using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace TermoDom;

public record GitHubPackage(Version Version, string Tag, Uri DownloadUrl, long Size, string Sha256);

/// <summary>Downloads a verified public release ZIP; never executes, installs or extracts it.</summary>
public sealed class GitHubUpdateService
{
    public static readonly Version CurrentVersion = new(2, 0, 0);
    const long MaximumSize = 500_000_000;
    public const string ReleaseApi = "https://api.github.com/repos/leo25matko-boop/termo/releases/latest";
    public string DownloadDirectory { get; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TermoDom", "downloads");
    readonly string preferencesPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TermoDom", "updates.json");
    readonly SemaphoreSlim gate = new(1, 1);
    public event Action? StatusChanged;
    public bool Enabled { get; private set; } = true;
    public string Status { get; private set; } = "Automatsko preuzimanje ZIP nadogradnji je uključeno.";
    public GitHubUpdateService()
    {
        try
        {
            if (File.Exists(preferencesPath)) { using var preferences = JsonDocument.Parse(File.ReadAllText(preferencesPath)); Enabled = preferences.RootElement.GetProperty("enabled").GetBoolean(); }
        }
        catch { }
        if (!Enabled) Status = "Automatsko preuzimanje je isključeno.";
    }
    public void SetEnabled(bool enabled)
    {
        Enabled = enabled; Directory.CreateDirectory(Path.GetDirectoryName(preferencesPath)!);
        File.WriteAllText(preferencesPath, JsonSerializer.Serialize(new { enabled }));
        SetStatus(enabled ? "Automatsko preuzimanje ZIP nadogradnji je uključeno." : "Automatsko preuzimanje je isključeno.");
    }
    void SetStatus(string message) { Status = message; StatusChanged?.Invoke(); }
    public void OpenFolder()
    {
        Directory.CreateDirectory(DownloadDirectory); Process.Start(new ProcessStartInfo(DownloadDirectory) { UseShellExecute = true });
    }
    public static GitHubPackage? SelectPackage(string metadata, Version current)
    {
        using var document = JsonDocument.Parse(metadata); var root = document.RootElement;
        if (root.TryGetProperty("draft", out var draft) && draft.ValueKind == JsonValueKind.True || root.TryGetProperty("prerelease", out var prerelease) && prerelease.ValueKind == JsonValueKind.True) return null;
        var tag = root.TryGetProperty("tag_name", out var tagValue) && tagValue.ValueKind == JsonValueKind.String ? tagValue.GetString()! : "";
        var match = Regex.Match(tag, @"^v?(\d{1,6})\.(\d{1,6})\.(\d{1,6})$");
        if (!match.Success || !Version.TryParse(string.Join('.', match.Groups.Cast<System.Text.RegularExpressions.Group>().Skip(1).Select(g => g.Value)), out var version)) throw new ControllerException("GitHub izdanje nema podržanu oznaku verzije.");
        if (version <= current) return null;
        if (!root.TryGetProperty("assets", out var assets) || assets.ValueKind != JsonValueKind.Array) throw new ControllerException("GitHub izdanje nema ZIP datoteku.");
        var candidates = assets.EnumerateArray().Where(a => a.TryGetProperty("name", out var name) && name.GetString() == "TermoDom-Windows.zip").ToArray();
        if (candidates.Length != 1) throw new ControllerException("GitHub izdanje nema jedinstveni TermoDom-Windows.zip.");
        var asset = candidates[0]; var url = asset.TryGetProperty("browser_download_url", out var location) ? location.GetString() : null;
        var expected = $"https://github.com/leo25matko-boop/termo/releases/download/{Uri.EscapeDataString(tag)}/TermoDom-Windows.zip";
        if (!Uri.TryCreate(url, UriKind.Absolute, out var download) || download.AbsoluteUri != expected) throw new ControllerException("ZIP adresa ne pripada izvornom GitHub repozitoriju.");
        if (!asset.TryGetProperty("size", out var sizeValue) || !sizeValue.TryGetInt64(out var size) || size is <= 0 or > MaximumSize) throw new ControllerException("Veličina ZIP nadogradnje nije podržana.");
        var digest = asset.TryGetProperty("digest", out var digestValue) && digestValue.ValueKind == JsonValueKind.String ? digestValue.GetString()! : "";
        if (!Regex.IsMatch(digest, @"^sha256:[0-9a-fA-F]{64}$")) throw new ControllerException("GitHub nije objavio SHA256 potvrdu ZIP datoteke. Preuzimanje je zaustavljeno.");
        return new(version, tag, download, size, digest[7..].ToLowerInvariant());
    }
    public static void ValidateHash(ReadOnlySpan<byte> digest, string expected)
    {
        var expectedBytes = Convert.FromHexString(expected);
        if (!CryptographicOperations.FixedTimeEquals(digest, expectedBytes)) throw new ControllerException("SHA256 ZIP datoteke ne odgovara GitHub potvrdi. Datoteka nije prihvaćena.");
    }
    static bool OfficialDownload(Uri uri, Uri original) => uri.Scheme == "https" && uri.IsDefaultPort && uri.UserInfo.Length == 0 && uri.Fragment.Length == 0 &&
        (uri.Host == "github.com" && uri.AbsolutePath == original.AbsolutePath || uri.Host is "release-assets.githubusercontent.com" or "objects.githubusercontent.com" or "github-releases.githubusercontent.com");
    static async Task<HttpResponseMessage> OpenDownload(HttpClient client, Uri original, CancellationToken cancellation)
    {
        var target = original;
        for (var hop = 0; hop < 6; hop++)
        {
            if (!OfficialDownload(target, original)) throw new ControllerException("GitHub preusmjeravanje nije dopušteno.");
            using var request = new HttpRequestMessage(HttpMethod.Get, target);
            request.Headers.UserAgent.ParseAdd("TermoDom/2.0-native-Windows");
            var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellation);
            if ((int)response.StatusCode is 301 or 302 or 303 or 307 or 308)
            {
                var location = response.Headers.Location; response.Dispose();
                if (location == null) throw new ControllerException("GitHub preusmjeravanje je nepotpuno.");
                target = new Uri(target, location); continue;
            }
            if (!response.IsSuccessStatusCode) { var code = (int)response.StatusCode; response.Dispose(); throw new ControllerException("GitHub ZIP nije dostupan (HTTP " + code + ")."); }
            return response;
        }
        throw new ControllerException("Previše GitHub preusmjeravanja.");
    }
    public async Task CheckAsync()
    {
        if (!await gate.WaitAsync(0)) return;
        try
        {
            SetStatus("Provjeravam GitHub nadogradnje…");
            using var handler = new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false };
            using var client = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
            using var metadataTimeout = new CancellationTokenSource(TimeSpan.FromSeconds(20));
            using var request = new HttpRequestMessage(HttpMethod.Get, ReleaseApi);
            request.Headers.UserAgent.ParseAdd("TermoDom/2.0-native-Windows"); request.Headers.Accept.ParseAdd("application/vnd.github+json");
            using var metadataResponse = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, metadataTimeout.Token);
            if (!metadataResponse.IsSuccessStatusCode) throw new ControllerException("GitHub provjera nije dostupna (HTTP " + (int)metadataResponse.StatusCode + ").");
            await using var metadataStream = await metadataResponse.Content.ReadAsStreamAsync(metadataTimeout.Token);
            using var metadataOutput = new MemoryStream(); var buffer = new byte[81920]; int read;
            while ((read = await metadataStream.ReadAsync(buffer, metadataTimeout.Token)) != 0)
            { if (metadataOutput.Length + read > 2_000_000) throw new ControllerException("GitHub odgovor je prevelik."); metadataOutput.Write(buffer, 0, read); }
            var package = SelectPackage(System.Text.Encoding.UTF8.GetString(metadataOutput.ToArray()), CurrentVersion);
            if (package == null) { SetStatus("Instalirana je aktualna verzija 2.0.0."); return; }
            var folder = Path.Combine(DownloadDirectory, package.Version.ToString(3)); Directory.CreateDirectory(folder);
            var final = Path.Combine(folder, "TermoDom-Windows.zip"); var partial = final + ".part";
            if (File.Exists(final))
            {
                await using var existing = File.OpenRead(final);
                try { ValidateHash(await SHA256.HashDataAsync(existing), package.Sha256); SetStatus("ZIP verzije " + package.Version + " već je preuzet i provjeren."); return; }
                catch (ControllerException) { /* Replace only the exact versioned ZIP through a verified atomic move. */ }
            }
            SetStatus("Preuzimam ZIP nadogradnju " + package.Version + "…");
            using var downloadTimeout = new CancellationTokenSource(TimeSpan.FromMinutes(10));
            try
            {
                using var response = await OpenDownload(client, package.DownloadUrl, downloadTimeout.Token);
                if (response.Content.Headers.ContentLength is long length && (length != package.Size || length > MaximumSize)) throw new ControllerException("Veličina GitHub datoteke ne odgovara objavljenoj.");
                await using var incoming = await response.Content.ReadAsStreamAsync(downloadTimeout.Token);
                using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256); long total = 0;
                await using (var output = new FileStream(partial, FileMode.Create, FileAccess.Write, FileShare.None, 81920, true))
                {
                    while ((read = await incoming.ReadAsync(buffer, downloadTimeout.Token)) != 0)
                    {
                        total += read; if (total > MaximumSize || total > package.Size) throw new ControllerException("ZIP nadogradnja je prevelika.");
                        hash.AppendData(buffer, 0, read); await output.WriteAsync(buffer.AsMemory(0, read), downloadTimeout.Token);
                    }
                    await output.FlushAsync(downloadTimeout.Token);
                }
                if (total != package.Size) throw new ControllerException("ZIP nadogradnja nije preuzeta u cijelosti.");
                ValidateHash(hash.GetHashAndReset(), package.Sha256); File.Move(partial, final, true);
                SetStatus("ZIP nadogradnja " + package.Version + " je preuzeta i SHA256 provjeren. Otvorite mapu preuzimanja.");
            }
            finally { if (File.Exists(partial)) try { File.Delete(partial); } catch { } }
        }
        catch (ControllerException error) { SetStatus(error.Message); }
        catch (Exception) { SetStatus("GitHub nadogradnja trenutačno nije dostupna. Pokušat ćemo pri sljedećem pokretanju."); }
        finally { gate.Release(); }
    }
}
