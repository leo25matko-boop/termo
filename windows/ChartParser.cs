using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace TermoDom;

/// <summary>A bounded literal-data reader. JavaScript functions, DOM access and remote code are never executed.</summary>
public static class ChartParser
{
    const int MaxItems = 200_000;
    static readonly object Unavailable = new();
    static readonly string[] Palette = ["#E7AC68", "#6ECDBB", "#8CAEE8", "#B79ACD", "#D98C91"];
    public static readonly ChartMeta[] Catalog = [
        new("kat", "Kat", "graf_kat_temp.php", "°C"), new("prizemlje", "Prizemlje", "graf_prizemlje_temp.php", "°C"),
        new("buffer", "Puffer", "graf_puffer_temp.php", "°C"), new("sanitary", "Sanitarna voda", "graf_sanitarna_temp.php", "°C"),
        new("boiler", "Peć", "graf_pec_temp2.php", "°C"), new("outside", "Vanjska temperatura", "grafVANJ.php", "°C"),
        new("tuya", "Tuya senzori", "graf_tuya.php", "°C"), new("voltage", "Napon", "graf_napon2.php", "V"),
        new("tuya-1h", "Tuya senzori · 1 sat", "grafP1.php", "°C", 1), new("tuya-6h", "Tuya senzori · 6 sati", "grafp1-6.php", "°C", 6)];
    public static ChartMeta Find(string id) => Catalog.FirstOrDefault(x => x.Id == id) ?? throw new ControllerException("Nepoznat grafikon.");
    record Token(char Type, object Value);
    record Range(int Start, int End);
    static Exception Error(string message = "Podatci grafa sadrže nepodržan izraz.") => new ControllerException(message);
    static object? At(List<Token> t, int p) => p >= 0 && p < t.Count ? t[p].Value : null;
    static bool Is(List<Token> t, int p, string s) => At(t, p) is string v && v == s;
    static string? Close(string? open) => open switch { "[" => "]", "{" => "}", "(" => ")", _ => null };
    static int Closing(List<Token> t, int start)
    {
        var close = Close(At(t, start) as string) ?? throw Error();
        var stack = new Stack<string>(); stack.Push(close);
        for (var i = start + 1; i < t.Count; i++)
        {
            if (t[i].Type != 'p') continue;
            var value = (string)t[i].Value;
            if (Close(value) is string end) { if (stack.Count >= 100) throw Error(); stack.Push(end); }
            else if (value is "]" or "}" or ")")
            {
                if (stack.Count == 0 || stack.Pop() != value) throw Error("Neispravna struktura grafa.");
                if (stack.Count == 0) return i;
            }
        }
        throw Error("Nedovršen graf.");
    }
    static List<Token> Tokenize(string source)
    {
        var tokens = new List<Token>(); var i = 0;
        void Add(char type, object value) { if (tokens.Count >= MaxItems * 3) throw Error("Graf sadrži previše podataka."); tokens.Add(new(type, value)); }
        bool Identifier(char c) => c is '_' or '$' || char.IsAsciiLetter(c);
        while (i < source.Length)
        {
            var c = source[i];
            if (char.IsWhiteSpace(c)) { i++; continue; }
            if (c == '/' && i + 1 < source.Length && source[i + 1] == '/') { i += 2; while (i < source.Length && source[i] != '\n') i++; continue; }
            if (c == '/' && i + 1 < source.Length && source[i + 1] == '*') { var end = source.IndexOf("*/", i + 2, StringComparison.Ordinal); i = end < 0 ? source.Length : end + 2; continue; }
            if (c is '\'' or '"')
            {
                var quote = c; var value = new StringBuilder(); var closed = false; i++;
                while (i < source.Length)
                {
                    var next = source[i++];
                    if (next == quote) { closed = true; break; }
                    if (next != '\\') { value.Append(next); continue; }
                    if (i >= source.Length) break;
                    var esc = source[i++];
                    switch (esc)
                    {
                        case 'n': value.Append('\n'); break; case 'r': value.Append('\r'); break; case 't': value.Append('\t'); break;
                        case 'b': value.Append('\b'); break; case 'f': value.Append('\f'); break; case 'v': value.Append('\v'); break; case '0': value.Append('\0'); break;
                        case 'u': case 'x':
                            var count = esc == 'u' ? 4 : 2;
                            if (i + count > source.Length || !int.TryParse(source.AsSpan(i, count), NumberStyles.HexNumber, CultureInfo.InvariantCulture, out var n)) throw Error();
                            value.Append((char)n); i += count; break;
                        case '\r': if (i < source.Length && source[i] == '\n') i++; break;
                        case '\n': break;
                        default: value.Append(esc); break;
                    }
                }
                if (!closed) throw Error("Nedovršen tekst u grafu.");
                Add('s', value.ToString()); continue;
            }
            if (c == '`')
            {
                var start = i++;
                while (i < source.Length) { if (source[i] == '\\') { i = Math.Min(i + 2, source.Length); continue; } if (source[i++] == '`') break; }
                Add('u', source[start..i]); continue;
            }
            if (char.IsAsciiDigit(c) || c == '.' && i + 1 < source.Length && char.IsAsciiDigit(source[i + 1]))
            {
                var m = Regex.Match(source[i..], @"^(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?", RegexOptions.IgnoreCase);
                if (m.Success) { Add('n', double.Parse(m.Value, CultureInfo.InvariantCulture)); i += m.Length; continue; }
            }
            if (Identifier(c)) { var start = i++; while (i < source.Length && (Identifier(source[i]) || char.IsAsciiDigit(source[i]))) i++; Add('i', source[start..i]); continue; }
            Add('p', c.ToString()); i++;
        }
        return tokens;
    }
    sealed class Expression(List<Token> tokens, int start, Dictionary<string, object?> bindings)
    {
        public int Position = start;
        int steps, depth;
        void Expect(string s) { if (!Is(tokens, Position++, s)) throw Error(); }
        static double Numeric(object? value) => value is double n && double.IsFinite(n) ? n : throw Error("Neispravan broj u grafu.");
        static int Precedence(object? op) => op is string s ? s switch { "+" or "-" => 1, "*" or "/" or "%" => 2, _ => -1 } : -1;
        object? Primary()
        {
            if (++steps > MaxItems * 2 || ++depth > 100) throw Error("Graf sadrži previše podataka ili predubok izraz.");
            try { return PrimaryValue(); } finally { depth--; }
        }
        object? PrimaryValue()
        {
            if (Position >= tokens.Count) throw Error();
            var token = tokens[Position++]; var value = token.Value;
            if (token.Type is 'n' or 's') return value;
            if (value is "+" or "-") { var n = Numeric(Primary()); return value.Equals("-") ? -n : n; }
            if (value.Equals("(")) { var result = Arithmetic(0); Expect(")"); return result; }
            if (value.Equals("["))
            {
                var result = new List<object?>();
                while (!Is(tokens, Position, "]"))
                {
                    if (result.Count >= MaxItems) throw Error(); result.Add(Arithmetic(0));
                    if (!Is(tokens, Position, ",")) break;
                    Position++; if (Is(tokens, Position, "]")) break;
                }
                Expect("]"); return result;
            }
            if (value.Equals("true")) return true;
            if (value.Equals("false")) return false;
            if (value.Equals("null")) return null;
            if (value.Equals("new") && Is(tokens, Position, "Array")) Position++;
            else if (!value.Equals("Array"))
            {
                if (token.Type != 'i' || !bindings.TryGetValue((string)value, out var bound)) throw Error();
                if (Is(tokens, Position, ".") && Is(tokens, Position + 1, "length") && bound is List<object?> list) { Position += 2; return (double)list.Count; }
                return bound;
            }
            Expect("("); var count = Numeric(Arithmetic(0)); Expect(")"); Expect("."); Expect("fill"); Expect("("); var fill = Arithmetic(0); Expect(")");
            if (count != Math.Floor(count) || count < 0 || count > MaxItems || fill is List<object?>) throw Error();
            return Enumerable.Repeat(fill, (int)count).ToList();
        }
        public object? Arithmetic(int min)
        {
            var left = Primary();
            while (Precedence(At(tokens, Position)) >= min)
            {
                var op = (string)tokens[Position++].Value;
                var right = Arithmetic(Precedence(op) + 1);
                var a = Numeric(left); var b = Numeric(right);
                left = op switch { "+" => a + b, "-" => a - b, "*" => a * b, "/" => a / b, _ => a % b };
                Numeric(left);
            }
            return left;
        }
    }
    static Dictionary<string, Range> Properties(List<Token> t, int start)
    {
        var result = new Dictionary<string, Range>(); var end = Closing(t, start); var at = start + 1;
        while (at < end)
        {
            var key = t[at++]; if (key.Value.Equals(",")) continue;
            if (key.Type is not ('i' or 's') || !Is(t, at++, ":")) break;
            var valueStart = at;
            while (at < end && !Is(t, at, ","))
            {
                if (t[at].Type == 'p' && Close(At(t, at) as string) != null) at = Closing(t, at) + 1;
                else at++;
            }
            result[(string)key.Value] = new(valueStart, at); if (Is(t, at, ",")) at++;
        }
        return result;
    }
    static object? Property(List<Token> t, Dictionary<string, Range> props, string name, Dictionary<string, object?> bindings)
    {
        if (!props.TryGetValue(name, out var range)) return Unavailable;
        try { var expression = new Expression(t, range.Start, bindings); var value = expression.Arithmetic(0); return expression.Position == range.End ? value : Unavailable; }
        catch { return Unavailable; }
    }
    public static ChartData Parse(string html, string id)
    {
        var meta = Find(id);
        if (html.Length > 4_000_000) throw Error("Prevelik odgovor grafa.");
        if (HeatingParser.DetectLogin(html)) throw new ControllerException("Izvorni sustav traži prijavu.", true);
        var doc = HeatingParser.Document(html);
        var scripts = doc.DocumentNode.SelectNodes("//script")?.Select(n => n.InnerHtml) ?? [];
        var tokens = Tokenize(string.Join('\n', scripts)); var bindings = new Dictionary<string, object?>();
        for (var at = 0; at + 3 < tokens.Count; at++)
        {
            if (At(tokens, at) is not ("var" or "let" or "const") || tokens[at + 1].Type != 'i' || !Is(tokens, at + 2, "=")) continue;
            try { var expr = new Expression(tokens, at + 3, bindings); var value = expr.Arithmetic(0); if (At(tokens, expr.Position) is ";" or ",") bindings[(string)tokens[at + 1].Value] = value; }
            catch { /* DOM references and functions intentionally remain unparsed. */ }
        }
        Dictionary<string, Range>? dataProps = null;
        for (var at = 0; at + 3 < tokens.Count; at++)
            if (Is(tokens, at, "data") && Is(tokens, at + 1, ":") && Is(tokens, at + 2, "{"))
            { var candidate = Properties(tokens, at + 2); if (candidate.ContainsKey("labels") && candidate.ContainsKey("datasets")) { dataProps = candidate; break; } }
        if (dataProps == null) throw Error("Izvor nije vratio podatke grafa.");
        if (Property(tokens, dataProps, "labels", bindings) is not List<object?> labels || labels.Any(v => v is not (string or double))) throw Error("Neispravne oznake grafa.");
        var range = dataProps["datasets"];
        if (!Is(tokens, range.Start, "[")) throw Error();
        var series = new List<ChartSeries>(); var pos = range.Start + 1;
        while (pos < range.End - 1)
        {
            if (Is(tokens, pos, ",")) { pos++; continue; }
            if (!Is(tokens, pos, "{")) throw Error();
            var props = Properties(tokens, pos);
            if (Property(tokens, props, "label", bindings) is not string label || Property(tokens, props, "data", bindings) is not List<object?> rawData) throw Error();
            var values = new List<double?>();
            foreach (var value in rawData)
            {
                if (value == null) values.Add(null);
                else if (value is double number && double.IsFinite(number)) values.Add(number);
                else if (value is string text && Regex.IsMatch(text, @"^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$", RegexOptions.IgnoreCase) && double.TryParse(text, CultureInfo.InvariantCulture, out var parsed) && double.IsFinite(parsed)) values.Add(parsed);
                else throw Error();
            }
            var color = Property(tokens, props, "borderColor", bindings) as string ?? Palette[series.Count % Palette.Length];
            var stepped = Property(tokens, props, props.ContainsKey("steppedLine") ? "steppedLine" : "stepped", bindings);
            series.Add(new(label, values, color, stepped is true or "before" or "after" or "middle", stepped as string ?? "before"));
            pos = Closing(tokens, pos) + 1;
        }
        if (series.Count == 0) throw Error();
        return new ChartData(meta, labels.Select(x => Convert.ToString(x, CultureInfo.InvariantCulture)!).ToArray(), series);
    }
}
