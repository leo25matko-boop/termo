package se.automatiziraj.termodom;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Native parser regression against original HTML and the existing adapter's data. */
public class ChartParserTest {
    private String resource(String name) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/charts/" + name)) {
            assertNotNull("Missing test fixture " + name, stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test public void preservesEveryOriginalLabelDatasetValueColorAndSteppedFlag() throws Exception {
        JSONArray catalog = ChartParser.catalog();
        assertEquals(10, catalog.length());
        for (int c = 0; c < 8; c++) {
            JSONObject meta = catalog.getJSONObject(c);
            String id = meta.getString("id");
            JSONObject expected = new JSONObject(resource(id + ".expected.json"));
            JSONObject actual = ChartParser.parse(resource(meta.getString("path") + ".html"), id);
            assertEquals(id, actual.getString("id"));
            assertEquals(expected.getString("title"), actual.getString("title"));
            assertEquals(expected.getString("unit"), actual.getString("unit"));
            assertEquals(expected.getString("period"), actual.getString("period"));
            JSONArray labels = actual.getJSONArray("labels");
            JSONArray expectedLabels = expected.getJSONArray("labels");
            assertEquals(id, expectedLabels.length(), labels.length());
            for (int i = 0; i < labels.length(); i++) assertEquals(id + " label " + i, expectedLabels.get(i), labels.get(i));
            JSONArray datasets = actual.getJSONArray("datasets");
            JSONArray expectedDatasets = expected.getJSONArray("datasets");
            assertEquals(id, expectedDatasets.length(), datasets.length());
            for (int s = 0; s < datasets.length(); s++) {
                JSONObject dataset = datasets.getJSONObject(s), expectedDataset = expectedDatasets.getJSONObject(s);
                assertEquals(expectedDataset.getString("label"), dataset.getString("label"));
                assertEquals(expectedDataset.getString("color"), dataset.getString("color"));
                assertEquals(expectedDataset.getBoolean("stepped"), dataset.getBoolean("stepped"));
                JSONArray data = dataset.getJSONArray("data"), expectedData = expectedDataset.getJSONArray("data");
                assertEquals(id + " dataset " + s, expectedData.length(), data.length());
                for (int i = 0; i < data.length(); i++) {
                    assertEquals(id + " null at " + i, expectedData.isNull(i), data.isNull(i));
                    if (!data.isNull(i)) assertEquals(id + " value " + s + "/" + i, expectedData.getDouble(i), data.getDouble(i), 0);
                }
            }
        }
    }

    @Test public void unavailableOriginalTuyaPagesStayUnavailable() throws Exception {
        for (String id : Arrays.asList("tuya-1h", "tuya-6h")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ChartParser.parse(resource(ChartParser.pathFor(id) + ".html"), id));
            assertTrue(error.getMessage(), error.getMessage().contains("Izvor nije vratio podatke grafa"));
        }
    }

    @Test public void emptyOriginalDatasetsAreNotReplacedWithSamples() throws Exception {
        for (String id : Arrays.asList("tuya", "voltage")) {
            JSONObject chart = ChartParser.parse(resource(ChartParser.pathFor(id) + ".html"), id);
            assertEquals(0, chart.getJSONArray("labels").length());
            JSONArray datasets = chart.getJSONArray("datasets");
            for (int i = 0; i < datasets.length(); i++) assertEquals(0, datasets.getJSONObject(i).getJSONArray("data").length());
        }
    }

    @Test public void permitsOnlyLiteralArithmeticBindingsLengthAndFill() throws Exception {
        JSONObject chart = ChartParser.parse("<script>\n" +
            "const limit = 250 + 1; var dataA = [240, '252', -.5, null];\n" +
            "var labels = ['a', 'b', 'c', 'd'];\n" +
            "new Chart(ctx, {data:{labels:labels,datasets:[\n" +
            "{label:'Faza A',data:dataA,borderColor:'#4caf50'},\n" +
            "{label:'ALARM 250V',data:Array(dataA.length).fill(limit),borderColor:'red',steppedLine:true}\n" +
            "]},options:{animation:{onComplete:function(){throw 'MUST NEVER EXECUTE';}}}});\n" +
            "</script>", "voltage");
        JSONArray data = chart.getJSONArray("datasets").getJSONObject(0).getJSONArray("data");
        assertEquals(240, data.getDouble(0), 0);
        assertEquals(252, data.getDouble(1), 0);
        assertEquals(-.5, data.getDouble(2), 0);
        assertTrue(data.isNull(3));
        JSONObject alarm = chart.getJSONArray("datasets").getJSONObject(1);
        assertEquals("ALARM 250V", alarm.getString("label"));
        for (int i = 0; i < 4; i++) assertEquals(251, alarm.getJSONArray("data").getDouble(i), 0);
        assertTrue(alarm.getBoolean("stepped"));
        assertEquals(251, chart.getInt("alarmLimit"));
        assertEquals(10, chart.getInt("spikeThreshold"));
    }

    @Test public void handlesCommentsEscapesTrailingCommasAndSteppedModes() throws Exception {
        JSONObject chart = ChartParser.parse("<script>const values=new Array(2 * 2).fill(3/2);\n" +
            "new Chart(ctx,{data:{labels:['a\\x31','b\\u0032','c','d',],datasets:[/* native */\n" +
            "{label:'line\\nname',data:values,borderColor:'rgba(255,99,132)',stepped:'middle'},\n" +
            "{label:'gaps',data:[1,null,3,4],stepped:'after'}\n" +
            "]}});</script>", "kat");
        assertEquals("a1", chart.getJSONArray("labels").getString(0));
        assertEquals("b2", chart.getJSONArray("labels").getString(1));
        JSONObject series = chart.getJSONArray("datasets").getJSONObject(0);
        assertEquals("line\nname", series.getString("label"));
        assertEquals("middle", series.getString("steppedMode"));
        assertEquals("rgba(255,99,132)", series.getString("color"));
        assertEquals(1.5, series.getJSONArray("data").getDouble(3), 0);
        assertTrue(chart.getJSONArray("datasets").getJSONObject(1).getJSONArray("data").isNull(1));
    }

    @Test public void arbitraryCodeFunctionCallsAndUnboundedArraysAreRejected() {
        for (String unsafe : Arrays.asList(
            "globalThis.DO_NOT_RUN = true", "Array.constructor('throw new Error()')()",
            "Array(1e10).fill(251)", "[1].map(() => 99)", "Object.assign({}, {a:1})",
            "fetch('https://example.invalid/')", "[Infinity]", "[NaN]", "[1/0]",
            "[`template ${unknown}`]", "['1junk']", "[{}]", "[true]")) {
            assertThrows("Must reject " + unsafe, IllegalArgumentException.class,
                () -> ChartParser.parse("<script>new Chart(ctx,{data:{labels:['a'],datasets:[{label:'bad',data:" + unsafe + "}]}});</script>", "kat"));
        }
    }

    @Test public void rejectsMalformedOversizedOrDeepInputAndUnknownCharts() {
        assertThrows(IllegalArgumentException.class, () -> ChartParser.parse("", "unknown"));
        assertThrows(IllegalArgumentException.class, () -> ChartParser.pathFor("../../other"));
        assertThrows(IllegalArgumentException.class, () -> ChartParser.parse("<html>Not a chart</html>", "kat"));
        assertThrows(IllegalArgumentException.class, () -> ChartParser.parse(null, "kat"));
        assertThrows(IllegalArgumentException.class, () -> ChartParser.parse("x".repeat(4_000_001), "kat"));
        String nested = "[".repeat(110) + "1" + "]".repeat(110);
        assertThrows(IllegalArgumentException.class, () -> ChartParser.parse("<script>new Chart(ctx,{data:{labels:['a'],datasets:[{label:'bad',data:" + nested + "}]}});</script>", "kat"));
    }
}
