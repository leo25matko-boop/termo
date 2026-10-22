using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace TermoDom;

public record SolarData(string Name, double? Power, double? Daily, double? Monthly, double? Yearly, double? Total,
    double? Co2, double? Coal, double? Trees, ChartData? Curve);

public sealed class SolarRepository
{
    static readonly string ConfigPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TermoDom", "connections.json");
    public string? SharedUrl { get; private set; }
    public SolarRepository()
    {
        try { if (File.Exists(ConfigPath)) Import(File.ReadAllText(ConfigPath), false); } catch { /* Invalid local configuration is never sent to the network. */ }
    }
    public static Uri Endpoint(string sharedUrl)
    {
        if (!Uri.TryCreate(sharedUrl.Trim(), UriKind.Absolute, out var uri) || uri.Scheme != "https" || !uri.IsDefaultPort || uri.UserInfo.Length != 0 ||
            !Regex.IsMatch(uri.Host, @"^uni[0-9a-z]+\.fusionsolar\.huawei\.com$", RegexOptions.IgnoreCase) ||
            uri.AbsolutePath != "/pvmswebsite/nologin/assets/build/cloud.html")
            throw new ControllerException("Umetnite izvornu HTTPS poveznicu za FusionSolar kiosk.");
        var fragment = uri.Fragment.TrimStart('#');
        if (!fragment.StartsWith("/kiosk?", StringComparison.Ordinal)) throw new ControllerException("Poveznica nije FusionSolar kiosk.");
        var fields = fragment[7..].Split('&').Select(x => x.Split('=', 2)).Where(x => x.Length == 2).ToArray();
        var keys = fields.Where(x => x[0] == "kk").ToArray();
        if (keys.Length != 1) throw new ControllerException("Poveznica nema jedinstvenu oznaku kioska.");
        var token = Uri.UnescapeDataString(keys[0][1]);
        if (!Regex.IsMatch(token, @"^[A-Za-z0-9_-]{10,256}$")) throw new ControllerException("Oznaka kioska nije ispravna.");
        return new Uri($"https://{uri.Host}/rest/pvms/web/kiosk/v1/station-kiosk-file?kk={Uri.EscapeDataString(token)}");
    }
    public void SetUrl(string url, bool save = true)
    {
        Endpoint(url); SharedUrl = url.Trim();
        if (save)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(ConfigPath)!);
            File.WriteAllText(ConfigPath, JsonSerializer.Serialize(new { version = 1, solarKioskUrl = SharedUrl }));
        }
    }
    public void Import(string json, bool save = true)
    {
        if (json.Length > 16384) throw new ControllerException("Datoteka povezivanja je prevelika.");
        using var doc = JsonDocument.Parse(json);
        if (!doc.RootElement.TryGetProperty("version", out var version) || version.GetInt32() != 1 || !doc.RootElement.TryGetProperty("solarKioskUrl", out var url) || url.ValueKind != JsonValueKind.String)
            throw new ControllerException("Datoteka povezivanja nema podržani oblik.");
        SetUrl(url.GetString()!, save);
    }
    public void Disconnect() { SharedUrl = null; if (File.Exists(ConfigPath)) File.Delete(ConfigPath); }
    public async Task<SolarData> Read()
    {
        if (SharedUrl == null) throw new ControllerException("Solarna elektrana još nije povezana.");
        var endpoint = Endpoint(SharedUrl);
        using var handler = new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false };
        using var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(20) };
        using var request = new HttpRequestMessage(HttpMethod.Get, endpoint);
        request.Headers.UserAgent.ParseAdd("TermoDom/2.0-native-Windows");
        request.Headers.TryAddWithoutValidation("Accept", "application/json");
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
        if (!response.IsSuccessStatusCode) throw new ControllerException($"FusionSolar nije dostupan (HTTP {(int)response.StatusCode}).");
        if (response.Content.Headers.ContentLength > 5_000_000) throw new ControllerException("FusionSolar odgovor je prevelik.");
        using var limited = new MemoryStream(); await using var stream = await response.Content.ReadAsStreamAsync(timeout.Token); var bytes = new byte[8192]; int count;
        while ((count = await stream.ReadAsync(bytes, timeout.Token)) != 0) { if (limited.Length + count > 5_000_000) throw new ControllerException("FusionSolar odgovor je prevelik."); limited.Write(bytes, 0, count); }
        return Parse(System.Text.Encoding.UTF8.GetString(limited.ToArray()));
    }
    public static SolarData Parse(string response)
    {
        using var outer = JsonDocument.Parse(response);
        if (!outer.RootElement.TryGetProperty("success", out var success) || success.ValueKind != JsonValueKind.True || !outer.RootElement.TryGetProperty("data", out var data) || data.ValueKind != JsonValueKind.String)
            throw new ControllerException("FusionSolar nije vratio podatke kioska.");
        using var inner = JsonDocument.Parse(WebUtility.HtmlDecode(data.GetString()!));
        var root = inner.RootElement;
        JsonElement Obj(string name) => root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Object ? value : default;
        string? String(JsonElement obj, string name) => obj.ValueKind == JsonValueKind.Object && obj.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;
        double? Numeric(JsonElement obj, string name) => obj.ValueKind == JsonValueKind.Object && obj.TryGetProperty(name, out var value) ? NumericValue(value) : null;
        var overview = Obj("stationOverview"); var kpi = Obj("realKpi"); var social = Obj("socialContribution"); var power = Obj("powerCurve"); ChartData? curve = null;
        if (overview.ValueKind != JsonValueKind.Object || kpi.ValueKind != JsonValueKind.Object) throw new ControllerException("FusionSolar nema očekivane podatke elektrane.");
        if (power.ValueKind == JsonValueKind.Object && power.TryGetProperty("xAxis", out var labels) && labels.ValueKind == JsonValueKind.Array && power.TryGetProperty("activePower", out var active) && active.ValueKind == JsonValueKind.Array)
        {
            var x = labels.EnumerateArray().Select(v => v.ValueKind == JsonValueKind.String ? v.GetString()! : "").ToArray();
            var y = active.EnumerateArray().Select(NumericValue).ToArray();
            if (x.Length > 0 && x.Length <= 10000 && x.Length == y.Length) curve = new ChartData(new("solar", "Dnevna proizvodnja", "", "kW"), x, [new("Proizvodnja", y, "#E7B469", false)]);
        }
        return new(String(overview, "stationName") ?? "Solarna elektrana", Numeric(kpi, "realTimePower"), Numeric(kpi, "dailyEnergy"), Numeric(kpi, "monthEnergy"), Numeric(kpi, "yearEnergy"), Numeric(kpi, "cumulativeEnergy"),
            Numeric(social, "co2Reduction"), Numeric(social, "standardCoalSavings"), Numeric(social, "equivalentTreePlanting"), curve);
    }
    static double? NumericValue(JsonElement value)
    {
        if (value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out var n) && double.IsFinite(n)) return n;
        if (value.ValueKind == JsonValueKind.String && double.TryParse(value.GetString(), CultureInfo.InvariantCulture, out n) && double.IsFinite(n)) return n;
        return null;
    }
}
