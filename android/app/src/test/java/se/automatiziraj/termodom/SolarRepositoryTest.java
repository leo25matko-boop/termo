package se.automatiziraj.termodom;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** Synthetic kiosk responses only. Tests never access or publish a user's plant. */
public final class SolarRepositoryTest {
    private static JSONObject sample() throws Exception {
        return new JSONObject()
                .put("stationOverview", new JSONObject().put("stationName", "Kuća & vrt"))
                .put("realKpi", new JSONObject().put("realTimePower", 0).put("dailyEnergy", 14.17)
                        .put("monthEnergy", 945.81).put("yearEnergy", 17399.06).put("cumulativeEnergy", 25922.32))
                .put("socialContribution", new JSONObject().put("co2Reduction", 12313.10)
                        .put("standardCoalSavings", 10368.93).put("equivalentTreePlanting", 17))
                .put("powerCurve", new JSONObject().put("xAxis", new JSONArray().put("00:00").put("00:05").put("00:10"))
                        .put("activePower", new JSONArray().put("-").put("0.00").put("1.24")));
    }

    private static String envelope(JSONObject data, boolean htmlEncoded) throws Exception {
        String encoded = data.toString();
        if (htmlEncoded) encoded = encoded.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("'", "&#39;").replace("<", "&lt;").replace(">", "&gt;");
        return new JSONObject().put("success", true).put("data", encoded).toString();
    }

    private interface Operation { void run() throws Exception; }
    private static void rejects(Operation operation) throws Exception {
        try { operation.run(); fail("Expected invalid kiosk response to fail."); }
        catch (AssertionError error) { throw error; }
        catch (Exception expected) { assertNotNull(expected.getMessage()); }
    }

    @Test public void parsesProviderEnvelopeAndRetainsAllAvailableMetricPrecision() throws Exception {
        JSONObject result = SolarRepository.parse(envelope(sample(), true));
        assertEquals("Kuća & vrt", result.getString("stationName"));
        assertEquals(0, result.getDouble("powerKw"), 0);
        assertEquals(14.17, result.getDouble("dailyKwh"), 0);
        assertEquals(945.81, result.getDouble("monthKwh"), 0);
        assertEquals(17399.06, result.getDouble("yearKwh"), 0);
        assertEquals(25922.32, result.getDouble("totalKwh"), 0);
        assertEquals(12313.10, result.getDouble("co2Kg"), 0);
        assertEquals(10368.93, result.getDouble("coalKg"), 0);
        assertEquals(17, result.getDouble("trees"), 0);
    }

    @Test public void acceptsUnescapedJsonWithoutChangingText() throws Exception {
        JSONObject result = SolarRepository.parse(envelope(sample(), false));
        assertEquals("Kuća & vrt", result.getString("stationName"));
        assertTrue(result.getString("fetchedAt").endsWith("Z"));
    }

    @Test public void unknownReadingsRemainUnknownWhileRealZeroStaysZero() throws Exception {
        JSONObject data = sample();
        data.getJSONObject("realKpi").remove("dailyEnergy");
        data.getJSONObject("realKpi").put("monthEnergy", "--");
        data.getJSONObject("socialContribution").put("co2Reduction", JSONObject.NULL);
        JSONObject result = SolarRepository.parse(envelope(data, true));
        assertEquals(0, result.getDouble("powerKw"), 0);
        assertTrue(result.isNull("dailyKwh"));
        assertTrue(result.isNull("monthKwh"));
        assertTrue(result.isNull("co2Kg"));
    }

    @Test public void invalidFiniteAndNegativeNumbersNeverBecomeMeasurements() throws Exception {
        for (Object invalid : new Object[]{"NaN", "Infinity", "1e999", "-0.01", -2, "", "   "}) {
            JSONObject data = sample();
            data.getJSONObject("realKpi").put("realTimePower", invalid).put("cumulativeEnergy", invalid);
            JSONObject result = SolarRepository.parse(envelope(data, false));
            assertTrue("Invalid power: " + invalid, result.isNull("powerKw"));
            assertTrue("Invalid energy: " + invalid, result.isNull("totalKwh"));
        }
    }

    @Test public void nativeChartPreservesNullGapsAndValidZero() throws Exception {
        JSONObject chart = SolarRepository.parse(envelope(sample(), true)).getJSONObject("chart");
        assertEquals(3, chart.getJSONArray("labels").length());
        assertEquals("00:05", chart.getJSONArray("labels").getString(1));
        JSONArray data = chart.getJSONArray("datasets").getJSONObject(0).getJSONArray("data");
        assertEquals(3, data.length());
        assertTrue(data.isNull(0));
        assertEquals(0, data.getDouble(1), 0);
        assertEquals(1.24, data.getDouble(2), 0);
    }

    @Test public void failedExpiredOrMalformedResponsesFailWithoutInventingReadings() throws Exception {
        for (String invalid : new String[]{"", "<html>Sign in</html>", "{}", "null",
                "{\"success\":false,\"data\":\"expired\"}",
                "{\"success\":true,\"data\":\"not JSON\"}"}) {
            rejects(() -> SolarRepository.parse(invalid));
        }
    }

    @Test public void acceptsOnlyHttpsHuaweiKioskLinksWithoutCredentialsOrExtraPorts() {
        String suffix = "/pvmswebsite/nologin/assets/build/cloud.html#/kiosk?kk=syntheticTestToken123";
        assertTrue(SolarRepository.validUrl("https://uni005eu5.fusionsolar.huawei.com" + suffix));
        for (String invalid : new String[]{
                "http://uni005eu5.fusionsolar.huawei.com" + suffix,
                "https://uni005eu5.fusionsolar.huawei.com.evil.example" + suffix,
                "https://evil.example" + suffix,
                "https://user:password@uni005eu5.fusionsolar.huawei.com" + suffix,
                "https://uni005eu5.fusionsolar.huawei.com:8443" + suffix,
                "https://uni005eu5.fusionsolar.huawei.com/admin#/kiosk?kk=syntheticTestToken123",
                "https://uni005eu5.fusionsolar.huawei.com/pvmswebsite/nologin/assets/build/cloud.html#/login",
                "javascript:alert(1)", "", "   "}) {
            assertFalse("Unexpected accepted URL: " + invalid, SolarRepository.validUrl(invalid));
        }
    }
}
