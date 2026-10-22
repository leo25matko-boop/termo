package se.automatiziraj.termodom;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/** A local, installed controller. Only the Java bridge can contact the heating server. */
public final class MainActivity extends Activity {
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String HOME = "https://" + ASSET_HOST + "/assets/public/index.html";
    private static final String SOURCE_HOST = "automatiziraj.se";
    private static final int MAX_RESPONSE = 5_000_000;
    private static final Set<String> PATHS = new HashSet<>(Arrays.asList(
            "/centralno/php/novo.php", "/centralno/php/kat.php",
            "/centralno/php/prizemlje.php", "/centralno/php/podesavanje.php",
            "/centralno/php/graf_kat_temp.php", "/centralno/php/graf_prizemlje_temp.php",
            "/centralno/php/graf_puffer_temp.php", "/centralno/php/graf_sanitarna_temp.php",
            "/centralno/php/graf_pec_temp2.php", "/centralno/php/grafVANJ.php",
            "/centralno/php/graf_tuya.php", "/centralno/php/graf_napon2.php",
            "/centralno/php/grafP1.php", "/centralno/php/grafp1-6.php",
            "/centralno/php/dodajPODESAVANJA.php", "/centralno/php/zapisi_postavke.php",
            "/centralno/scr/logout.scr.php"));
    private static final Set<String> HEADERS = new HashSet<>(Arrays.asList(
            "accept", "user-agent", "cookie"));

    private final AtomicBoolean trustedPage = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, NetworkJob> jobs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32));
    private WebView webView;
    private volatile boolean destroyed;
    private OnBackInvokedCallback backCallback;
    private byte[] pendingCsv;
    private static final int CREATE_CSV = 1704;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        final int background = Color.rgb(245, 246, 242);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(background);
        configureInsets(root);
        webView = new WebView(this);
        webView.setBackgroundColor(background);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        root.requestApplyInsets();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBlockNetworkLoads(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setGeolocationEnabled(false);
        settings.setSafeBrowsingEnabled(true);
        settings.setTextZoom(100);
        CookieManager.getInstance().setAcceptCookie(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isAsset(uri) && "GET".equals(request.getMethod())) {
                    WebResourceResponse response = assetLoader.shouldInterceptRequest(uri);
                    if (response != null) {
                        // Older Android MIME tables do not recognize ECMAScript modules.
                        if (uri.getPath().endsWith(".mjs") || uri.getPath().endsWith(".js")) {
                            response.setMimeType("text/javascript");
                            response.setEncoding("UTF-8");
                        }
                        return response;
                    }
                }
                return blockedResponse();
            }

            @Override public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                if (isHome(request.getUrl())) return false;
                if (request.isForMainFrame()) openExternal(request.getUrl().toString());
                return true;
            }

            @Override public void onPageStarted(WebView view, String url,
                                              android.graphics.Bitmap favicon) {
                trustedPage.set(isHome(Uri.parse(url)));
            }

            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                                     SslError error) {
                handler.cancel();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
        });
        webView.addJavascriptInterface(new MobileBridge(), "MobileBridge");
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, backCallback);
        }
        webView.loadUrl(HOME);
    }

    @SuppressWarnings("deprecation")
    private void configureInsets(FrameLayout root) {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right,
                        Math.max(bars.bottom, keyboard.bottom));
                android.view.WindowInsetsController controller = view.getWindowInsetsController();
                if (controller != null) controller.setSystemBarsAppearance(
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                // The native container owns the safe area; do not inset WebView twice.
                return WindowInsets.CONSUMED;
            });
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets.consumeSystemWindowInsets();
            });
        }
    }

    private static boolean isAsset(Uri uri) {
        return "https".equals(uri.getScheme()) && ASSET_HOST.equals(uri.getHost())
                && uri.getPort() == -1 && uri.getUserInfo() == null
                && uri.getPath() != null && (uri.getPath().startsWith("/assets/public/")
                || uri.getPath().startsWith("/assets/lib/"));
    }

    private static boolean isHome(Uri uri) {
        return isAsset(uri) && "/assets/public/index.html".equals(uri.getPath())
                && uri.getQuery() == null;
    }

    private static WebResourceResponse blockedResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
                java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private void handleBack() {
        if (!destroyed && webView != null && trustedPage.get()) {
            webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('termodom-back'));", null);
        } else finish();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { handleBack(); }

    @Override protected void onPause() {
        if (webView != null) {
            if (trustedPage.get()) webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('termodom-pause'));", null);
            webView.onPause();
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            if (trustedPage.get()) webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('termodom-resume'));", null);
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        trustedPage.set(false);
        for (NetworkJob job : jobs.values()) job.cancel();
        jobs.clear();
        requests.shutdownNow();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        if (webView != null) {
            webView.removeJavascriptInterface("MobileBridge");
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CREATE_CSV) return;
        final byte[] content = pendingCsv;
        pendingCsv = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || content == null) {
            exportResult(false, "Spremanje je otkazano.");
            return;
        }
        final Uri destination = data.getData();
        if (!"content".equals(destination.getScheme())) {
            exportResult(false, "Odabrana datoteka nije dostupna za spremanje.");
            return;
        }
        try {
            requests.execute(() -> {
                try (OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) throw new java.io.IOException("No output stream");
                    output.write(content);
                    output.flush();
                    exportResult(true, "CSV datoteka je spremljena.");
                } catch (Exception error) {
                    exportResult(false, "CSV datoteka nije spremljena. Pokušajte ponovno.");
                }
            });
        } catch (RejectedExecutionException busy) {
            exportResult(false, "Aplikacija je zauzeta. Pokušajte ponovno spremiti CSV.");
        }
    }

    private void exportResult(boolean success, String message) {
        if (destroyed) return;
        runOnUiThread(() -> {
            if (!destroyed && trustedPage.get() && webView != null) {
                webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('termodom-export',"
                        + "{detail:{success:" + success + ",message:" + JSONObject.quote(message)
                        + "}}));", null);
            }
        });
    }

    private void respond(String id, JSONObject response) {
        if (destroyed) return;
        runOnUiThread(() -> {
            if (!destroyed && trustedPage.get() && webView != null) {
                webView.evaluateJavascript("window.__termodomNativeResponse && "
                        + "window.__termodomNativeResponse(" + JSONObject.quote(id)
                        + "," + response + ");", null);
            }
        });
    }

    private void respondError(String id, String message) {
        JSONObject response = new JSONObject();
        try { response.put("error", message); }
        catch (JSONException impossible) { return; }
        respond(id, response);
    }

    private void openExternal(String value) {
        if (destroyed || !trustedPage.get() || value == null || value.length() > 2048) return;
        try {
            URI uri = new URI(value);
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null
                    || uri.getPort() != -1 || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) return;
            boolean original = SOURCE_HOST.equals(uri.getHost())
                    && "/centralno/php/novo.php".equals(uri.getRawPath());
            String path = uri.getRawPath();
            boolean repository = "github.com".equals(uri.getHost())
                    && ("/leo25matko-boop/termo".equals(path)
                    || "/leo25matko-boop/termo/releases".equals(path)
                    || "/leo25matko-boop/termo/releases/latest".equals(path)
                    || path.startsWith("/leo25matko-boop/termo/releases/download/")
                    || path.startsWith("/leo25matko-boop/termo/releases/tag/"));
            if (!original && !repository) return;
            runOnUiThread(() -> {
                if (destroyed) return;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(value))
                        .addCategory(Intent.CATEGORY_BROWSABLE)); }
                catch (ActivityNotFoundException error) {
                    Toast.makeText(this, "Nije pronađena aplikacija za otvaranje poveznice.",
                            Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception ignored) { /* An invalid external URL is never opened. */ }
    }

    public final class MobileBridge {
        @JavascriptInterface public void request(String json) {
            if (destroyed || !trustedPage.get() || json == null || json.length() > 16_384) return;
            String id = "";
            try {
                JSONObject input = new JSONObject(json);
                id = input.getString("id");
                if (!id.matches("[A-Za-z0-9_-]{1,120}")) return;
                NetworkJob job = new NetworkJob(id, input);
                if (jobs.putIfAbsent(id, job) != null) {
                    respondError(id, "Zahtjev s tim identifikatorom već postoji.");
                    return;
                }
                try { requests.execute(job); }
                catch (RejectedExecutionException busy) {
                    jobs.remove(id, job);
                    respondError(id, "Previše istodobnih zahtjeva. Pokušajte ponovno.");
                }
            } catch (Exception invalid) {
                if (!id.isEmpty()) respondError(id, "Neispravan zahtjev aplikacije.");
            }
        }

        @JavascriptInterface public void cancel(String id) {
            if (destroyed || !trustedPage.get() || id == null) return;
            NetworkJob job = jobs.get(id);
            if (job != null) {
                job.cancel();
                if (requests.remove(job)) jobs.remove(id, job);
            }
        }

        @JavascriptInterface public void closeApp() {
            if (!destroyed && trustedPage.get()) runOnUiThread(() -> finish());
        }

        @JavascriptInterface public void openExternal(String url) {
            MainActivity.this.openExternal(url);
        }

        @JavascriptInterface public void saveCsv(String filename, String content) {
            if (destroyed || !trustedPage.get()) return;
            if (filename == null || !filename.matches("TermoDom-[A-Za-z0-9_-]{1,80}\\.csv")
                    || content == null || content.length() > MAX_RESPONSE) {
                exportResult(false, "Podaci za spremanje CSV datoteke nisu ispravni.");
                return;
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_RESPONSE) {
                exportResult(false, "CSV datoteka je prevelika.");
                return;
            }
            runOnUiThread(() -> {
                if (destroyed || !trustedPage.get()) return;
                if (pendingCsv != null) {
                    exportResult(false, "Najprije dovršite prethodno spremanje datoteke.");
                    return;
                }
                pendingCsv = bytes;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv")
                        .putExtra(Intent.EXTRA_TITLE, filename);
                try { startActivityForResult(intent, CREATE_CSV); }
                catch (ActivityNotFoundException error) {
                    pendingCsv = null;
                    exportResult(false, "Na uređaju nije dostupan odabir mjesta za spremanje.");
                }
            });
        }
    }

    private final class NetworkJob implements Runnable {
        private final String id;
        private final JSONObject input;
        private volatile HttpsURLConnection connection;
        private volatile boolean cancelled;

        NetworkJob(String id, JSONObject input) { this.id = id; this.input = input; }

        void cancel() {
            cancelled = true;
            HttpsURLConnection active = connection;
            if (active != null) active.disconnect();
        }

        @Override public void run() {
            if (cancelled || destroyed) { jobs.remove(id, this); return; }
            try {
                String address = input.getString("url");
                if (address.length() > 4096) throw new IllegalArgumentException("URL too long");
                URI uri = new URI(address);
                if (!"https".equals(uri.getScheme()) || !SOURCE_HOST.equals(uri.getHost())
                        || (uri.getPort() != -1 && uri.getPort() != 443)
                        || uri.getUserInfo() != null || uri.getRawFragment() != null
                        || !PATHS.contains(uri.getRawPath())) {
                    throw new IllegalArgumentException("URL not allowed");
                }
                connection = (HttpsURLConnection) uri.toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(20_000);
                connection.setUseCaches(false);
                JSONObject headers = input.optJSONObject("headers");
                if (headers != null) {
                    java.util.Iterator<String> keys = headers.keys();
                    while (keys.hasNext()) {
                        String name = keys.next();
                        String value = headers.getString(name);
                        if (!HEADERS.contains(name.toLowerCase(Locale.ROOT))
                                || value.length() > 8192 || value.indexOf('\r') >= 0
                                || value.indexOf('\n') >= 0) {
                            throw new IllegalArgumentException("Header not allowed");
                        }
                        connection.setRequestProperty(name, value);
                    }
                }
                if (cancelled) return;
                int status = connection.getResponseCode();
                JSONArray responseHeaders = new JSONArray();
                for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                    if (header.getKey() == null || header.getValue() == null) continue;
                    for (String value : header.getValue()) responseHeaders.put(
                            new JSONArray().put(header.getKey()).put(value));
                }
                if (connection.getContentLengthLong() > MAX_RESPONSE) {
                    throw new IllegalArgumentException("Response too large");
                }
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (stream != null) {
                    try (InputStream source = stream) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while (!cancelled && (count = source.read(buffer)) != -1) {
                            if (bytes.size() + count > MAX_RESPONSE) {
                                throw new IllegalArgumentException("Response too large");
                            }
                            bytes.write(buffer, 0, count);
                        }
                    }
                }
                Charset charset = StandardCharsets.UTF_8;
                String contentType = connection.getContentType();
                if (contentType != null) {
                    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                            "(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9._-]+)").matcher(contentType);
                    if (matcher.find()) {
                        try { charset = Charset.forName(matcher.group(1)); }
                        catch (Exception ignored) { /* UTF-8 remains the fallback. */ }
                    }
                }
                if (!cancelled && !destroyed) respond(id, new JSONObject()
                        .put("status", status).put("headers", responseHeaders)
                        .put("body", new String(bytes.toByteArray(), charset)));
            } catch (Exception error) {
                if (!cancelled && !destroyed) respondError(id,
                        "Veza sa sustavom grijanja nije dostupna. Provjerite internetsku vezu.");
            } finally {
                HttpsURLConnection active = connection;
                if (active != null) active.disconnect();
                jobs.remove(id, this);
            }
        }
    }
}
