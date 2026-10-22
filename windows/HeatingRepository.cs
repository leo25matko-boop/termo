using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Security;
using System.Net.Sockets;
using System.Text;

namespace TermoDom;

public record SourceResponse(int Status, string Body, IReadOnlyDictionary<string, string[]> Headers)
{
    public SourceResponse(int status, string body) : this(status, body, new Dictionary<string, string[]>()) { }
    public string? Header(string name) => Headers.FirstOrDefault(x => x.Key.Equals(name, StringComparison.OrdinalIgnoreCase)).Value?.FirstOrDefault();
}
public delegate Task<SourceResponse> SourceTransport(Uri uri, string cookie, bool mutation);

public sealed class HeatingRepository
{
    public const string Source = "https://automatiziraj.se/centralno/php/novo.php";
    static readonly Uri Base = new("https://automatiziraj.se/centralno/php/");
    static readonly HashSet<string> ReadPaths = new[] { "novo.php", "kat.php", "prizemlje.php", "podesavanje.php" }.Concat(ChartParser.Catalog.Select(x => x.Path)).Select(x => Base.AbsolutePath + x).ToHashSet(StringComparer.Ordinal);
    static readonly HashSet<string> WritePaths = ["/centralno/php/dodajPODESAVANJA.php", "/centralno/php/zapisi_postavke.php"];
    const string LogoutPath = "/centralno/scr/logout.scr.php";
    const int MaxResponse = 5_000_000;
    readonly SourceTransport transport;
    readonly CookieContainer cookies = new();
    readonly SemaphoreSlim saveLock = new(1, 1);
    public HeatingRepository(SourceTransport? transport = null) => this.transport = transport ?? NativeRequest;
    public static Uri SafeUrl(string path)
    {
        if (!Uri.TryCreate(Base, path, out var uri) || uri.Scheme != "https" || uri.Host != "automatiziraj.se" || !uri.IsDefaultPort || uri.UserInfo.Length != 0 || uri.Fragment.Length != 0 ||
            (!ReadPaths.Contains(uri.AbsolutePath) && !WritePaths.Contains(uri.AbsolutePath) && uri.AbsolutePath != LogoutPath))
            throw new ControllerException("Izvorna adresa nije na popisu dopuštenih funkcija.");
        return uri;
    }
    static async Task<SourceResponse> NativeRequest(Uri uri, string cookie, bool mutation)
    {
        SafeUrl(uri.AbsoluteUri);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        if (mutation) return await SingleTransmit(uri, cookie, timeout.Token);
        using var handler = new SocketsHttpHandler { AllowAutoRedirect = false, UseCookies = false, ConnectTimeout = TimeSpan.FromSeconds(20) };
        using var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(20) };
        using var request = new HttpRequestMessage(HttpMethod.Get, uri);
        request.Headers.UserAgent.ParseAdd("TermoDom/2.0 (native-Windows)");
        request.Headers.TryAddWithoutValidation("Accept", "text/html");
        if (cookie.Length != 0) request.Headers.TryAddWithoutValidation("Cookie", cookie);
        using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
        var headers = response.Headers.Concat(response.Content.Headers).ToDictionary(x => x.Key, x => x.Value.ToArray(), StringComparer.OrdinalIgnoreCase);
        if ((int)response.StatusCode is >= 300 and < 400) return new((int)response.StatusCode, "", headers);
        if (response.Content.Headers.ContentLength > MaxResponse) throw new ControllerException("Izvorni odgovor je prevelik.");
        await using var stream = await response.Content.ReadAsStreamAsync(timeout.Token);
        using var output = new MemoryStream(); var buffer = new byte[8192]; int read;
        while ((read = await stream.ReadAsync(buffer, timeout.Token)) != 0)
        { if (output.Length + read > MaxResponse) throw new ControllerException("Izvorni odgovor je prevelik."); output.Write(buffer, 0, read); }
        return new((int)response.StatusCode, Encoding.UTF8.GetString(output.ToArray()), headers);
    }
    // Source mutations use GET. A single fresh TLS socket guarantees that the request is never
    // automatically replayed by HTTP connection recovery, redirects or authentication retries.
    static async Task<SourceResponse> SingleTransmit(Uri uri, string cookie, CancellationToken cancellation)
    {
        using var socket = new TcpClient(); await socket.ConnectAsync(uri.Host, 443, cancellation);
        await using var tls = new SslStream(socket.GetStream(), false);
        await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions { TargetHost = uri.Host }, cancellation);
        var text = $"GET {uri.PathAndQuery} HTTP/1.1\r\nHost: {uri.Host}\r\nConnection: close\r\nAccept: text/html\r\nAccept-Encoding: identity\r\nUser-Agent: TermoDom/2.0-native-Windows\r\n";
        if (cookie.Length != 0) text += "Cookie: " + cookie + "\r\n";
        await tls.WriteAsync(Encoding.ASCII.GetBytes(text + "\r\n"), cancellation); await tls.FlushAsync(cancellation);
        using var raw = new MemoryStream(); var bytes = new byte[8192]; int length;
        while ((length = await tls.ReadAsync(bytes, cancellation)) != 0)
        { if (raw.Length + length > MaxResponse + 65536) throw new ControllerException("Izvorni odgovor je prevelik."); raw.Write(bytes, 0, length); }
        var all = raw.ToArray(); var split = -1;
        for (var i = 0; i + 3 < all.Length; i++) if (all[i] == 13 && all[i + 1] == 10 && all[i + 2] == 13 && all[i + 3] == 10) { split = i; break; }
        if (split < 0 || split > 65536) throw new IOException("Nepotpun HTTP odgovor.");
        var lines = Encoding.ASCII.GetString(all, 0, split).Split("\r\n");
        if (lines.Length == 0 || !int.TryParse(lines[0].Split(' ').ElementAtOrDefault(1), out var status)) throw new IOException("Neispravan HTTP odgovor.");
        var headers = new Dictionary<string, string[]>(StringComparer.OrdinalIgnoreCase);
        foreach (var line in lines.Skip(1))
        {
            var separator = line.IndexOf(':'); if (separator <= 0) continue;
            var key = line[..separator]; var value = line[(separator + 1)..].Trim();
            headers[key] = headers.TryGetValue(key, out var existing) ? [.. existing, value] : [value];
        }
        var body = all[(split + 4)..];
        if (headers.TryGetValue("Transfer-Encoding", out var encodings) && encodings.Any(x => x.Contains("chunked", StringComparison.OrdinalIgnoreCase)))
        {
            using var decoded = new MemoryStream(); var pos = 0;
            while (pos < body.Length)
            {
                var end = pos; while (end + 1 < body.Length && !(body[end] == 13 && body[end + 1] == 10)) end++;
                if (end + 1 >= body.Length || !int.TryParse(Encoding.ASCII.GetString(body, pos, end - pos).Split(';')[0], NumberStyles.HexNumber, CultureInfo.InvariantCulture, out var size) || size < 0) throw new IOException("Neispravni HTTP blokovi.");
                if (size == 0) break;
                pos = end + 2;
                if (pos + size + 2 > body.Length || decoded.Length + size > MaxResponse) throw new IOException("Nepotpun HTTP blok.");
                decoded.Write(body, pos, size); pos += size + 2;
            }
            body = decoded.ToArray();
        }
        else if (headers.TryGetValue("Content-Length", out var lengths) && int.TryParse(lengths[0], out var expected) && body.Length != expected) throw new IOException("Nepotpun HTTP odgovor.");
        if (body.Length > MaxResponse) throw new IOException("Prevelik HTTP odgovor.");
        return new(status, Encoding.UTF8.GetString(body), headers);
    }
    async Task<string> Request(string path, IReadOnlyDictionary<string, string>? parameters = null, bool mutation = false, bool logout = false)
    {
        var mutationAttempted = mutation;
        var uri = SafeUrl(path);
        if (!(mutation ? WritePaths.Contains(uri.AbsolutePath) : logout ? uri.AbsolutePath == LogoutPath : ReadPaths.Contains(uri.AbsolutePath))) throw new ControllerException("Ova izvorna funkcija nije dopuštena za traženi način rada.");
        if (parameters != null) uri = new UriBuilder(uri) { Query = string.Join('&', parameters.Select(x => Uri.EscapeDataString(x.Key) + "=" + Uri.EscapeDataString(x.Value))) }.Uri;
        for (var hop = 0; hop <= 4; hop++)
        {
            SourceResponse response;
            try { response = await transport(uri, cookies.GetCookieHeader(uri), mutation); }
            catch (Exception) { throw new ControllerException("Veza s izvornim sustavom nije dostupna." + (mutationAttempted ? " Potvrda spremanja nije primljena; osvježite podatke prije ponavljanja." : ""), mutationUnconfirmed: mutationAttempted); }
            foreach (var header in response.Headers.Where(x => x.Key.Equals("Set-Cookie", StringComparison.OrdinalIgnoreCase)))
                foreach (var value in header.Value) try { cookies.SetCookies(uri, value); } catch (CookieException) { }
            if (response.Status is 301 or 302 or 303 or 307 or 308)
            {
                if (logout) return "";
                var location = response.Header("Location") ?? throw new ControllerException("Neispravno preusmjeravanje.", mutationUnconfirmed: mutationAttempted);
                Uri next;
                try { next = new Uri(uri, location); } catch { throw new ControllerException("Neispravno preusmjeravanje.", mutationUnconfirmed: mutationAttempted); }
                if (next.AbsolutePath.Contains("login", StringComparison.OrdinalIgnoreCase) || next.AbsolutePath.Contains("prijava", StringComparison.OrdinalIgnoreCase)) throw new ControllerException("Izvorni sustav traži prijavu.", true, mutationAttempted);
                try { uri = SafeUrl(next.AbsoluteUri); } catch { throw new ControllerException("Neočekivano preusmjeravanje izvornog sustava.", mutationUnconfirmed: mutationAttempted); }
                if (!ReadPaths.Contains(uri.AbsolutePath)) throw new ControllerException("Neočekivano preusmjeravanje izvornog sustava.", mutationUnconfirmed: mutationAttempted);
                mutation = false; // Redirects can read a result, but can never replay a mutation.
                continue;
            }
            if (response.Status is < 200 or >= 300) throw new ControllerException($"Izvorni sustav vratio je HTTP {response.Status}.", response.Status is 401 or 403, mutationAttempted);
            if (Encoding.UTF8.GetByteCount(response.Body) > MaxResponse) throw new ControllerException("Izvorni odgovor je prevelik.", mutationUnconfirmed: mutationAttempted);
            if (!logout && HeatingParser.DetectLogin(response.Body)) throw new ControllerException("Izvorni sustav traži prijavu.", true, mutationAttempted);
            return response.Body;
        }
        throw new ControllerException("Previše preusmjeravanja.", mutationUnconfirmed: mutationAttempted);
    }
    public async Task<HeatingStatus> Status() => HeatingParser.Status(await Request("novo.php"));
    public async Task<Schedule> Schedule(string id) { HeatingParser.RequireZone(id); return HeatingParser.Schedule(await Request(id + ".php"), id); }
    public async Task<SettingsData> Settings() => HeatingParser.Settings(await Request("podesavanje.php"));
    public async Task<ChartData> Chart(string id) => ChartParser.Parse(await Request(ChartParser.Find(id).Path), id);
    public async Task<Schedule> SaveSchedule(string id, double[] values)
    {
        if (!await saveLock.WaitAsync(0)) throw new ControllerException("Drugo spremanje je u tijeku.");
        try
        {
            var requested = (double[])values.Clone(); var current = await Schedule(id); HeatingParser.ValidateSchedule(requested, current);
            if (SafeUrl(current.Action).AbsolutePath != "/centralno/php/dodajPODESAVANJA.php" || current.Method != "GET") throw new ControllerException("Izvorni način spremanja rasporeda promijenjen je. Spremanje je zaustavljeno.");
            var parameters = current.Fields.Select((f, i) => (f.Name, Value: requested[i].ToString("R", CultureInfo.InvariantCulture))).ToDictionary(x => x.Name, x => x.Value);
            foreach (var pair in current.Hidden) parameters.Add(pair.Key, pair.Value);
            await Request(current.Action, parameters, true);
            Schedule saved;
            try { saved = await Schedule(id); } catch { throw new ControllerException("Zahtjev je poslan, ali ponovna provjera nije uspjela. Osvježite prije ponovnog spremanja.", mutationUnconfirmed: true); }
            if (saved.Fields.Where((f, i) => Math.Abs(f.Value - requested[i]) > 0.00001).Any()) throw new ControllerException("Izvor nije potvrdio sve temperature. Osvježite i provjerite raspored.", mutationUnconfirmed: true);
            return saved;
        }
        finally { saveLock.Release(); }
    }
    public async Task<SettingsData> SaveSettings(double[] values)
    {
        if (!await saveLock.WaitAsync(0)) throw new ControllerException("Drugo spremanje je u tijeku.");
        try
        {
            var requested = (double[])values.Clone(); HeatingParser.ValidateSettings(requested); var current = await Settings();
            if (SafeUrl(current.Action).AbsolutePath != "/centralno/php/zapisi_postavke.php" || current.Method != "GET") throw new ControllerException("Izvorni način spremanja postavki promijenjen je. Spremanje je zaustavljeno.");
            var parameters = requested.Select((v, i) => (Key: "n" + i, Value: v.ToString("R", CultureInfo.InvariantCulture))).ToDictionary(x => x.Key, x => x.Value);
            await Request(current.Action, parameters, true);
            SettingsData saved;
            try { saved = await Settings(); } catch { throw new ControllerException("Zahtjev je poslan, ali ponovna provjera nije uspjela. Osvježite prije ponovnog spremanja.", mutationUnconfirmed: true); }
            if (saved.Fields.Where((f, i) => Math.Abs(f.Value - requested[i]) > 0.00001).Any()) throw new ControllerException("Izvor nije potvrdio sve postavke. Osvježite i provjerite vrijednosti.", mutationUnconfirmed: true);
            return saved;
        }
        finally { saveLock.Release(); }
    }
    public async Task Logout() { try { await Request(LogoutPath, logout: true); } finally { foreach (Cookie cookie in cookies.GetAllCookies()) cookie.Expired = true; } }
}
