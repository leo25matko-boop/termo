using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace TermoDom;

public sealed record CameraLoginResult(string Status, byte[]? CaptchaBytes = null,
    string? VerificationPhone = null, string? VerificationEmail = null);
public sealed record CameraDevice(string Name, string Model, string Address, string SourceUrl)
{
    public override string ToString() => Name;
}
public sealed class CameraEngineException(string message) : Exception(message);

/// <summary>Private native camera transport. No browser, emulator, API client key, or external account is used.</summary>
public sealed class CameraEngine : IAsyncDisposable
{
    private readonly SemaphoreSlim gate = new(1, 1);
    private readonly string statePath;
    private readonly string executablePath;
    private Process? process;
    private HttpClient? client;
    private string apiUser = "", apiPassword = "", rtspUser = "", rtspPassword = "";
    private int apiPort, rtspPort;
    private string region = "de", streamName = "";
    private string? accountId;
    private Dictionary<string, string> savedTokens = new();
    private bool disposed;

    public CameraEngine()
    {
        executablePath = Path.Combine(AppContext.BaseDirectory, "camera", "termodom-camera.exe");
        string directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TermoDom");
        statePath = Path.Combine(directory, "camera-session.dpapi");
        LoadSession();
    }

    public bool HasSavedAccount => savedTokens.Count > 0;

    public async Task StartAsync(CancellationToken ct = default)
    {
        await gate.WaitAsync(ct);
        try { await StartCoreAsync(ct); }
        finally { gate.Release(); }
    }

    private async Task StartCoreAsync(CancellationToken ct)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        if (process is { HasExited: false } && client is not null) return;
        if (!File.Exists(executablePath))
            throw new CameraEngineException("Modul kamere nije pronađen. Ponovno instalirajte TermoDom.");
        apiPort = FreePort(); rtspPort = FreePort();
        while (rtspPort == apiPort) rtspPort = FreePort();
        apiUser = RandomSecret(); apiPassword = RandomSecret(); rtspUser = RandomSecret(); rtspPassword = RandomSecret();
        client?.Dispose();
        client = new HttpClient(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false })
        { BaseAddress = new Uri($"http://127.0.0.1:{apiPort}/"), Timeout = TimeSpan.FromSeconds(35) };
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Basic",
            Convert.ToBase64String(Encoding.UTF8.GetBytes(apiUser + ":" + apiPassword)));
        string config = JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["app"] = new { modules = new[] { "api", "rtsp", "xiaomi" } },
            ["api"] = new { listen = $"127.0.0.1:{apiPort}", username = apiUser, password = apiPassword,
                local_auth = true, origin = "", allow_paths = new[] { "/api", "/api/xiaomi", "/api/streams", "/api/termodom/session" } },
            ["rtsp"] = new { listen = $"127.0.0.1:{rtspPort}", username = rtspUser, password = rtspPassword },
            ["webrtc"] = new { listen = "" },
            ["log"] = new { level = "disabled", output = "" },
            ["xiaomi"] = savedTokens,
            ["streams"] = new Dictionary<string, string>()
        });
        var start = new ProcessStartInfo(executablePath)
        {
            UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden,
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
            WorkingDirectory = Path.GetDirectoryName(executablePath)!
        };
        start.ArgumentList.Add("-config"); start.ArgumentList.Add("-");
        process?.Dispose();
        process = Process.Start(start) ?? throw new CameraEngineException("Modul kamere nije moguće pokrenuti.");
        // Process arguments contain no credentials. Configuration travels through an anonymous pipe.
        _ = DrainAsync(process.StandardOutput);
        _ = DrainAsync(process.StandardError);
        await process.StandardInput.WriteAsync(config.AsMemory(), ct);
        process.StandardInput.Close();
        long deadline = Environment.TickCount64 + 12_000;
        while (Environment.TickCount64 < deadline)
        {
            ct.ThrowIfCancellationRequested();
            if (process.HasExited) break;
            try
            {
                using var healthDeadline = CancellationTokenSource.CreateLinkedTokenSource(ct);
                healthDeadline.CancelAfter(1000);
                using var health = await client.GetAsync("api/termodom/session", HttpCompletionOption.ResponseHeadersRead, healthDeadline.Token);
                if (health.IsSuccessStatusCode) { await RequireIsolationAsync(ct); return; }
            }
            catch (HttpRequestException) { }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested) { }
            await Task.Delay(120, ct);
        }
        KillProcess();
        throw new CameraEngineException("Modul kamere nije spreman. Ponovno otvorite povezivanje kamere.");
    }

    public async Task<CameraLoginResult> BeginLoginAsync(string username, string password, string region = "de", CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(username) || string.IsNullOrEmpty(password))
            throw new CameraEngineException("Unesite Xiaomi korisničko ime i lozinku.");
        this.region = SafeRegion(region);
        return await AuthenticateAsync(new() { ["username"] = username.Trim(), ["password"] = password }, ct);
    }
    public Task<CameraLoginResult> SubmitCaptchaAsync(string code, CancellationToken ct = default) =>
        AuthenticateAsync(new() { ["captcha"] = RequiredCode(code) }, ct);
    public Task<CameraLoginResult> SubmitVerificationAsync(string code, CancellationToken ct = default) =>
        AuthenticateAsync(new() { ["verify"] = RequiredCode(code) }, ct);

    private async Task<CameraLoginResult> AuthenticateAsync(Dictionary<string, string> fields, CancellationToken ct)
    {
        await gate.WaitAsync(ct);
        try
        {
            await StartCoreAsync(ct);
            using var form = new FormUrlEncodedContent(fields);
            using var request = new HttpRequestMessage(HttpMethod.Post, "api/xiaomi") { Content = form };
            using var response = await client!.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
            string body = await ReadLimitedAsync(response, ct);
            CameraLoginResult result = ParseLoginResponse(response.StatusCode, body);
            if (result.Status == "success") { await SaveSessionAsync(ct); accountId = savedTokens.Keys.FirstOrDefault(); }
            return result;
        }
        catch (CameraEngineException) { throw; }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (Exception) { throw new CameraEngineException("Xiaomi prijava trenutačno nije dostupna. Provjerite vezu i pokušajte ponovno."); }
        finally { fields.Clear(); gate.Release(); }
    }

    internal static CameraLoginResult ParseLoginResponse(HttpStatusCode status, string body)
    {
        if ((int)status is >= 200 and < 300) return new("success");
        if (status == HttpStatusCode.Unauthorized)
        {
            try
            {
                using var json = JsonDocument.Parse(body);
                var root = json.RootElement;
                string? captcha = Value(root, "captcha");
                if (!string.IsNullOrEmpty(captcha) && captcha.Length <= 1_000_000)
                    return new("captcha", Convert.FromBase64String(captcha));
                string? phone = Value(root, "verify_phone"), email = Value(root, "verify_email");
                if (!string.IsNullOrEmpty(phone) || !string.IsNullOrEmpty(email)) return new("verify", null, phone, email);
            }
            catch (Exception) { }
        }
        throw new CameraEngineException("Prijava nije uspjela. Provjerite Xiaomi račun ili zatraženi potvrdni kod.");
    }

    public async Task<IReadOnlyList<CameraDevice>> ListDevicesAsync(string region = "de", CancellationToken ct = default)
    {
        await gate.WaitAsync(ct);
        try
        {
            await StartCoreAsync(ct);
            this.region = SafeRegion(region);
            if (accountId is null)
            {
                using var accountsResponse = await client!.GetAsync("api/xiaomi", HttpCompletionOption.ResponseHeadersRead, ct);
                using var accounts = JsonDocument.Parse(await SuccessfulBodyAsync(accountsResponse, ct));
                accountId = accounts.RootElement.EnumerateArray().Select(v => v.GetString()).FirstOrDefault(v => !string.IsNullOrEmpty(v));
            }
            if (accountId is null) throw new CameraEngineException("Najprije se prijavite svojim Xiaomi računom.");
            using var response = await client!.GetAsync("api/xiaomi?id=" + Uri.EscapeDataString(accountId) + "&region=" + this.region, HttpCompletionOption.ResponseHeadersRead, ct);
            if (response.StatusCode == HttpStatusCode.NotFound) return Array.Empty<CameraDevice>();
            using var document = JsonDocument.Parse(await SuccessfulBodyAsync(response, ct));
            var result = new List<CameraDevice>();
            // API ResponseSources wraps sources under a fixed JSON field.
            var entries = document.RootElement.ValueKind == JsonValueKind.Array ? document.RootElement : document.RootElement.GetProperty("sources");
            foreach (var entry in entries.EnumerateArray())
            {
                string source = Value(entry, "url") ?? "";
                if (!Uri.TryCreate(source, UriKind.Absolute, out var address) || address.Scheme != "xiaomi") continue;
                var query = ParseQuery(address.Query);
                string model = query.GetValueOrDefault("model", "");
                result.Add(new CameraDevice(CleanText(Value(entry, "name") ?? "Xiaomi kamera"), CleanText(model), address.Host, source));
            }
            return result;
        }
        catch (CameraEngineException) { throw; }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (Exception) { throw new CameraEngineException("Popis kamera nije dostupan. Provjerite prijavu i regiju Europa."); }
        finally { gate.Release(); }
    }

    public async Task<Uri> OpenStreamAsync(CameraDevice device, CancellationToken ct = default)
    {
        await gate.WaitAsync(ct);
        try
        {
            await StartCoreAsync(ct);
            if (!Uri.TryCreate(device.SourceUrl, UriKind.Absolute, out var source) || source.Scheme != "xiaomi")
                throw new CameraEngineException("Odabrana kamera nema valjano povezivanje.");
            await StopStreamCoreAsync(ct);
            streamName = "camera_" + RandomSecret();
            using var request = new HttpRequestMessage(HttpMethod.Put, "api/streams?name=" + streamName + "&src=" + Uri.EscapeDataString(device.SourceUrl));
            using var response = await client!.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
            _ = await SuccessfulBodyAsync(response, ct);
            return new Uri($"rtsp://{rtspUser}:{rtspPassword}@127.0.0.1:{rtspPort}/{streamName}");
        }
        catch (CameraEngineException) { throw; }
        catch (Exception) { throw new CameraEngineException("Povezivanje kamere nije uspjelo. Kamera i računalo trebaju biti na istoj lokalnoj mreži."); }
        finally { gate.Release(); }
    }

    public async Task StopStreamAsync(CancellationToken ct = default)
    {
        await gate.WaitAsync(ct);
        try { await StopStreamCoreAsync(ct); }
        finally { gate.Release(); }
    }
    private async Task StopStreamCoreAsync(CancellationToken ct)
    {
        if (streamName.Length == 0 || client is null) return;
        string previous = streamName; streamName = "";
        try { using var request = new HttpRequestMessage(HttpMethod.Delete, "api/streams?src=" + Uri.EscapeDataString(previous)); using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct); }
        catch (HttpRequestException) { }
    }

    public async Task LogoutAsync(CancellationToken ct = default)
    {
        await gate.WaitAsync(ct);
        try
        {
            KillProcess(); savedTokens.Clear(); accountId = null; streamName = "";
            if (File.Exists(statePath)) File.Delete(statePath);
        }
        finally { gate.Release(); }
    }

    private void LoadSession()
    {
        try
        {
            if (!File.Exists(statePath)) return;
            byte[] plain = ProtectedData.Unprotect(File.ReadAllBytes(statePath), null, DataProtectionScope.CurrentUser);
            try { savedTokens = JsonSerializer.Deserialize<Dictionary<string, string>>(plain) ?? new(); }
            finally { CryptographicOperations.ZeroMemory(plain); }
        }
        catch (Exception) { savedTokens = new(); }
    }
    private async Task SaveSessionAsync(CancellationToken ct)
    {
        using var response = await client!.GetAsync("api/termodom/session", HttpCompletionOption.ResponseHeadersRead, ct);
        byte[] plain = Encoding.UTF8.GetBytes(await SuccessfulBodyAsync(response, ct));
        try
        {
            savedTokens = JsonSerializer.Deserialize<Dictionary<string, string>>(plain) ?? new();
            if (savedTokens.Count == 0) throw new CameraEngineException("Xiaomi sesija nije spremljena. Ponovno se prijavite.");
            Directory.CreateDirectory(Path.GetDirectoryName(statePath)!);
            byte[] encrypted = ProtectedData.Protect(plain, null, DataProtectionScope.CurrentUser);
            await File.WriteAllBytesAsync(statePath + ".tmp", encrypted, ct);
            File.Move(statePath + ".tmp", statePath, true);
        }
        finally { CryptographicOperations.ZeroMemory(plain); }
    }

    private async Task RequireIsolationAsync(CancellationToken ct)
    {
        using var anonymous = new HttpClient(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(3) };
        using var denied = await anonymous.GetAsync($"http://127.0.0.1:{apiPort}/api", HttpCompletionOption.ResponseHeadersRead, ct);
        if (denied.StatusCode != HttpStatusCode.Unauthorized) { KillProcess(); throw new CameraEngineException("Privatna zaštita modula kamere nije ispravna."); }
        using var socket = new TcpClient();
        await socket.ConnectAsync(IPAddress.Loopback, rtspPort, ct);
        var data = Encoding.ASCII.GetBytes($"DESCRIBE rtsp://127.0.0.1:{rtspPort}/private-check RTSP/1.0\r\nCSeq: 1\r\nAccept: application/sdp\r\n\r\n");
        await socket.GetStream().WriteAsync(data, ct);
        byte[] reply = new byte[2048];
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct); deadline.CancelAfter(3000);
        int received = await socket.GetStream().ReadAsync(reply, deadline.Token);
        if (!Encoding.ASCII.GetString(reply, 0, received).StartsWith("RTSP/1.0 401"))
        { KillProcess(); throw new CameraEngineException("Privatni video prijenos nije zaštićen."); }
    }

    private static async Task<string> SuccessfulBodyAsync(HttpResponseMessage response, CancellationToken ct)
    {
        string body = await ReadLimitedAsync(response, ct);
        if (!response.IsSuccessStatusCode) throw new CameraEngineException("Modul kamere trenutačno ne može obraditi zahtjev.");
        return body;
    }
    private static async Task<string> ReadLimitedAsync(HttpResponseMessage response, CancellationToken ct)
    {
        if (response.Content.Headers.ContentLength > 2_000_000) throw new CameraEngineException("Odgovor kamere je prevelik.");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct); deadline.CancelAfter(35_000);
        using var input = await response.Content.ReadAsStreamAsync(deadline.Token);
        using var output = new MemoryStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = await input.ReadAsync(buffer, deadline.Token)) != 0)
        {
            if (output.Length + count > 2_000_000) throw new CameraEngineException("Odgovor kamere je prevelik.");
            output.Write(buffer, 0, count);
        }
        return Encoding.UTF8.GetString(output.GetBuffer(), 0, checked((int)output.Length));
    }
    private static int FreePort() { var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start(); int port = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop(); return port; }
    private static string RandomSecret() => Convert.ToHexString(RandomNumberGenerator.GetBytes(24)).ToLowerInvariant();
    private static string RequiredCode(string code) => string.IsNullOrWhiteSpace(code) ? throw new CameraEngineException("Unesite zatraženi potvrdni kod.") : code.Trim();
    private static string SafeRegion(string region) => new[] { "de", "i2", "ru", "sg", "us", "cn" }.Contains(region) ? region : "de";
    private static string? Value(JsonElement root, string key) => root.TryGetProperty(key, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;
    private static string CleanText(string value) => new(value.Where(c => !char.IsControl(c)).Take(160).ToArray());
    private static Dictionary<string, string> ParseQuery(string value) => value.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries)
        .Select(pair => pair.Split('=', 2)).Where(pair => pair.Length == 2).ToDictionary(pair => Uri.UnescapeDataString(pair[0]), pair => Uri.UnescapeDataString(pair[1]));
    private static async Task DrainAsync(StreamReader stream) { try { char[] buffer = new char[2048]; while (await stream.ReadAsync(buffer) > 0) { } } catch (Exception) { } }
    private void KillProcess() { if (process is not null) { try { if (!process.HasExited) process.Kill(true); } catch (Exception) { } process.Dispose(); process = null; } client?.Dispose(); client = null; }
    public async ValueTask DisposeAsync() { await gate.WaitAsync(); try { if (disposed) return; disposed = true; KillProcess(); } finally { gate.Release(); } }
}
