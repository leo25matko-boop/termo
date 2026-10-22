package se.automatiziraj.termodom;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the controller's HTML as data. No remote script is ever executed. */
public final class HeatingParser {
    private HeatingParser() { }
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:[.,]\\d+)?");
    private static final Pattern SOURCE_NUMBER = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:e[+-]?\\d+)?", Pattern.CASE_INSENSITIVE);

    public static String plainText(String html) {
        return Jsoup.parse(html == null ? "" : html).text().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    public static boolean detectLogin(String html) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        for (Element input : doc.select("input")) {
            if ("password".equalsIgnoreCase(input.attr("type"))) return true;
        }
        for (Element form : doc.select("form[action]")) {
            String action = form.attr("action").toLowerCase(Locale.ROOT);
            if (action.contains("login") || action.contains("prijava")) return true;
        }
        return false;
    }

    private static void requireAccess(String html) throws Exception {
        if (detectLogin(html)) throw new HeatingRepository.AppException("Izvorni sustav traži prijavu.", 401,
                new JSONObject().put("requiresLogin", true));
    }

    private static String match(String source, String regex) {
        if (source == null) return null;
        Matcher matcher = Pattern.compile(regex, FLAGS).matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Object number(String value) {
        if (value == null) return JSONObject.NULL;
        Matcher matcher = NUMBER.matcher(value);
        if (!matcher.find()) return JSONObject.NULL;
        try {
            double parsed = Double.parseDouble(matcher.group().replace(',', '.'));
            return Double.isFinite(parsed) ? parsed : JSONObject.NULL;
        } catch (NumberFormatException ignored) { return JSONObject.NULL; }
    }

    private static Object nullable(Object value) { return value == null ? JSONObject.NULL : value; }

    private static Object indicator(String html) {
        String name = match(html, "<img\\b[^>]*\\bsrc\\s*=\\s*[\"'][^\"']*/(ok|no)\\.(?:png|gif)[\"']");
        return name == null ? JSONObject.NULL : "ok".equalsIgnoreCase(name);
    }

    public static JSONObject parseStatus(String html) throws Exception {
        requireAccess(html);
        Document doc = Jsoup.parse(html);
        String body = doc.body().html();
        String structure = body.toLowerCase(Locale.ROOT);
        if (!structure.contains("graf_kat_temp.php") || !structure.contains("graf_puffer_temp.php")) {
            throw new HeatingRepository.AppException("Izvorna stranica nema očekivanu strukturu sustava grijanja. Podatci nisu zamijenjeni primjerima.");
        }
        String text = plainText(body);
        JSONObject sync = new JSONObject().put("kat", JSONObject.NULL).put("prizemlje", JSONObject.NULL);
        for (Element para : doc.body().select("p")) {
            String value = para.text();
            if (!value.toLowerCase(Locale.ROOT).contains("sync")) continue;
            for (String id : new String[]{"kat", "prizemlje"}) {
                if (sync.isNull(id) && Pattern.compile("\\b" + id + "\\b", Pattern.CASE_INSENSITIVE).matcher(value).find()) sync.put(id, value);
            }
        }
        JSONObject water = new JSONObject();
        Matcher waterParts = Pattern.compile("Trenutno\\s+je\\s+(-?\\d+(?:[.,]\\d+)?)[\\s\\S]*?<span\\b[^>]*id\\s*=\\s*[\"'](ztk|ztp)[\"'][^>]*>([\\s\\S]*?)</span>", FLAGS).matcher(body);
        while (waterParts.find()) {
            water.put(waterParts.group(2).toLowerCase(Locale.ROOT), new JSONObject()
                    .put("temperature", number(waterParts.group(1)))
                    .put("planned", number(plainText(waterParts.group(3))))
                    .put("running", indicator(waterParts.group())));
        }
        JSONArray zones = new JSONArray();
        String[] ids = {"kat", "prizemlje"}, names = {"Kat", "Prizemlje"}, waterIds = {"ztk", "ztp"};
        for (int i = 0; i < ids.length; i++) {
            JSONObject w = water.optJSONObject(waterIds[i]);
            String temperature = match(body, "href=[\"']graf_" + ids[i] + "_temp\\.php[\"'][\\s\\S]*?<span\\b[^>]*>([^<]*)</span>");
            zones.put(new JSONObject().put("id", ids[i]).put("name", names[i])
                    .put("temperature", number(temperature))
                    .put("waterTemperature", w == null ? JSONObject.NULL : w.get("temperature"))
                    .put("plannedWaterTemperature", w == null ? JSONObject.NULL : w.get("planned"))
                    .put("pumpRunning", w == null ? JSONObject.NULL : w.get("running")));
        }
        JSONObject buffer = new JSONObject();
        Element bufferBlock = doc.selectFirst(".tekst-puffer");
        for (String[] item : new String[][]{{"top", "vrh"}, {"middle", "sredina"}, {"bottom", "dno"}}) {
            Object value = JSONObject.NULL;
            if (bufferBlock != null) {
                for (Element p : bufferBlock.select("p")) {
                    if (item[1].equalsIgnoreCase(p.text().trim())) {
                        Element next = p.nextElementSibling();
                        if (next != null && "h2".equals(next.tagName())) value = number(next.text());
                        break;
                    }
                }
            }
            buffer.put(item[0], value);
        }
        Elements furnaceTemps = doc.select(".tekst-pec h2");
        Element pumpBlock = doc.selectFirst(".pump_pec"), fanBlock = doc.selectFirst(".vent");
        String sanitaryBlock = match(body, "href\\s*=\\s*[\"']graf_sanitarna_temp\\.php[\"']([\\s\\S]*?)href\\s*=\\s*[\"']graf_pec_temp2\\.php[\"']");
        return new JSONObject()
                .put("serverTime", nullable(match(text, "(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})")))
                .put("systemTime", nullable(match(text, "vrijeme\\s+sistema\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?)")))
                .put("sync", sync).put("resets", number(match(text, "Resetirano\\s*:\\s*(\\d+)")))
                .put("outside", number(match(text, "Vanjska\\s+temperatura\\s+(-?\\d+(?:[.,]\\d+)?)")))
                .put("percentage", number(match(text, "Posto\\s+(-?\\d+(?:[.,]\\d+)?)\\s*%")))
                .put("zones", zones).put("buffer", buffer)
                .put("sanitary", new JSONObject().put("temperature", furnaceTemps.isEmpty() ? JSONObject.NULL : number(furnaceTemps.get(0).text())).put("pumpRunning", indicator(sanitaryBlock)))
                .put("boiler", new JSONObject().put("temperature", furnaceTemps.size() < 2 ? JSONObject.NULL : number(furnaceTemps.get(1).text()))
                        .put("pumpRunning", indicator(pumpBlock == null ? null : pumpBlock.html()))
                        .put("fanRunning", indicator(fanBlock == null ? null : fanBlock.html())));
    }

    private static double sourceNumber(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!SOURCE_NUMBER.matcher(trimmed).matches()) return Double.NaN;
        try { return Double.parseDouble(trimmed); } catch (NumberFormatException ignored) { return Double.NaN; }
    }

    private static Element formWith(Document doc, String selector) throws HeatingRepository.AppException {
        for (Element form : doc.select("form")) if (!form.select(selector).isEmpty()) return form;
        throw new HeatingRepository.AppException("Izvorna stranica nema očekivani obrazac. Spremanje je zaustavljeno.");
    }

    public static JSONObject parseSchedule(String html, String id) throws Exception {
        requireZone(id);
        requireAccess(html);
        Document doc = Jsoup.parse(html);
        Element form = formWith(doc, "input[type=range][name=n23]");
        JSONArray fields = new JSONArray();
        for (int n = 0; n < 24; n++) {
            Elements matches = form.select("input[type=range][name=n" + n + "]");
            if (matches.size() != 1) throw new HeatingRepository.AppException("U izvornom rasporedu nedostaje jedan od 24 sata ili je polje ponovljeno.");
            Element input = matches.first();
            double value = sourceNumber(input.attr("value")), min = sourceNumber(input.attr("min")), max = sourceNumber(input.attr("max"));
            double step = input.hasAttr("step") ? sourceNumber(input.attr("step")) : 1;
            if (!Double.isFinite(value) || !Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(step) || step <= 0 || min > max) {
                throw new HeatingRepository.AppException("Izvorni raspored ima neispravne granice.");
            }
            fields.put(new JSONObject().put("name", "n" + n).put("value", value).put("min", min).put("max", max).put("step", step));
        }
        JSONObject hidden = new JSONObject();
        for (Element input : form.select("input[type=hidden][name]")) {
            String name = input.attr("name");
            if (name.isEmpty()) continue;
            if (hidden.has(name) || name.matches("n\\d+")) throw new HeatingRepository.AppException("Izvorni obrazac ima neočekivana skrivena polja.");
            hidden.put(name, input.attr("value"));
        }
        if (!("kat".equals(id) ? "2" : "1").equals(hidden.optString("kat"))) throw new HeatingRepository.AppException("Izvorni obrazac ima neočekivanu oznaku zone.");
        JSONArray defaults = new JSONArray();
        for (Element element : doc.select(".default_temp")) defaults.put(number(element.text()));
        return new JSONObject().put("id", id).put("fields", fields).put("defaults", defaults)
                .put("action", form.attr("action")).put("hidden", hidden)
                .put("method", form.attr("method").isEmpty() ? "GET" : form.attr("method").toUpperCase(Locale.ROOT));
    }

    public static JSONObject parseSettings(String html) throws Exception {
        requireAccess(html);
        Element form = formWith(Jsoup.parse(html), "input[name=n5]");
        Elements labels = form.select("label");
        JSONArray fields = new JSONArray();
        for (int n = 0; n < 6; n++) {
            Elements matches = form.select("input[name=n" + n + "]");
            double value = matches.size() == 1 ? sourceNumber(matches.first().attr("value")) : Double.NaN;
            if (!Double.isFinite(value)) throw new HeatingRepository.AppException("Izvorne postavke imaju nepotpun ili neispravan obrazac.");
            fields.put(new JSONObject().put("name", "n" + n).put("label", labels.size() > n ? labels.get(n).text() : "n" + n).put("value", value));
        }
        return new JSONObject().put("fields", fields).put("action", form.attr("action"))
                .put("method", form.attr("method").isEmpty() ? "GET" : form.attr("method").toUpperCase(Locale.ROOT));
    }

    public static void requireZone(String id) throws HeatingRepository.AppException {
        if (!"kat".equals(id) && !"prizemlje".equals(id)) throw new HeatingRepository.AppException("Nepoznata zona.", 404);
    }

    public static void validateSchedule(double[] values, JSONObject schedule) throws Exception {
        if (values == null || values.length != 24) throw new HeatingRepository.AppException("Raspored mora sadržavati točno 24 temperature.", 400);
        JSONArray fields = schedule.getJSONArray("fields");
        for (int i = 0; i < values.length; i++) {
            JSONObject field = fields.getJSONObject(i);
            double value = values[i], min = field.getDouble("min"), max = field.getDouble("max"), step = field.getDouble("step");
            double ticks = (value - min) / step;
            if (!Double.isFinite(value) || value < min || value > max || Math.abs(ticks - Math.rint(ticks)) > 0.000001) {
                throw new HeatingRepository.AppException("Neispravna temperatura za " + i + ":00. Dopušteno je " + min + "–" + max + " °C, korak " + step + " °C.", 400);
            }
        }
    }

    public static void validateSettings(JSONObject values) throws Exception {
        if (values == null || values.length() != 6) throw new HeatingRepository.AppException("Potrebno je svih šest postavki.", 400);
        for (int n = 0; n < 6; n++) {
            Object value = values.opt("n" + n);
            if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue())) throw new HeatingRepository.AppException("Postavka n" + n + " mora biti broj.", 400);
        }
        double state = values.getDouble("n5");
        if (state != 0 && state != 1) throw new HeatingRepository.AppException("Stanje klimatizacije mora biti 0 ili 1.", 400);
    }
}
