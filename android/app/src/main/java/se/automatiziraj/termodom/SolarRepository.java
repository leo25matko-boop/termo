package se.automatiziraj.termodom;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.parser.Parser;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Read-only adapter for a user-supplied Huawei FusionSolar shared kiosk link.
 * The link is kept on the user's device. No login, API key, or inverter commands are used.
 */
public class SolarRepository {
    private static final int LIMIT = 1_000_000;
    public static final String DEFAULT_HOST = "uni005eu5.fusionsolar.huawei.com";
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build();

    public static boolean validUrl(String value) {
        try { endpoint(value); return true; } catch (Exception e) { return false; }
    }

    private static String endpoint(String value) throws Exception {
        if (value == null || value.length() > 2048) throw new IllegalArgumentException();
        URI uri = new URI(value.trim());
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !host.matches("(?i)[a-z0-9-]+\\.fusionsolar\\.huawei\\.com")
                || uri.getRawUserInfo() != null || uri.getPort() != -1
                || !"/pvmswebsite/nologin/assets/build/cloud.html".equals(uri.getRawPath())
                || uri.getRawQuery() != null) throw new IllegalArgumentException();
        String fragment = uri.getRawFragment();
        if (fragment == null || !fragment.startsWith("/kiosk?")) throw new IllegalArgumentException();
        String token = null;
        for (String part : fragment.substring(7).split("&", -1)) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2 || !"kk".equals(pair[0]) || token != null) throw new IllegalArgumentException();
            token = URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name());
        }
        if (token == null || !token.matches("[A-Za-z0-9_-]{16,128}")) throw new IllegalArgumentException();
        return "https://" + host.toLowerCase(java.util.Locale.ROOT)
                + "/rest/pvms/web/kiosk/v1/station-kiosk-file?kk="
                + URLEncoder.encode(token, StandardCharsets.UTF_8.name());
    }

    public JSONObject read(String kioskUrl) throws Exception {
        final String target;
        try { target = endpoint(kioskUrl); }
        catch (Exception e) { throw new HeatingRepository.AppException("Unesite valjanu dijeljenu FusionSolar poveznicu.", 400); }
        Request request = new Request.Builder().url(target).header("Accept", "application/json")
                .header("User-Agent", "TermoDom/2.0 Android").get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new HeatingRepository.AppException(
                    "FusionSolar prikaz nije dostupan. Provjerite dijeljenu poveznicu.", 502);
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > LIMIT) throw new HeatingRepository.AppException("FusionSolar odgovor nije valjan.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream stream = body.byteStream()) {
                byte[] buffer = new byte[8192]; int length;
                while ((length = stream.read(buffer)) != -1) {
                    if (out.size() + length > LIMIT) throw new HeatingRepository.AppException("FusionSolar odgovor je prevelik.");
                    out.write(buffer, 0, length);
                }
            }
            return parse(out.toString(StandardCharsets.UTF_8.name()));
        } catch (HeatingRepository.AppException e) { throw e; }
        catch (Exception e) { throw new HeatingRepository.AppException("Nije moguće osvježiti FusionSolar. Provjerite vezu i dijeljenu poveznicu."); }
    }

    public static JSONObject parse(String body) throws Exception {
        if (body == null || body.length() > LIMIT) throw new HeatingRepository.AppException("FusionSolar odgovor nije valjan.");
        try {
            JSONObject envelope = new JSONObject(body);
            if (!envelope.optBoolean("success", false) || !(envelope.opt("data") instanceof String)) throw new IllegalArgumentException();
            JSONObject source = new JSONObject(Parser.unescapeEntities(envelope.getString("data"), true));
            JSONObject kpi = source.optJSONObject("realKpi"), overview = source.optJSONObject("stationOverview");
            if (kpi == null || overview == null) throw new IllegalArgumentException();
            JSONObject result = new JSONObject();
            String name = overview.optString("stationName", "Solarna elektrana").replaceAll("[\\p{Cntrl}]", " ").trim();
            result.put("stationName", name.length() > 160 ? name.substring(0, 160) : name);
            result.put("fetchedAt", Instant.now().toString());
            result.put("powerKw", number(kpi, "realTimePower"));
            result.put("dailyKwh", number(kpi, "dailyEnergy"));
            result.put("monthKwh", number(kpi, "monthEnergy"));
            result.put("yearKwh", number(kpi, "yearEnergy"));
            result.put("totalKwh", number(kpi, "cumulativeEnergy"));
            JSONObject contribution = source.optJSONObject("socialContribution");
            result.put("co2Kg", number(contribution, "co2Reduction"));
            result.put("coalKg", number(contribution, "standardCoalSavings"));
            result.put("trees", number(contribution, "equivalentTreePlanting"));
            JSONObject curve = source.optJSONObject("powerCurve");
            JSONArray labels = curve == null ? null : curve.optJSONArray("xAxis");
            JSONArray points = curve == null ? null : curve.optJSONArray("activePower");
            JSONArray cleanLabels = new JSONArray(), cleanPoints = new JSONArray();
            if (labels != null && points != null && labels.length() == points.length() && labels.length() <= 1000) {
                for (int i = 0; i < labels.length(); i++) {
                    String label = labels.optString(i, "");
                    if (!label.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) throw new IllegalArgumentException();
                    cleanLabels.put(label); cleanPoints.put(number(points.opt(i)));
                }
            }
            JSONObject dataset = new JSONObject().put("label", "Proizvodnja").put("data", cleanPoints)
                    .put("color", "#e6b55d").put("borderColor", "#e6b55d").put("stepped", false);
            result.put("chart", new JSONObject().put("id", "solar").put("title", "Proizvodnja električne energije")
                    .put("unit", "kW").put("labels", cleanLabels).put("datasets", new JSONArray().put(dataset)));
            return result;
        } catch (Exception e) { throw new HeatingRepository.AppException("FusionSolar poveznica je istekla ili podaci trenutačno nisu dostupni."); }
    }

    private static Object number(JSONObject object, String key) { return number(object == null ? null : object.opt(key)); }
    private static Object number(Object raw) {
        if (!(raw instanceof Number) && !(raw instanceof String)) return JSONObject.NULL;
        try {
            String text = raw.toString().trim();
            if (text.isEmpty()) return JSONObject.NULL;
            double value = Double.parseDouble(text);
            return Double.isFinite(value) && value >= 0 ? value : JSONObject.NULL;
        } catch (Exception e) { return JSONObject.NULL; }
    }
}
