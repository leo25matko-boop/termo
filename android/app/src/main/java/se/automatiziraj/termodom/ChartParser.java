package se.automatiziraj.termodom;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads literal chart data. Source JavaScript is text and is never executed. */
public final class ChartParser {
    private ChartParser() { }

    private static final int MAX_ITEMS = 200_000;
    private static final int MAX_HTML = 4_000_000;
    private static final int MAX_DEPTH = 100;
    private static final Object UNAVAILABLE = new Object();
    private static final Pattern NUMBER = Pattern.compile("(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");
    private static final Pattern NUMERIC_TEXT = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");
    private static final String[] PALETTE = {"#ef694f", "#46b29c", "#7f96ec", "#b488dc", "#dfad59"};
    private static final String[][] CHARTS = {
        {"kat", "Kat", "graf_kat_temp.php", "°C", "24h"},
        {"prizemlje", "Prizemlje", "graf_prizemlje_temp.php", "°C", "24h"},
        {"buffer", "Puffer", "graf_puffer_temp.php", "°C", "24h"},
        {"sanitary", "Sanitarna voda", "graf_sanitarna_temp.php", "°C", "24h"},
        {"boiler", "Peć", "graf_pec_temp2.php", "°C", "24h"},
        {"outside", "Vanjska temperatura", "grafVANJ.php", "°C", "24h"},
        {"tuya", "Tuya senzori", "graf_tuya.php", "°C", "24h"},
        {"voltage", "Napon", "graf_napon2.php", "V", "24h"},
        {"tuya-1h", "Tuya senzori · 1 sat", "grafP1.php", "°C", "1h"},
        {"tuya-6h", "Tuya senzori · 6 sati", "grafp1-6.php", "°C", "6h"}
    };

    public static JSONArray catalog() {
        List<Map<String, Object>> charts = new ArrayList<>();
        for (String[] chart : CHARTS) charts.add(metadata(chart));
        return new JSONArray(charts);
    }

    public static String pathFor(String id) { return findChart(id)[2]; }

    private static String[] findChart(String id) {
        for (String[] chart : CHARTS) if (chart[0].equals(id)) return chart;
        throw error("Nepoznat graf.");
    }

    private static Map<String, Object> metadata(String[] chart) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", chart[0]);
        result.put("title", chart[1]);
        result.put("path", chart[2]);
        result.put("unit", chart[3]);
        result.put("period", chart[4]);
        return result;
    }

    public static JSONObject parse(String html, String id) {
        String[] meta = findChart(id);
        if (html == null || html.length() > MAX_HTML) throw error("Neispravan odgovor grafa.");
        StringBuilder script = new StringBuilder();
        for (Element element : Jsoup.parse(html).select("script")) {
            script.append(element.data()).append('\n');
        }
        List<Token> tokens = tokenize(script.toString());
        Map<String, Object> bindings = dataBindings(tokens);
        Map<String, Range> properties = null;
        for (int at = 0; at + 3 < tokens.size(); at++) {
            if (!is(tokens, at, "data") || !is(tokens, at + 1, ":") || !is(tokens, at + 2, "{")) continue;
            Map<String, Range> candidate = objectProperties(tokens, at + 2);
            if (candidate.containsKey("labels") && candidate.containsKey("datasets")) {
                properties = candidate;
                break;
            }
        }
        if (properties == null) throw error("Izvor nije vratio podatke grafa.");
        Object rawLabels = propertyValue(tokens, properties.get("labels"), bindings);
        if (!(rawLabels instanceof List)) throw error("Neispravne oznake grafa.");
        List<?> labels = (List<?>) rawLabels;
        for (Object label : labels) {
            if (!(label instanceof String) && !(label instanceof Number)) throw error("Neispravne oznake grafa.");
        }
        Range datasetRange = properties.get("datasets");
        if (!is(tokens, datasetRange.start, "[")) throw error("Neispravni nizovi grafa.");
        List<Map<String, Object>> datasets = new ArrayList<>();
        int at = datasetRange.start + 1;
        while (at < datasetRange.end - 1) {
            if (is(tokens, at, ",")) { at++; continue; }
            if (!is(tokens, at, "{")) throw error("Nepodržan niz grafa.");
            Map<String, Range> props = objectProperties(tokens, at);
            Object label = propertyValue(tokens, props.get("label"), bindings);
            Object rawData = propertyValue(tokens, props.get("data"), bindings);
            Object color = propertyValue(tokens, props.get("borderColor"), bindings);
            Range steppedRange = props.containsKey("steppedLine") ? props.get("steppedLine") : props.get("stepped");
            Object stepped = propertyValue(tokens, steppedRange, bindings);
            if (!(label instanceof String) || !(rawData instanceof List)) {
                throw error("Podaci grafa sadrže nepodržan izraz.");
            }
            List<Object> data = new ArrayList<>();
            for (Object value : (List<?>) rawData) {
                if (value == null || value == JSONObject.NULL) {
                    data.add(JSONObject.NULL);
                } else if (value instanceof Number && Double.isFinite(((Number) value).doubleValue())) {
                    data.add(((Number) value).doubleValue());
                } else if (value instanceof String && NUMERIC_TEXT.matcher((String) value).matches()) {
                    double number = Double.parseDouble((String) value);
                    if (!Double.isFinite(number)) throw error("Neispravan broj u grafu.");
                    data.add(number);
                } else throw error("Podaci grafa sadrže nepodržan izraz.");
            }
            Map<String, Object> dataset = new LinkedHashMap<>();
            dataset.put("label", label);
            dataset.put("data", data);
            dataset.put("color", color instanceof String ? color : PALETTE[datasets.size() % PALETTE.length]);
            boolean isStepped = Boolean.TRUE.equals(stepped) || Arrays.asList("before", "after", "middle").contains(stepped);
            dataset.put("stepped", isStepped);
            dataset.put("steppedMode", stepped instanceof String && isStepped ? stepped : "before");
            datasets.add(dataset);
            at = closePosition(tokens, at) + 1;
        }
        if (datasets.isEmpty()) throw error("Izvor nije vratio nizove grafa.");
        Map<String, Object> result = metadata(meta);
        result.remove("path");
        result.put("labels", labels);
        result.put("datasets", datasets);
        if ("voltage".equals(id)) {
            // The original page's actual limit is 251, despite its 250 V label.
            result.put("alarmLimit", 251);
            result.put("spikeThreshold", 10);
        }
        return new JSONObject(result);
    }

    private static IllegalArgumentException error(String message) { return new IllegalArgumentException(message); }

    private static final class Token {
        final char type;
        final Object value;
        Token(char type, Object value) { this.type = type; this.value = value; }
    }
    private static final class Range {
        final int start, end;
        Range(int start, int end) { this.start = start; this.end = end; }
    }
    private static Object valueAt(List<Token> tokens, int at) { return at >= 0 && at < tokens.size() ? tokens.get(at).value : null; }
    private static boolean is(List<Token> tokens, int at, String value) { return value.equals(valueAt(tokens, at)); }

    private static void add(List<Token> tokens, char type, Object value) {
        if (tokens.size() >= MAX_ITEMS * 3) throw error("Graf sadrži previše podataka.");
        tokens.add(new Token(type, value));
    }

    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i += 2;
                while (i < source.length() && source.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end == -1 ? source.length() : end + 2;
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                i++;
                while (i < source.length()) {
                    char next = source.charAt(i++);
                    if (next == quote) { closed = true; break; }
                    if (next != '\\') { value.append(next); continue; }
                    if (i >= source.length()) break;
                    char escaped = source.charAt(i++);
                    switch (escaped) {
                        case 'n': value.append('\n'); break;
                        case 'r': value.append('\r'); break;
                        case 't': value.append('\t'); break;
                        case 'b': value.append('\b'); break;
                        case 'f': value.append('\f'); break;
                        case 'v': value.append('\u000b'); break;
                        case '0': value.append('\0'); break;
                        case 'u':
                        case 'x': {
                            int count = escaped == 'u' ? 4 : 2;
                            if (i + count > source.length()) throw error("Neispravan tekst u grafu.");
                            String digits = source.substring(i, i + count);
                            if (!digits.matches("[0-9a-fA-F]{" + count + "}")) throw error("Neispravan tekst u grafu.");
                            value.append((char) Integer.parseInt(digits, 16));
                            i += count;
                            break;
                        }
                        case '\r': if (i < source.length() && source.charAt(i) == '\n') i++; break;
                        case '\n': break;
                        default: value.append(escaped);
                    }
                }
                if (!closed) throw error("Nedovršen tekst u grafu.");
                add(tokens, 's', value.toString());
                continue;
            }
            if (c == '`') {
                int start = i++;
                while (i < source.length()) {
                    if (source.charAt(i) == '\\') { i = Math.min(i + 2, source.length()); continue; }
                    if (source.charAt(i++) == '`') break;
                }
                add(tokens, 'u', source.substring(start, i));
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < source.length() && Character.isDigit(source.charAt(i + 1)))) {
                Matcher number = NUMBER.matcher(source);
                number.region(i, source.length());
                if (number.lookingAt()) {
                    add(tokens, 'n', Double.parseDouble(number.group()));
                    i = number.end();
                    continue;
                }
            }
            if (isIdentifierStart(c)) {
                int start = i++;
                while (i < source.length() && (isIdentifierStart(source.charAt(i)) || Character.isDigit(source.charAt(i)))) i++;
                add(tokens, 'i', source.substring(start, i));
                continue;
            }
            add(tokens, 'p', String.valueOf(c));
            i++;
        }
        return tokens;
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || c == '$' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static final class Expression {
        final List<Token> tokens;
        final Map<String, Object> bindings;
        int at, steps, depth;
        Expression(List<Token> tokens, int at, Map<String, Object> bindings) {
            this.tokens = tokens; this.at = at; this.bindings = bindings;
        }
        void expect(String value) {
            if (!is(tokens, at++, value)) throw error("Nepodržan izraz u grafu.");
        }
        double number(Object value) {
            if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue())) throw error("Neispravan broj u grafu.");
            return ((Number) value).doubleValue();
        }
        Object primary() {
            if (++steps > MAX_ITEMS * 2) throw error("Graf sadrži previše podataka.");
            if (++depth > MAX_DEPTH) throw error("Graf sadrži predubok izraz.");
            try { return primaryValue(); }
            finally { depth--; }
        }
        Object primaryValue() {
            if (at >= tokens.size()) throw error("Nedovršen izraz u grafu.");
            Token token = tokens.get(at++);
            if (token.type == 'n' || token.type == 's') return token.value;
            if ("+".equals(token.value) || "-".equals(token.value)) {
                double value = number(primary());
                return "-".equals(token.value) ? -value : value;
            }
            if ("(".equals(token.value)) {
                Object result = arithmetic(0);
                expect(")");
                return result;
            }
            if ("[".equals(token.value)) {
                List<Object> result = new ArrayList<>();
                while (!is(tokens, at, "]")) {
                    if (result.size() >= MAX_ITEMS) throw error("Graf sadrži previše podataka.");
                    result.add(arithmetic(0));
                    if (!is(tokens, at, ",")) break;
                    at++;
                    if (is(tokens, at, "]")) break;
                }
                expect("]");
                return result;
            }
            if ("true".equals(token.value)) return true;
            if ("false".equals(token.value)) return false;
            if ("null".equals(token.value)) return JSONObject.NULL;
            if ("new".equals(token.value) && is(tokens, at, "Array")) at++;
            else if (!"Array".equals(token.value)) {
                if (token.type != 'i' || !bindings.containsKey(token.value)) throw error("Nepoznata varijabla u grafu.");
                Object result = bindings.get(token.value);
                if (is(tokens, at, ".") && is(tokens, at + 1, "length") && result instanceof List) {
                    at += 2;
                    result = (double) ((List<?>) result).size();
                }
                return result;
            }
            expect("(");
            double count = number(arithmetic(0));
            expect(")"); expect("."); expect("fill"); expect("(");
            Object fill = arithmetic(0);
            expect(")");
            if (count != Math.floor(count) || count < 0 || count > MAX_ITEMS ||
                !(fill == JSONObject.NULL || fill instanceof String || fill instanceof Number || fill instanceof Boolean)) {
                throw error("Neispravan niz u grafu.");
            }
            return new ArrayList<>(Collections.nCopies((int) count, fill));
        }
        Object arithmetic(int minimum) {
            Object left = primary();
            while (precedence(valueAt(tokens, at)) >= minimum) {
                Object op = valueAt(tokens, at);
                int precedence = precedence(op);
                if (precedence < 0) break;
                at++;
                Object right = arithmetic(precedence + 1);
                double a = number(left), b = number(right);
                switch ((String) op) {
                    case "+": left = a + b; break;
                    case "-": left = a - b; break;
                    case "*": left = a * b; break;
                    case "/": left = a / b; break;
                    default: left = a % b;
                }
                number(left);
            }
            return left;
        }
    }

    private static int precedence(Object op) {
        if ("+".equals(op) || "-".equals(op)) return 1;
        if ("*".equals(op) || "/".equals(op) || "%".equals(op)) return 2;
        return -1;
    }

    private static String closing(String opening) {
        switch (opening) {
            case "[": return "]";
            case "{": return "}";
            case "(": return ")";
            default: return null;
        }
    }

    private static int closePosition(List<Token> tokens, int start) {
        Object opening = valueAt(tokens, start);
        String end = opening instanceof String ? closing((String) opening) : null;
        if (end == null) throw error("Neispravna struktura grafa.");
        Deque<String> stack = new ArrayDeque<>();
        stack.push(end);
        for (int i = start + 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type != 'p') continue;
            String value = (String) token.value;
            String close = closing(value);
            if (close != null) {
                if (stack.size() >= MAX_DEPTH) throw error("Graf sadrži preduboku strukturu.");
                stack.push(close);
            } else if ("]".equals(value) || "}".equals(value) || ")".equals(value)) {
                if (stack.isEmpty() || !stack.pop().equals(value)) throw error("Neispravna struktura grafa.");
                if (stack.isEmpty()) return i;
            }
        }
        throw error("Nedovršen graf.");
    }

    private static Map<String, Range> objectProperties(List<Token> tokens, int start) {
        Map<String, Range> result = new LinkedHashMap<>();
        int end = closePosition(tokens, start);
        int at = start + 1;
        while (at < end) {
            Token key = tokens.get(at++);
            if (",".equals(key.value)) continue;
            if ((key.type != 'i' && key.type != 's') || !is(tokens, at++, ":")) break;
            int valueStart = at;
            while (at < end && !is(tokens, at, ",")) {
                Token token = tokens.get(at);
                if (token.type == 'p' && token.value instanceof String && closing((String) token.value) != null) {
                    at = closePosition(tokens, at) + 1;
                } else at++;
            }
            result.put((String) key.value, new Range(valueStart, at));
            if (is(tokens, at, ",")) at++;
        }
        return result;
    }

    private static Object propertyValue(List<Token> tokens, Range property, Map<String, Object> bindings) {
        if (property == null) return UNAVAILABLE;
        try {
            Expression expression = new Expression(tokens, property.start, bindings);
            Object result = expression.arithmetic(0);
            return expression.at == property.end ? result : UNAVAILABLE;
        } catch (IllegalArgumentException ignored) { return UNAVAILABLE; }
    }

    private static Map<String, Object> dataBindings(List<Token> tokens) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int at = 0; at + 3 < tokens.size(); at++) {
            if (!(is(tokens, at, "var") || is(tokens, at, "let") || is(tokens, at, "const")) ||
                tokens.get(at + 1).type != 'i' || !is(tokens, at + 2, "=")) continue;
            try {
                Expression expression = new Expression(tokens, at + 3, result);
                Object value = expression.arithmetic(0);
                if (is(tokens, expression.at, ";") || is(tokens, expression.at, ",")) {
                    result.put((String) valueAt(tokens, at + 1), value);
                }
            } catch (IllegalArgumentException ignored) {
                // DOM access, functions, templates, calls and arbitrary code are not data.
            }
        }
        return result;
    }
}
