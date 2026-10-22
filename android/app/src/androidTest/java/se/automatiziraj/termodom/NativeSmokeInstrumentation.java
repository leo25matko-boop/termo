package se.automatiziraj.termodom;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Platform-only launch/navigation test. All controller responses are recorded fixtures. */
public final class NativeSmokeInstrumentation extends Instrumentation {
    private Activity activity;
    private final AtomicInteger requests = new AtomicInteger();
    private File evidenceDirectory;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(BuildConfig.DEBUG, "The smoke runner must target the debug app.");
            MainActivity.testRepository = new HeatingRepository((uri, headers) -> {
                // Any save, logout, or unrecognized call fails the test. These tests
                // cannot change a live controller or even open a network socket.
                check(uri.getRawQuery() == null, "Unexpected controller query: " + uri.getPath());
                String name = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
                check(!name.equals("dodajPODESAVANJA.php") && !name.equals("zapisi_postavke.php")
                        && !name.equals("logout.scr.php"), "A read-only test attempted a controller mutation.");
                requests.incrementAndGet();
                try (InputStream input = getContext().getAssets().open("fixtures/" + name + ".html");
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    return new HeatingRepository.HttpResponse(name.equals("grafP1.php") || name.equals("grafp1-6.php") ? 404 : 200,
                            output.toString(StandardCharsets.UTF_8.name()));
                }
            });
            // This is generated test data, never a real station token or URL.
            MainActivity.testSolarData = new org.json.JSONObject()
                    .put("stationName", "Solarna elektrana · provjera")
                    .put("fetchedAt", "2026-10-22T12:00:00Z")
                    .put("powerKw", 2.48).put("dailyKwh", 14.17)
                    .put("monthKwh", 945.81).put("yearKwh", 17399.06).put("totalKwh", 25922.32)
                    .put("co2Kg", 12313.10).put("coalKg", 10368.93).put("trees", 17)
                    .put("chart", new org.json.JSONObject().put("id", "solar")
                            .put("title", "Proizvodnja električne energije").put("unit", "kW")
                            .put("labels", new org.json.JSONArray().put("08:00").put("09:00").put("10:00").put("11:00").put("12:00"))
                            .put("datasets", new org.json.JSONArray().put(new org.json.JSONObject()
                                    .put("label", "Proizvodnja").put("borderColor", "#e6b55d").put("stepped", false)
                                    .put("data", new org.json.JSONArray().put(0).put(0.4).put(org.json.JSONObject.NULL).put(1.5).put(2.48)))));
            Intent launch = new Intent(getTargetContext(), MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = startActivitySync(launch);
            evidenceDirectory = new File(getTargetContext().getExternalFilesDir(null), "native-smoke");
            check(evidenceDirectory.isDirectory() || evidenceDirectory.mkdirs(), "Cannot create screenshot folder.");

            waitFor("overview", () -> hasClass(activity.getWindow().getDecorView(), "se.automatiziraj.termodom.TemperatureDialView"));
            verifyNativeHierarchy();
            capture("01-pregled");

            navigate("zones");
            waitFor("zones", () -> hasClass(activity.getWindow().getDecorView(), "se.automatiziraj.termodom.TemperatureDialView"));
            verifyNativeHierarchy();
            capture("02-zone");

            navigate("schedule");
            waitFor("schedule", () -> countSeekBars(activity.getWindow().getDecorView()) >= 24);
            verifyNativeHierarchy();
            capture("03-rasporedi");

            navigate("charts");
            waitFor("charts", () -> hasClass(activity.getWindow().getDecorView(), "se.automatiziraj.termodom.NativeChartView"));
            verifyNativeHierarchy();
            capture("04-grafikoni");

            navigate("settings");
            waitFor("settings", () -> countEditors(activity.getWindow().getDecorView()) >= 5);
            verifyNativeHierarchy();
            capture("05-postavke");

            openModule("solar");
            waitFor("solar", () -> hasClass(activity.getWindow().getDecorView(), "se.automatiziraj.termodom.NativeChartView"));
            verifyNativeHierarchy();
            capture("06-solarna-elektrana");

            openModule("camera");
            waitFor("camera", () -> hasText(activity.getWindow().getDecorView(), "Xiaomi"));
            verifyNativeHierarchy();
            capture("07-kamera");

            openModule("upcoming");
            waitFor("upcoming", () -> hasText(activity.getWindow().getDecorView(), "priprem"));
            verifyNativeHierarchy();
            capture("08-priprema");

            check(requests.get() >= 5, "Expected fixture reads for every controller area.");
            String liveReport = liveReadReport();
            result.putString("stream", "\nTERMODOM_NATIVE_SMOKE_OK: 8 native screens; 24 hourly sliders; heating and solar native charts; fixture-only UI reads; no external camera or login launch.\n" + liveReport);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            try { if (activity != null && evidenceDirectory != null) capture("failure"); } catch (Throwable ignored) { }
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            result.putString("stream", "\nTERMODOM_NATIVE_SMOKE_FAILED\n" + trace);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    /** One optional real status GET verifies Android TLS without sending any control command. */
    private String liveReadReport() throws Exception {
        String report;
        long started = SystemClock.elapsedRealtime();
        try {
            org.json.JSONObject live = new HeatingRepository().status();
            org.json.JSONArray zones = live.optJSONArray("zones");
            check(zones != null && zones.length() > 0, "The source returned no heating zones.");
            report = "TERMODOM_LIVE_STATUS_READ_OK: zoneCount=" + zones.length();
        } catch (Throwable error) {
            String code = error instanceof HeatingRepository.AppException ? " status=" + ((HeatingRepository.AppException) error).status : "";
            report = "TERMODOM_LIVE_STATUS_READ_UNAVAILABLE:" + code + " " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        report += "; elapsedMs=" + (SystemClock.elapsedRealtime() - started)
                + "; source=https://automatiziraj.se/centralno/php/novo.php; controlCommands=0\n";
        try (FileOutputStream output = new FileOutputStream(new File(evidenceDirectory, "live-status-read.txt"))) {
            output.write(report.getBytes(StandardCharsets.UTF_8));
        }
        return report;
    }

    private void navigate(String screen) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                View navigation = findDescription(activity.getWindow().getDecorView(), "nav-" + screen);
                check(navigation != null, "Missing native navigation: " + screen);
                check(navigation.performClick(), "Navigation is not clickable: " + screen);
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Navigation failed", failure.get());
        waitFor(screen, () -> countNativeViews(activity.getWindow().getDecorView()) >= 10);
    }

    private void openModule(String screen) throws Exception {
        navigate("overview");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                View module = findDescription(activity.getWindow().getDecorView(), "module-" + screen);
                check(module != null, "Missing native home module: " + screen);
                check(module.performClick(), "Home module is not clickable: " + screen);
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Opening module failed", failure.get());
    }

    private interface Condition { boolean isReady(); }

    private void waitFor(String screen, Condition condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            runOnMainSync(() -> ready.set(findDescription(activity.getWindow().getDecorView(), "screen-" + screen) != null && condition.isReady()));
            if (ready.get()) { waitForIdleSync(); return; }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Native screen did not finish loading: " + screen);
    }

    private void verifyNativeHierarchy() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                View root = activity.getWindow().getDecorView();
                check(!hasClass(root, "android.webkit.WebView"), "The Android app contains a WebView.");
                check(countNativeViews(root) >= 10, "Expected real Android view hierarchy.");
                for (String name : new String[]{"overview", "zones", "schedule", "charts", "settings"}) {
                    View button = findDescription(root, "nav-" + name);
                    check(button != null, "Native navigation button missing: " + name);
                    float density = activity.getResources().getDisplayMetrics().density;
                    check(button.getWidth() >= 44 * density && button.getHeight() >= 44 * density,
                            "Navigation touch target is smaller than 44dp: " + name);
                }
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Invalid native hierarchy", failure.get());
    }

    private void capture(String name) throws Exception {
        waitForIdleSync();
        SystemClock.sleep(250);
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        check(bitmap != null, "Android could not capture the screen.");
        try (FileOutputStream output = new FileOutputStream(new File(evidenceDirectory, name + ".png"))) {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Android could not encode the screenshot.");
        } finally { bitmap.recycle(); }
        AtomicReference<String> hierarchy = new AtomicReference<>();
        runOnMainSync(() -> {
            StringBuilder text = new StringBuilder();
            describe(activity.getWindow().getDecorView(), text, 0);
            hierarchy.set(text.toString());
        });
        try (FileOutputStream output = new FileOutputStream(new File(evidenceDirectory, name + ".txt"))) {
            output.write(hierarchy.get().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static View findDescription(View root, String description) {
        if (description.equals(root.getTag()) || description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findDescription(group.getChildAt(index), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasClass(View root, String name) {
        Class<?> type = root.getClass();
        while (type != null) { if (type.getName().equals(name)) return true; type = type.getSuperclass(); }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) if (hasClass(group.getChildAt(index), name)) return true;
        }
        return false;
    }

    private static int countNativeViews(View root) {
        int result = 1;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) result += countNativeViews(group.getChildAt(index));
        }
        return result;
    }

    private static boolean hasText(View root, String expected) {
        if (root instanceof TextView && ((TextView) root).getText().toString().contains(expected)) return true;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) if (hasText(group.getChildAt(index), expected)) return true;
        }
        return false;
    }

    private static int countSeekBars(View root) {
        int result = root instanceof SeekBar ? 1 : 0;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) result += countSeekBars(group.getChildAt(index));
        }
        return result;
    }

    private static int countEditors(View root) {
        int result = root instanceof EditText ? 1 : 0;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) result += countEditors(group.getChildAt(index));
        }
        return result;
    }

    private static void describe(View view, StringBuilder text, int depth) {
        for (int index = 0; index < depth; index++) text.append("  ");
        text.append(view.getClass().getName()).append(" [").append(view.getWidth()).append('x').append(view.getHeight()).append("]");
        if (view.getContentDescription() != null) text.append(" desc=").append(view.getContentDescription());
        if (view instanceof TextView) text.append(" text=").append(((TextView) view).getText());
        text.append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) describe(group.getChildAt(index), text, depth + 1);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
