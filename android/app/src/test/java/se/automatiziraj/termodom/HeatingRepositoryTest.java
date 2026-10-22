package se.automatiziraj.termodom;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.Test;

import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real source fixtures and fake transport: tests never change the heating system. */
public final class HeatingRepositoryTest {
    private static String fixture(String name) throws Exception {
        try (InputStream stream = HeatingRepositoryTest.class.getResourceAsStream("/heating/" + name)) {
            if (stream == null) throw new IllegalStateException("Missing fixture " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private interface Operation { void run() throws Exception; }
    private static HeatingRepository.AppException failure(Operation operation) throws Exception {
        try { operation.run(); fail("Expected controller failure"); return null; }
        catch (HeatingRepository.AppException error) { return error; }
    }
    private static HeatingRepository.HttpResponse ok(String html) { return new HeatingRepository.HttpResponse(200, html); }
    private static double[] values(double value) { double[] result = new double[24]; Arrays.fill(result, value); return result; }
    private static String scheduleWith(String html, double value) {
        Document doc = Jsoup.parse(html);
        for (Element input : doc.select("input[type=range]")) input.attr("value", Double.toString(value));
        return doc.html();
    }
    private static JSONObject settingsValues() throws Exception {
        return new JSONObject().put("n0", 23).put("n1", 35).put("n2", 26).put("n3", 25).put("n4", 24).put("n5", 1);
    }
    private static String settingsWith(String html, JSONObject values) throws Exception {
        Document doc = Jsoup.parse(html);
        for (int n = 0; n < 6; n++) doc.selectFirst("input[name=n" + n + "]").attr("value", values.get("n" + n).toString());
        return doc.html();
    }
    private static Map<String, String> query(URI uri) throws Exception {
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            query.put(URLDecoder.decode(parts[0], "UTF-8"), URLDecoder.decode(parts[1], "UTF-8"));
        }
        return query;
    }

    @Test public void statusRetainsAllSensorsAndPhysicalPumpMappings() throws Exception {
        String html = fixture("https-original.html");
        JSONObject status = new HeatingRepository((uri, headers) -> ok(html)).status();
        assertEquals(HeatingRepository.SOURCE, status.getString("source"));
        assertTrue(status.getString("fetchedAt").endsWith("Z"));
        assertEquals("2026-10-22 19:22:23", status.getString("serverTime"));
        assertEquals("19:20", status.getString("systemTime"));
        assertEquals("SYNC kat", status.getJSONObject("sync").getString("kat"));
        assertEquals("SYNC prizemlje", status.getJSONObject("sync").getString("prizemlje"));
        assertEquals(60, status.getInt("resets"));
        assertEquals(11.5, status.getDouble("outside"), 0);
        assertEquals(6, status.getDouble("percentage"), 0);
        JSONArray zones = status.getJSONArray("zones");
        assertEquals(2, zones.length());
        JSONObject kat = zones.getJSONObject(0), p = zones.getJSONObject(1);
        assertEquals("kat", kat.getString("id")); assertEquals(26, kat.getDouble("temperature"), 0);
        assertEquals(37, kat.getDouble("waterTemperature"), 0); assertEquals(33, kat.getDouble("plannedWaterTemperature"), 0);
        assertFalse(kat.getBoolean("pumpRunning"));
        assertEquals("prizemlje", p.getString("id")); assertEquals(23.27, p.getDouble("temperature"), 0);
        assertEquals(36, p.getDouble("waterTemperature"), 0); assertEquals(33, p.getDouble("plannedWaterTemperature"), 0);
        assertTrue(p.getBoolean("pumpRunning"));
        JSONObject buffer = status.getJSONObject("buffer");
        assertEquals(37, buffer.getDouble("top"), 0); assertEquals(12, buffer.getDouble("middle"), 0); assertEquals(12, buffer.getDouble("bottom"), 0);
        assertEquals(34, status.getJSONObject("sanitary").getDouble("temperature"), 0);
        assertFalse(status.getJSONObject("sanitary").getBoolean("pumpRunning"));
        JSONObject boiler = status.getJSONObject("boiler");
        assertEquals(19, boiler.getDouble("temperature"), 0); assertTrue(boiler.getBoolean("pumpRunning")); assertTrue(boiler.getBoolean("fanRunning"));
    }

    @Test public void missingSensorsStayUnknownAndWrongPagesFailVisibly() throws Exception {
        String html = fixture("https-original.html").replace("../slike/ok.png", "../slike/unknown.png")
                .replace("../slike/no.png", "../slike/unknown.png").replace("<p>SYNC kat</p>", "");
        JSONObject status = HeatingParser.parseStatus(html);
        assertTrue(status.getJSONArray("zones").getJSONObject(0).isNull("pumpRunning"));
        assertTrue(status.getJSONObject("boiler").isNull("fanRunning"));
        assertTrue(status.getJSONObject("sync").isNull("kat"));
        assertTrue(failure(() -> HeatingParser.parseStatus("<h1>Unavailable</h1>")).getMessage().contains("očekivanu strukturu"));
        assertEquals(401, failure(() -> HeatingParser.parseStatus("<input TYPE=PASSWORD>")).status);
    }

    @Test public void schedulesKeepAll24ValuesBoundsDefaultsAndHiddenZone() throws Exception {
        JSONObject k = HeatingParser.parseSchedule(fixture("kat.php.html"), "kat");
        JSONObject p = HeatingParser.parseSchedule(fixture("prizemlje.php.html"), "prizemlje");
        assertEquals(24, k.getJSONArray("fields").length()); assertEquals(24, p.getJSONArray("fields").length());
        assertEquals(25.7, k.getJSONArray("fields").getJSONObject(23).getDouble("value"), 0);
        assertEquals(22.7, p.getJSONArray("fields").getJSONObject(23).getDouble("value"), 0);
        assertEquals(33, k.getJSONArray("fields").getJSONObject(0).getDouble("max"), 0);
        assertEquals(25, p.getJSONArray("fields").getJSONObject(0).getDouble("max"), 0);
        assertEquals(18, k.getJSONArray("fields").getJSONObject(0).getDouble("min"), 0);
        assertEquals(.1, k.getJSONArray("fields").getJSONObject(0).getDouble("step"), 0);
        assertEquals("2", k.getJSONObject("hidden").getString("kat")); assertEquals("1", p.getJSONObject("hidden").getString("kat"));
        assertEquals(24, k.getJSONArray("defaults").length());
        for (int i = 0; i < 24; i++) assertEquals(50, k.getJSONArray("defaults").getDouble(i), 0);
        assertEquals(0, HeatingParser.parseSettings(fixture("podesavanje.php.html")).getJSONArray("fields").getJSONObject(5).getDouble("value"), 0);
    }

    @Test public void malformedFormFieldsNeverBecomeZeroOrTargetWrongZone() throws Exception {
        String kat = fixture("kat.php.html"), settings = fixture("podesavanje.php.html");
        for (String empty : new String[]{"", "   "}) {
            for (String attribute : new String[]{"value=\"25.50\"", "min=\"18\"", "step=\"0.1\""}) {
                String changed = kat.replace(attribute, attribute.split("=")[0] + "=\"" + empty + "\"");
                assertTrue(failure(() -> HeatingParser.parseSchedule(changed, "kat")).getMessage().contains("neispravne granice"));
            }
            assertTrue(failure(() -> HeatingParser.parseSettings(settings.replace("value=\"22.00\"", "value=\"" + empty + "\""))).getMessage().contains("nepotpun ili neispravan"));
        }
        Document wrong = Jsoup.parse(kat); wrong.selectFirst("input[type=hidden][name=kat]").attr("value", "1");
        assertTrue(failure(() -> HeatingParser.parseSchedule(wrong.html(), "kat")).getMessage().contains("oznaku zone"));
        Document duplicate = Jsoup.parse(kat); duplicate.selectFirst("input[name=n0]").after(duplicate.selectFirst("input[name=n0]").clone());
        failure(() -> HeatingParser.parseSchedule(duplicate.html(), "kat"));
    }

    @Test public void scheduleUsesExactGetFormAndReadAfterWriteVerification() throws Exception {
        String original = fixture("prizemlje.php.html"), updated = scheduleWith(original, 22.1);
        List<URI> sent = new ArrayList<>();
        AtomicInteger writes = new AtomicInteger();
        HeatingRepository repository = new HeatingRepository((uri, headers) -> {
            sent.add(uri);
            if (uri.getPath().endsWith("dodajPODESAVANJA.php")) { writes.incrementAndGet(); return ok("OK"); }
            return ok(writes.get() > 0 ? updated : original);
        });
        JSONObject saved = repository.saveSchedule("prizemlje", values(22.1));
        assertEquals(3, sent.size()); assertEquals(1, writes.get());
        assertEquals("/centralno/php/dodajPODESAVANJA.php", sent.get(1).getPath());
        Map<String, String> query = query(sent.get(1));
        assertEquals(25, query.size()); assertEquals("1", query.get("kat")); assertEquals("22.1", query.get("n23"));
        assertTrue(saved.getBoolean("verified")); assertTrue(saved.getBoolean("ok"));
        assertTrue(saved.getString("message").contains("Sinkronizaciju regulatora pratite"));
    }

    @Test public void invalidOrUnpersistedScheduleIsNeverReportedAsSaved() throws Exception {
        String original = fixture("kat.php.html"); List<URI> sent = new ArrayList<>();
        HeatingRepository repository = new HeatingRepository((uri, headers) -> { sent.add(uri); return ok(original); });
        for (double invalid : new double[]{40, 22.15, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(400, failure(() -> repository.saveSchedule("kat", values(invalid))).status);
        }
        assertEquals(4, sent.size());
        assertFalse(sent.stream().anyMatch(uri -> uri.getPath().contains("dodajPODESAVANJA")));
        HeatingRepository.AppException error = failure(() -> repository.saveSchedule("kat", values(22)));
        assertTrue(error.details.getBoolean("mutationUnconfirmed")); assertTrue(error.details.has("current"));
        assertEquals(404, failure(() -> repository.schedule("other")).status);
    }

    @Test public void settingsRequireAllSixNumbersAndVerifySavedValues() throws Exception {
        JSONObject values = settingsValues(); String original = fixture("podesavanje.php.html"), updated = settingsWith(original, values);
        List<URI> sent = new ArrayList<>(); AtomicInteger writes = new AtomicInteger();
        HeatingRepository repository = new HeatingRepository((uri, headers) -> {
            sent.add(uri); if (uri.getPath().endsWith("zapisi_postavke.php")) { writes.incrementAndGet(); return ok("OK"); }
            return ok(writes.get() > 0 ? updated : original);
        });
        assertEquals(400, failure(() -> repository.saveSettings(new JSONObject(values.toString()).put("n5", 2))).status);
        assertEquals(400, failure(() -> repository.saveSettings(new JSONObject(values.toString()).put("n2", "26"))).status);
        JSONObject missing = new JSONObject(values.toString()); missing.remove("n4");
        assertEquals(400, failure(() -> repository.saveSettings(missing)).status);
        assertTrue(sent.isEmpty());
        assertTrue(repository.saveSettings(values).getBoolean("verified"));
        assertEquals(3, sent.size()); assertEquals(1, writes.get());
        Map<String, String> query = query(sent.get(1)); assertEquals(6, query.size());
        for (int n = 0; n < 6; n++) assertEquals(values.getDouble("n" + n), Double.parseDouble(query.get("n" + n)), 0);
    }

    @Test public void changedFormActionStopsBeforeSendingAnyMutation() throws Exception {
        String original = fixture("kat.php.html");
        for (String action : new String[]{"https://evil.example/centralno/php/dodajPODESAVANJA.php", "zapisi_postavke.php", "http://automatiziraj.se/centralno/php/dodajPODESAVANJA.php"}) {
            Document doc = Jsoup.parse(original); doc.selectFirst("form:has(input[name=n23])").attr("action", action);
            AtomicInteger calls = new AtomicInteger();
            HeatingRepository repository = new HeatingRepository((uri, headers) -> { calls.incrementAndGet(); return ok(doc.html()); });
            failure(() -> repository.saveSchedule("kat", values(22))); assertEquals(1, calls.get());
        }
    }

    @Test public void tlsAllowlistRejectsArbitraryPortsCredentialsFragmentsAndEncodedPaths() throws Exception {
        for (String address : new String[]{"http://automatiziraj.se/centralno/php/novo.php", "https://evil.example/centralno/php/novo.php",
                "https://automatiziraj.se:444/centralno/php/novo.php", "https://user:pass@automatiziraj.se/centralno/php/novo.php",
                "novo.php#anything", "../php/other.php", "%6eovo.php", "//evil.example/centralno/php/novo.php"}) {
            assertEquals(400, failure(() -> HeatingRepository.safeUrl(address)).status);
        }
        assertEquals("/centralno/php/novo.php", HeatingRepository.safeUrl("../php/novo.php").getPath());
        assertEquals(443, HeatingRepository.safeUrl("https://automatiziraj.se:443/centralno/php/novo.php").getPort());
    }

    @Test public void sessionCookiesAreMemoryOnlyAndClearedAfterFailedLogout() throws Exception {
        String homepage = fixture("https-original.html"); AtomicInteger calls = new AtomicInteger();
        HeatingRepository repository = new HeatingRepository((uri, headers) -> {
            int count = calls.incrementAndGet();
            if (count == 1) return new HeatingRepository.HttpResponse(200, Collections.singletonMap("Set-Cookie", Arrays.asList("PHPSESSID=example; Path=/centralno/; HttpOnly", "evil=ignored; Domain=evil.example; Path=/")), homepage);
            if (count == 2 || count == 3) assertEquals("PHPSESSID=example", headers.get("Cookie"));
            if (uri.getPath().contains("logout")) throw new Exception("fixture offline");
            if (count == 4) assertNull(headers.get("Cookie"));
            return ok(homepage);
        });
        repository.status(); repository.status(); failure(repository::logout); repository.status(); assertEquals(4, calls.get());
    }

    @Test public void loginAndUnsafeRedirectsNeverBecomeTemperatureData() throws Exception {
        for (String location : new String[]{"https://evil.example/novo.php", "dodajPODESAVANJA.php", "novo.php#fragment"}) {
            AtomicInteger calls = new AtomicInteger();
            HeatingRepository repository = new HeatingRepository((uri, headers) -> {
                calls.incrementAndGet(); return new HeatingRepository.HttpResponse(302, Collections.singletonMap("Location", Collections.singletonList(location)), "");
            });
            failure(repository::status); assertEquals(1, calls.get());
        }
        HeatingRepository login = new HeatingRepository((uri, headers) -> new HeatingRepository.HttpResponse(302, Collections.singletonMap("Location", Collections.singletonList("../login.php")), ""));
        assertTrue(failure(login::status).details.getBoolean("requiresLogin"));
        assertEquals(401, failure(() -> new HeatingRepository((uri, headers) -> ok("<form action='/prijava'><input type=password></form>")).status()).status);
        assertEquals(401, failure(() -> new HeatingRepository((uri, headers) -> new HeatingRepository.HttpResponse(403, "Forbidden")).status()).status);
    }

    @Test public void interruptedWriteIsUnconfirmedAndNeverReplayed() throws Exception {
        String kat = fixture("kat.php.html"); AtomicInteger writes = new AtomicInteger();
        HeatingRepository timeout = new HeatingRepository((uri, headers) -> {
            if (uri.getPath().endsWith("kat.php")) return ok(kat);
            writes.incrementAndGet(); throw new java.net.SocketTimeoutException("fixture timeout");
        });
        HeatingRepository.AppException error = failure(() -> timeout.saveSchedule("kat", values(22)));
        assertTrue(error.details.getBoolean("mutationUnconfirmed")); assertTrue(error.getMessage().contains("prije ponavljanja osvježite")); assertEquals(1, writes.get());
        AtomicInteger redirectWrites = new AtomicInteger();
        HeatingRepository redirect = new HeatingRepository((uri, headers) -> {
            if (uri.getPath().endsWith("kat.php")) return ok(kat);
            redirectWrites.incrementAndGet(); return new HeatingRepository.HttpResponse(307, Collections.singletonMap("Location", Collections.singletonList("dodajPODESAVANJA.php")), "");
        });
        failure(() -> redirect.saveSchedule("kat", values(22))); assertEquals(1, redirectWrites.get());
    }

    @Test public void verificationReadFailureDoesNotClaimSavedOrRetryWrite() throws Exception {
        String kat = fixture("kat.php.html"); AtomicInteger writes = new AtomicInteger(); AtomicInteger reads = new AtomicInteger();
        HeatingRepository repository = new HeatingRepository((uri, headers) -> {
            if (uri.getPath().endsWith("dodajPODESAVANJA.php")) { writes.incrementAndGet(); return ok("OK"); }
            if (reads.incrementAndGet() == 1) return ok(kat);
            throw new Exception("fixture offline after mutation");
        });
        HeatingRepository.AppException error = failure(() -> repository.saveSchedule("kat", values(22)));
        assertTrue(error.details.getBoolean("mutationUnconfirmed")); assertTrue(error.getMessage().contains("ponovna provjera"));
        assertEquals(1, writes.get()); assertEquals(2, reads.get());
    }

    @Test public void writeRedirectMayOnlyFollowReadPageAndPreservesSessionCookie() throws Exception {
        String kat = fixture("kat.php.html"), updated = scheduleWith(kat, 22);
        AtomicInteger writes = new AtomicInteger(), reads = new AtomicInteger();
        HeatingRepository repository = new HeatingRepository((uri, headers) -> {
            if (uri.getPath().endsWith("dodajPODESAVANJA.php")) {
                writes.incrementAndGet();
                Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
                responseHeaders.put("Location", Collections.singletonList("kat.php"));
                responseHeaders.put("Set-Cookie", Collections.singletonList("PHPSESSID=write-session; Path=/centralno/; HttpOnly"));
                return new HeatingRepository.HttpResponse(303, responseHeaders, "");
            }
            reads.incrementAndGet();
            if (writes.get() > 0) assertEquals("PHPSESSID=write-session", headers.get("Cookie"));
            return ok(writes.get() > 0 ? updated : kat);
        });
        assertTrue(repository.saveSchedule("kat", values(22)).getBoolean("verified"));
        assertEquals(1, writes.get()); assertEquals(3, reads.get());
    }

    @Test public void sourceActionQueryIsRetainedAndValidatedValuesOverrideDuplicates() throws Exception {
        Document doc = Jsoup.parse(fixture("prizemlje.php.html"));
        doc.selectFirst("form:has(input[name=n23])").attr("action", "dodajPODESAVANJA.php?source=raspored&n0=999&kat=2");
        String current = doc.html(), updated = scheduleWith(current, 22.1);
        AtomicInteger writes = new AtomicInteger();
        HeatingRepository repository = new HeatingRepository((uri, headers) -> {
            if (uri.getPath().endsWith("dodajPODESAVANJA.php")) {
                Map<String, String> query = query(uri);
                assertEquals(26, query.size()); assertEquals("raspored", query.get("source"));
                assertEquals("1", query.get("kat")); assertEquals("22.1", query.get("n0"));
                writes.incrementAndGet(); return ok("OK");
            }
            return ok(writes.get() > 0 ? updated : current);
        });
        assertTrue(repository.saveSchedule("prizemlje", values(22.1)).getBoolean("verified")); assertEquals(1, writes.get());
    }

    @Test public void oversizedResponseIsRejectedBeforeParsing() throws Exception {
        HeatingRepository repository = new HeatingRepository((uri, headers) -> ok("x".repeat(5_000_001)));
        assertTrue(failure(repository::status).getMessage().contains("prevelik"));
    }

    @Test public void simultaneousWritesAreRejectedAndLockIsReleasedAfterFailure() throws Exception {
        String kat = fixture("kat.php.html"); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Exception> backgroundError = new AtomicReference<>();
        HeatingRepository repository = new HeatingRepository((uri, headers) -> {
            entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new Exception("fixture deadline"); return ok(kat);
        });
        Thread first = new Thread(() -> { try { repository.saveSchedule("kat", values(40)); } catch (Exception error) { backgroundError.set(error); } });
        first.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
        try { assertEquals(409, failure(() -> repository.saveSettings(settingsValues())).status); }
        finally { release.countDown(); first.join(5000); }
        assertFalse(first.isAlive()); assertTrue(backgroundError.get() instanceof HeatingRepository.AppException);
        assertEquals(400, failure(() -> repository.saveSchedule("kat", values(40))).status);
    }
}
