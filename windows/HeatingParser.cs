using HtmlAgilityPack;
using System.Globalization;
using System.Net;
using System.Text.RegularExpressions;

namespace TermoDom;

/// <summary>The controller serves HTML. It is parsed as data and never rendered or executed.</summary>
public static class HeatingParser
{
    static readonly RegexOptions Flags = RegexOptions.IgnoreCase | RegexOptions.Singleline;
    public static string? Match(string? html, string expression)
    {
        var m = Regex.Match(html ?? "", expression, Flags, TimeSpan.FromSeconds(2));
        return m.Success ? m.Groups[1].Value : null;
    }
    public static HtmlDocument Document(string html) { var doc = new HtmlDocument(); doc.LoadHtml(html); return doc; }
    public static string Text(string? html)
    {
        var doc = Document(html ?? "");
        foreach (var node in (doc.DocumentNode.SelectNodes("//script|//style")?.ToArray() ?? [])) node.Remove();
        return Regex.Replace(WebUtility.HtmlDecode(doc.DocumentNode.InnerText), @"\s+", " ").Trim();
    }
    public static double? Number(string? text)
    {
        var m = Regex.Match(text ?? "", @"-?\d+(?:[.,]\d+)?");
        return m.Success && double.TryParse(m.Value.Replace(',', '.'), CultureInfo.InvariantCulture, out var n) && double.IsFinite(n) ? n : null;
    }
    static double SourceNumber(string value) => Regex.IsMatch(value.Trim(), @"^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$", RegexOptions.IgnoreCase)
        && double.TryParse(value, CultureInfo.InvariantCulture, out var n) && double.IsFinite(n) ? n : double.NaN;
    static bool? Indicator(string? html)
    {
        var name = Match(html, "<img\\b[^>]*\\bsrc\\s*=\\s*[\"'][^\"']*/(ok|no)\\.(?:png|gif)[\"']");
        return name == null ? null : name.Equals("ok", StringComparison.OrdinalIgnoreCase);
    }
    public static bool DetectLogin(string html) => Document(html).DocumentNode.SelectSingleNode("//input[translate(@type,'PASSWORD','password')='password']") != null ||
        Regex.IsMatch(html, "<form\\b[^>]*action\\s*=\\s*[\"'][^\"']*(?:login|prijava)", Flags);
    static void RequireAccess(string html) { if (DetectLogin(html)) throw new ControllerException("Izvorni sustav traži prijavu.", true); }
    static IEnumerable<HtmlNode> Nodes(HtmlNode node, string xpath) => node.SelectNodes(xpath)?.Cast<HtmlNode>() ?? [];
    static HtmlNode? Class(HtmlNode node, string name) => node.SelectSingleNode($"//*[contains(concat(' ',normalize-space(@class),' '),' {name} ')]");
    public static HeatingStatus Status(string html)
    {
        RequireAccess(html);
        if (!html.Contains("graf_kat_temp.php", StringComparison.OrdinalIgnoreCase) || !html.Contains("graf_puffer_temp.php", StringComparison.OrdinalIgnoreCase))
            throw new ControllerException("Izvorna stranica nema očekivanu strukturu grijanja. Podatci nisu zamijenjeni primjerima.");
        var root = Document(html).DocumentNode;
        var text = Text(html);
        var water = new Dictionary<string, (double?, double?, bool?)>();
        foreach (Match match in Regex.Matches(html, "Trenutno\\s+je\\s+(-?\\d+(?:[.,]\\d+)?)[\\s\\S]*?<span\\b[^>]*id\\s*=\\s*[\"'](ztk|ztp)[\"'][^>]*>([\\s\\S]*?)</span>", Flags))
            water[match.Groups[2].Value.ToLowerInvariant()] = (Number(match.Groups[1].Value), Number(Text(match.Groups[3].Value)), Indicator(match.Value));
        var zones = new List<Zone>();
        foreach (var (id, name, wid) in new[] { ("kat", "Kat", "ztk"), ("prizemlje", "Prizemlje", "ztp") })
        {
            water.TryGetValue(wid, out var w);
            zones.Add(new Zone(id, name, Number(Match(html, $"href=[\"']graf_{id}_temp\\.php[\"'][\\s\\S]*?<span\\b[^>]*>([^<]*)</span>")), w.Item1, w.Item2, w.Item3));
        }
        double? Buffer(string label)
        {
            var block = Class(root, "tekst-puffer");
            var para = block == null ? null : Nodes(block, ".//p").FirstOrDefault(p => Text(p.InnerHtml).Equals(label, StringComparison.OrdinalIgnoreCase));
            var next = para?.NextSibling;
            while (next != null && next.NodeType != HtmlNodeType.Element) next = next.NextSibling;
            return next?.Name == "h2" ? Number(Text(next.InnerHtml)) : null;
        }
        var furnace = Class(root, "tekst-pec");
        var temps = furnace == null ? [] : Nodes(furnace, ".//h2").Select(n => Number(Text(n.InnerHtml))).ToArray();
        string? Sync(string id) => Nodes(root, "//p").Select(p => Text(p.InnerHtml)).FirstOrDefault(p => p.Contains("sync", StringComparison.OrdinalIgnoreCase) && Regex.IsMatch(p, $"\\b{id}\\b", Flags));
        return new HeatingStatus(Match(text, @"(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})"), Match(text, @"vrijeme\s+sistema\s+(\d{1,2}:\d{2}(?::\d{2})?)"),
            Number(Match(text, @"Vanjska\s+temperatura\s+(-?\d+(?:[.,]\d+)?)")), Number(Match(text, @"Posto\s+(-?\d+(?:[.,]\d+)?)\s*%")), Number(Match(text, @"Resetirano\s*:\s*(\d+)")),
            zones, Buffer("vrh"), Buffer("sredina"), Buffer("dno"), temps.ElementAtOrDefault(0),
            Indicator(Match(html, "href\\s*=\\s*[\"']graf_sanitarna_temp\\.php[\"']([\\s\\S]*?)href\\s*=\\s*[\"']graf_pec_temp2\\.php[\"']")),
            temps.ElementAtOrDefault(1), Indicator(Class(root, "pump_pec")?.InnerHtml), Indicator(Class(root, "vent")?.InnerHtml), Sync("kat"), Sync("prizemlje"));
    }
    static HtmlNode Form(string html, string xpath)
    {
        RequireAccess(html);
        return Nodes(Document(html).DocumentNode, "//form").FirstOrDefault(f => f.SelectSingleNode(xpath) != null) ?? throw new ControllerException("Izvorna stranica nema očekivani obrazac. Spremanje je zaustavljeno.");
    }
    static string Attr(HtmlNode node, string key, string fallback = "") => WebUtility.HtmlDecode(node.GetAttributeValue(key, fallback));
    public static Schedule Schedule(string html, string id)
    {
        RequireZone(id);
        var form = Form(html, ".//input[@name='n23' and @type='range']");
        var fields = new List<TemperatureField>();
        for (var i = 0; i < 24; i++)
        {
            var inputs = Nodes(form, $".//input[@name='n{i}' and @type='range']").ToArray();
            if (inputs.Length != 1) throw new ControllerException("Raspored mora sadržavati jedinstven klizač za svaki od 24 sata.");
            var f = inputs[0];
            var field = new TemperatureField($"n{i}", SourceNumber(Attr(f, "value")), SourceNumber(Attr(f, "min")), SourceNumber(Attr(f, "max")), SourceNumber(Attr(f, "step", "1")));
            if (!double.IsFinite(field.Value) || !double.IsFinite(field.Min) || !double.IsFinite(field.Max) || !double.IsFinite(field.Step) || field.Step <= 0 || field.Min > field.Max)
                throw new ControllerException("Izvorni raspored ima neispravne granice.");
            fields.Add(field);
        }
        var hidden = new Dictionary<string, string>();
        foreach (var f in Nodes(form, ".//input[@type='hidden' and @name]"))
        {
            var name = Attr(f, "name");
            if (!hidden.TryAdd(name, Attr(f, "value")) || Regex.IsMatch(name, @"^n\d+$")) throw new ControllerException("Neočekivana skrivena polja u rasporedu.");
        }
        if (!hidden.TryGetValue("kat", out var zone) || zone != (id == "kat" ? "2" : "1")) throw new ControllerException("Izvorni obrazac ima neočekivanu oznaku zone.");
        var defaults = Nodes(Document(html).DocumentNode, "//*[contains(concat(' ',normalize-space(@class),' '),' default_temp ')]").Select(x => Number(Text(x.InnerHtml))).ToArray();
        return new Schedule(id, fields, defaults, Attr(form, "action"), Attr(form, "method", "GET").ToUpperInvariant(), hidden);
    }
    public static SettingsData Settings(string html)
    {
        var form = Form(html, ".//input[@name='n5']");
        var labels = Nodes(form, ".//label").Select(x => Text(x.InnerHtml)).ToArray();
        var fields = new List<SettingField>();
        for (var i = 0; i < 6; i++)
        {
            var inputs = Nodes(form, $".//input[@name='n{i}']").ToArray();
            var value = inputs.Length == 1 ? SourceNumber(Attr(inputs[0], "value")) : double.NaN;
            if (!double.IsFinite(value)) throw new ControllerException("Izvorne postavke imaju nepotpun ili neispravan obrazac.");
            fields.Add(new SettingField($"n{i}", labels.ElementAtOrDefault(i) ?? $"n{i}", value));
        }
        return new SettingsData(fields, Attr(form, "action"), Attr(form, "method", "GET").ToUpperInvariant());
    }
    public static void RequireZone(string id) { if (id != "kat" && id != "prizemlje") throw new ControllerException("Nepoznata zona."); }
    public static void ValidateSchedule(double[] values, Schedule schedule)
    {
        if (values.Length != 24) throw new ControllerException("Potrebne su točno 24 temperature.");
        for (var i = 0; i < 24; i++)
        {
            var v = values[i]; var f = schedule.Fields[i]; var tick = (v - f.Min) / f.Step;
            if (!double.IsFinite(v) || v < f.Min || v > f.Max || Math.Abs(tick - Math.Round(tick)) > 0.000001)
                throw new ControllerException($"Neispravna temperatura u {i}:00. Dopušteno {f.Min}–{f.Max} °C, korak {f.Step} °C.");
        }
    }
    public static void ValidateSettings(double[] values)
    {
        if (values.Length != 6 || values.Any(v => !double.IsFinite(v))) throw new ControllerException("Potrebno je svih šest brojčanih postavki.");
        if (values[5] != 0 && values[5] != 1) throw new ControllerException("Stanje klimatizacije mora biti 0 ili 1.");
    }
}
