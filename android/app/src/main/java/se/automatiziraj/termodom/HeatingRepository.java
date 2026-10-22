package se.automatiziraj.termodom;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Native HTTPS client for the original controller. Invoke blocking methods off the UI thread. */
public final class HeatingRepository {
    public static final String SOURCE = "https://automatiziraj.se/centralno/php/novo.php";
    private static final URI BASE = URI.create("https://automatiziraj.se/centralno/php/");
    private static final String LOGOUT_PATH = "/centralno/scr/logout.scr.php";
    private static final int RESPONSE_LIMIT = 5_000_000;
    private static final Set<String> READ_PATHS = paths("novo.php", "kat.php", "prizemlje.php", "podesavanje.php",
            "graf_kat_temp.php", "graf_prizemlje_temp.php", "graf_puffer_temp.php", "graf_sanitarna_temp.php", "graf_pec_temp2.php",
            "grafVANJ.php", "graf_tuya.php", "graf_napon2.php", "grafP1.php", "grafp1-6.php");
    private static final Set<String> WRITE_PATHS = paths("dodajPODESAVANJA.php", "zapisi_postavke.php");
    private final Transport transport;
    private final Map<String, HttpCookie> cookies = new LinkedHashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    public static final class AppException extends Exception {
        public final int status;
        public final JSONObject details;
        public AppException(String message) { this(message, 502); }
        public AppException(String message, int status) { this(message, status, new JSONObject()); }
        public AppException(String message, int status, JSONObject details) {
            super(message); this.status = status; this.details = details;
        }
    }

    /** Injectable HTTP transport for fixture tests; the default implementation uses platform TLS. */
    public interface Transport { HttpResponse get(URI uri, Map<String, String> headers) throws Exception; }
    public static final class HttpResponse {
        public final int status;
        public final Map<String, List<String>> headers;
        public final String body;
        public HttpResponse(int status, Map<String, List<String>> headers, String body) {
            this.status = status; this.headers = headers == null ? Collections.emptyMap() : headers; this.body = body == null ? "" : body;
        }
        public HttpResponse(int status, String body) { this(status, Collections.emptyMap(), body); }
        String firstHeader(String name) {
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                if (name.equalsIgnoreCase(header.getKey()) && header.getValue() != null && !header.getValue().isEmpty()) return header.getValue().get(0);
            }
            return null;
        }
    }

    public HeatingRepository() { this(new NativeTransport()); }
    public HeatingRepository(Transport transport) {
        if (transport == null) throw new IllegalArgumentException("HTTP transport nedostaje.");
        this.transport = transport;
    }

    private static Set<String> paths(String... names) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) result.add("/centralno/php/" + name);
        return Collections.unmodifiableSet(result);
    }

    static URI safeUrl(String path) throws AppException {
        try {
            URI uri = BASE.resolve(new URI(path)).normalize();
            String rawPath = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"automatiziraj.se".equalsIgnoreCase(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || (!READ_PATHS.contains(rawPath) && !WRITE_PATHS.contains(rawPath) && !LOGOUT_PATH.equals(rawPath))) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (Exception error) { throw new AppException("Izvorna adresa nije na popisu dopuštenih funkcija.", 400); }
    }

    private static final class NativeTransport implements Transport {
        private static final class Attempt { final AtomicBoolean sent = new AtomicBoolean(); }
        private final OkHttpClient client = new OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(20, TimeUnit.SECONDS)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .addNetworkInterceptor(chain -> {
                    Attempt attempt = chain.request().tag(Attempt.class);
                    if (attempt == null || !attempt.sent.compareAndSet(false, true)) {
                        throw new IOException("Ponavljanje izvornog zahtjeva je zaustavljeno.");
                    }
                    return chain.proceed(chain.request());
                })
                .build();

        @Override public HttpResponse get(URI uri, Map<String, String> headers) throws Exception {
            safeUrl(uri.toString());
            Request.Builder request = new Request.Builder().url(uri.toString()).get().tag(Attempt.class, new Attempt());
            for (Map.Entry<String, String> header : headers.entrySet()) request.header(header.getKey(), header.getValue());
            // Writes are GET requests in the original system. Network recovery must never replay them.
            try (Response response = client.newCall(request.build()).execute()) {
                int status = response.code();
                Map<String, List<String>> responseHeaders = response.headers().toMultimap();
                if (status >= 300 && status < 400) return new HttpResponse(status, responseHeaders, "");
                ResponseBody body = response.body();
                if (body == null) return new HttpResponse(status, responseHeaders, "");
                if (body.contentLength() > RESPONSE_LIMIT) throw new AppException("Izvorni odgovor je prevelik.");
                try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int length;
                    while ((length = input.read(buffer)) != -1) {
                        if (output.size() + length > RESPONSE_LIMIT) throw new AppException("Izvorni odgovor je prevelik.");
                        output.write(buffer, 0, length);
                    }
                    return new HttpResponse(status, responseHeaders, output.toString(StandardCharsets.UTF_8.name()));
                }
            }
        }
    }

    private synchronized void absorbCookies(HttpResponse response) {
        for (Map.Entry<String, List<String>> header : response.headers.entrySet()) {
            if (!"set-cookie".equalsIgnoreCase(header.getKey()) || header.getValue() == null) continue;
            for (String value : header.getValue()) {
                try {
                    for (HttpCookie cookie : HttpCookie.parse(value)) {
                        String domain = cookie.getDomain();
                        if (domain != null && !"automatiziraj.se".equalsIgnoreCase(domain.replaceFirst("^\\.", ""))) continue;
                        if (cookie.hasExpired() || cookie.getValue().isEmpty()) cookies.remove(cookie.getName());
                        else {
                            if (cookie.getPath() == null || cookie.getPath().isEmpty()) cookie.setPath("/");
                            cookies.put(cookie.getName(), cookie);
                        }
                    }
                } catch (IllegalArgumentException ignored) { /* Ignore malformed cookies, never send them elsewhere. */ }
            }
        }
    }

    private synchronized String cookieHeader(URI uri) {
        List<String> pairs = new ArrayList<>();
        Iterator<Map.Entry<String, HttpCookie>> iterator = cookies.entrySet().iterator();
        while (iterator.hasNext()) {
            HttpCookie cookie = iterator.next().getValue();
            if (cookie.hasExpired()) { iterator.remove(); continue; }
            String path = cookie.getPath(), requestPath = uri.getPath();
            if (requestPath.equals(path) || requestPath.startsWith(path.endsWith("/") ? path : path + "/")) pairs.add(cookie.getName() + "=" + cookie.getValue());
        }
        return String.join("; ", pairs);
    }

    private synchronized void clearSession() { cookies.clear(); }

    private String request(String path, Map<String, String> params, boolean mutation, boolean logout) throws Exception {
        URI uri = safeUrl(path);
        boolean allowed = mutation ? WRITE_PATHS.contains(uri.getRawPath()) : logout ? LOGOUT_PATH.equals(uri.getRawPath()) : READ_PATHS.contains(uri.getRawPath());
        if (!allowed) throw new AppException("Ova izvorna funkcija nije dopuštena za traženi način rada.", 400);
        if (params != null && !params.isEmpty()) {
            // Parameters originate only from the parsed, validated controller forms.
            Map<String, String> merged = new LinkedHashMap<>();
            if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
                for (String pair : uri.getRawQuery().split("&")) {
                    String[] parts = pair.split("=", 2);
                    merged.put(URLDecoder.decode(parts[0], "UTF-8"), parts.length == 1 ? "" : URLDecoder.decode(parts[1], "UTF-8"));
                }
            }
            merged.putAll(params);
            StringBuilder query = new StringBuilder();
            for (Map.Entry<String, String> param : merged.entrySet()) {
                if (query.length() > 0) query.append('&');
                query.append(URLEncoder.encode(param.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(param.getValue(), "UTF-8"));
            }
            uri = URI.create(uri.toString().split("\\?", 2)[0] + "?" + query);
        }
        for (int hop = 0; hop <= 4; hop++) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "text/html");
            headers.put("User-Agent", "TermoDom/1.1 (native Android heating controller)");
            String cookie = cookieHeader(uri);
            if (!cookie.isEmpty()) headers.put("Cookie", cookie);
            HttpResponse response;
            try { response = transport.get(uri, Collections.unmodifiableMap(headers)); }
            catch (Exception error) {
                throw new AppException("Veza s izvornim sustavom nije dostupna" + (mutation ? "; potvrda spremanja nije primljena, prije ponavljanja osvježite podatke" : "") + ".", 502,
                        new JSONObject().put("mutationUnconfirmed", mutation));
            }
            absorbCookies(response);
            if (Arrays.asList(301, 302, 303, 307, 308).contains(response.status)) {
                String location = response.firstHeader("location");
                if (location == null || location.isEmpty()) throw new AppException("Izvorni sustav poslao je neispravno preusmjeravanje.");
                if (logout) return "";
                URI next;
                try { next = uri.resolve(new URI(location)); }
                catch (Exception error) { throw new AppException("Izvorni sustav poslao je neispravno preusmjeravanje."); }
                String nextPath = next.getPath() == null ? "" : next.getPath().toLowerCase(Locale.ROOT);
                if (nextPath.contains("login") || nextPath.contains("prijava")) throw new AppException("Izvorni sustav traži prijavu.", 401, new JSONObject().put("requiresLogin", true));
                uri = safeUrl(next.toString());
                // A mutation is never replayed, even for a 307/308 response.
                if (!READ_PATHS.contains(uri.getRawPath())) throw new AppException("Neočekivano preusmjeravanje izvornog sustava.");
                continue;
            }
            if (response.status < 200 || response.status >= 300) {
                boolean login = response.status == 401 || response.status == 403;
                throw new AppException("Izvorni sustav vratio je HTTP " + response.status + ".", login ? 401 : 502,
                        new JSONObject().put("requiresLogin", login).put("mutationUnconfirmed", mutation));
            }
            if (response.body.getBytes(StandardCharsets.UTF_8).length > RESPONSE_LIMIT) throw new AppException("Izvorni odgovor je prevelik.");
            if (!logout && HeatingParser.detectLogin(response.body)) throw new AppException("Izvorni sustav traži prijavu.", 401, new JSONObject().put("requiresLogin", true));
            return response.body;
        }
        throw new AppException("Previše preusmjeravanja izvornog sustava.");
    }

    private String read(String path) throws Exception { return request(path, null, false, false); }
    public JSONObject status() throws Exception {
        return HeatingParser.parseStatus(read("novo.php")).put("source", SOURCE).put("fetchedAt", Instant.now().toString());
    }
    public JSONObject schedule(String id) throws Exception {
        HeatingParser.requireZone(id);
        return HeatingParser.parseSchedule(read(id + ".php"), id).put("fetchedAt", Instant.now().toString());
    }
    public JSONObject settings() throws Exception {
        return HeatingParser.parseSettings(read("podesavanje.php")).put("fetchedAt", Instant.now().toString());
    }
    public JSONObject chart(String id) throws Exception {
        String path = ChartParser.pathFor(id);
        if (path == null) throw new AppException("Nepoznat grafikon.", 404);
        return ChartParser.parse(read(path), id).put("fetchedAt", Instant.now().toString());
    }
    public JSONArray catalog() throws Exception { return ChartParser.catalog(); }

    private void beginSave() throws AppException {
        if (!busy.compareAndSet(false, true)) throw new AppException("Drugo spremanje je u tijeku. Pričekajte potvrdu.", 409);
    }
    private static boolean equal(double left, double right) { return Math.abs(left - right) < 0.00001; }

    public JSONObject saveSchedule(String id, double[] values) throws Exception {
        beginSave();
        try {
            double[] requested = values == null ? null : values.clone();
            JSONObject current = schedule(id);
            HeatingParser.validateSchedule(requested, current);
            if (!"/centralno/php/dodajPODESAVANJA.php".equals(safeUrl(current.getString("action")).getRawPath()) || !"GET".equals(current.getString("method"))) {
                throw new AppException("Izvorni način spremanja rasporeda promijenjen je. Spremanje je zaustavljeno.");
            }
            Map<String, String> params = new LinkedHashMap<>();
            JSONArray fields = current.getJSONArray("fields");
            for (int i = 0; i < 24; i++) params.put(fields.getJSONObject(i).getString("name"), Double.toString(requested[i]));
            JSONObject hidden = current.getJSONObject("hidden");
            Iterator<String> names = hidden.keys();
            while (names.hasNext()) { String name = names.next(); params.put(name, hidden.getString(name)); }
            request(current.getString("action"), params, true, false);
            JSONObject saved;
            try { saved = schedule(id); }
            catch (Exception error) { throw new AppException("Zahtjev je poslan, ali ponovna provjera rasporeda nije uspjela. Osvježite prije ponovnog spremanja.", 502, new JSONObject().put("mutationUnconfirmed", true)); }
            for (int i = 0; i < 24; i++) {
                if (!equal(requested[i], saved.getJSONArray("fields").getJSONObject(i).getDouble("value"))) {
                    throw new AppException("Izvorna stranica nije potvrdila sve spremljene temperature. Osvježite raspored i provjerite vrijednosti.", 502,
                            new JSONObject().put("mutationUnconfirmed", true).put("current", saved));
                }
            }
            return saved.put("ok", true).put("verified", true).put("message", "Izvorna stranica potvrdila je raspored. Sinkronizaciju regulatora pratite na pregledu.");
        } finally { busy.set(false); }
    }

    public JSONObject saveSettings(JSONObject values) throws Exception {
        beginSave();
        try {
            HeatingParser.validateSettings(values);
            JSONObject requested = new JSONObject(values.toString());
            HeatingParser.validateSettings(requested);
            JSONObject current = settings();
            if (!"/centralno/php/zapisi_postavke.php".equals(safeUrl(current.getString("action")).getRawPath()) || !"GET".equals(current.getString("method"))) {
                throw new AppException("Izvorni način spremanja postavki promijenjen je. Spremanje je zaustavljeno.");
            }
            Map<String, String> params = new LinkedHashMap<>();
            for (int n = 0; n < 6; n++) params.put("n" + n, Double.toString(requested.getDouble("n" + n)));
            request(current.getString("action"), params, true, false);
            JSONObject saved;
            try { saved = settings(); }
            catch (Exception error) { throw new AppException("Zahtjev je poslan, ali ponovna provjera postavki nije uspjela. Osvježite prije ponovnog spremanja.", 502, new JSONObject().put("mutationUnconfirmed", true)); }
            for (int n = 0; n < 6; n++) {
                if (!equal(saved.getJSONArray("fields").getJSONObject(n).getDouble("value"), requested.getDouble("n" + n))) {
                    throw new AppException("Izvorna stranica nije potvrdila sve spremljene postavke. Osvježite i provjerite vrijednosti.", 502,
                            new JSONObject().put("mutationUnconfirmed", true).put("current", saved));
                }
            }
            return saved.put("ok", true).put("verified", true).put("message", "Izvorna stranica potvrdila je postavke. Primjena na regulatoru može uslijediti naknadno.");
        } finally { busy.set(false); }
    }

    public void logout() throws Exception {
        beginSave();
        try { request(LOGOUT_PATH, null, false, true); }
        finally { clearSession(); busy.set(false); }
    }
}
