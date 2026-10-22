package se.automatiziraj.termodom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android Canvas chart: no HTML, JavaScript engine, browser, or web renderer. */
public final class NativeChartView extends View {
    private static final int[] COLORS = {
        0xff759a61, 0xffd69c6e, 0xff7fa8b2, 0xffa696bd, 0xffb6ac5d, 0xffbd7c80
    };
    private static final int INK = 0xff314d43;
    private static final int MUTED = 0xff7c9186;
    private static final int GRID = 0xffe7eee7;
    private static final int ALARM = 0xffbc5548;
    private static final Pattern RGB = Pattern.compile("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)(?:\\s*,\\s*([\\d.]+))?\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME = Pattern.compile("\\d{2}:\\d{2}");
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF plot = new RectF();
    private final List<String> labels = new ArrayList<>();
    private final List<Series> series = new ArrayList<>();
    private Set<Integer> hidden = Collections.emptySet();
    private String title = "", unit = "°C", id = "";
    private int sourceHours = 24, periodHours = 24, start, selected = -1;
    private double minimum, maximum;
    private boolean hasData, alarm;
    private final float density;
    private final float textScale;

    private static final class Series {
        final String label, steppedMode;
        final double[] values;
        final int color;
        final boolean stepped, phase;
        Series(JSONObject json, int index) {
            label = json.optString("label", "Serija " + (index + 1));
            color = colorFor(json, index);
            stepped = json.optBoolean("stepped", false);
            steppedMode = json.optString("steppedMode", "before");
            phase = label.toLowerCase(Locale.ROOT).startsWith("faza");
            JSONArray data = json.optJSONArray("data");
            values = new double[data == null ? 0 : data.length()];
            for (int i = 0; i < values.length; i++) {
                Object value = data.opt(i);
                values[i] = value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
            }
        }
        double at(int index) { return index >= 0 && index < values.length ? values[index] : Double.NaN; }
    }

    public NativeChartView(Context context) { this(context, null); }
    public NativeChartView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public NativeChartView(Context context, AttributeSet attrs, int style) {
        super(context, attrs, style);
        density = getResources().getDisplayMetrics().density;
        textScale = getResources().getDisplayMetrics().scaledDensity;
        setMinimumHeight((int) dp(240));
        setFocusable(true);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("Grafikon. Dohvaćanje podataka.");
    }

    /** Source is copied into native values; callers may retain their JSONObject. */
    public void setChart(JSONObject chart) {
        labels.clear();
        series.clear();
        selected = -1;
        title = chart == null ? "" : chart.optString("title", "");
        id = chart == null ? "" : chart.optString("id", "");
        unit = chart == null ? "°C" : chart.optString("unit", "°C");
        String sourcePeriod = chart == null ? "24h" : chart.optString("period", "24h");
        sourceHours = "1h".equals(sourcePeriod) ? 1 : "6h".equals(sourcePeriod) ? 6 : 24;
        JSONArray rawLabels = chart == null ? null : chart.optJSONArray("labels");
        if (rawLabels != null) for (int i = 0; i < rawLabels.length(); i++) labels.add(String.valueOf(rawLabels.opt(i)));
        JSONArray rawSeries = chart == null ? null : chart.optJSONArray("datasets");
        if (rawSeries != null) for (int i = 0; i < rawSeries.length(); i++) {
            JSONObject dataset = rawSeries.optJSONObject(i);
            if (dataset != null) series.add(new Series(dataset, i));
        }
        recalculate();
    }

    public void setPeriodHours(int hours) {
        periodHours = hours <= 0 ? 24 : hours;
        selected = -1;
        recalculate();
    }

    public void setHiddenSeries(Set<Integer> seriesIndices) {
        hidden = seriesIndices == null ? Collections.emptySet() : new HashSet<>(seriesIndices);
        recalculate();
    }

    /** Used by native legend buttons, so series and legend always match. */
    public static int colorFor(JSONObject dataset, int index) {
        String raw = dataset == null ? "" : dataset.optString("color", "");
        Matcher rgb = RGB.matcher(raw);
        if (rgb.matches()) {
            try {
                int r = Integer.parseInt(rgb.group(1)), g = Integer.parseInt(rgb.group(2)), b = Integer.parseInt(rgb.group(3));
                int a = rgb.group(4) == null ? 255 : Math.round(Float.parseFloat(rgb.group(4)) * 255);
                if (r <= 255 && g <= 255 && b <= 255 && a >= 0 && a <= 255) return Color.argb(a, r, g, b);
            } catch (NumberFormatException ignored) { }
        }
        try {
            if (raw.matches("#[a-fA-F0-9]{3}")) raw = "#" + raw.charAt(1) + raw.charAt(1) + raw.charAt(2) + raw.charAt(2) + raw.charAt(3) + raw.charAt(3);
            if (raw.matches("#[a-fA-F0-9]{8}")) {
                // CSS is #RRGGBBAA, while Android's parseColor expects #AARRGGBB.
                return Color.argb(Integer.parseInt(raw.substring(7, 9), 16), Integer.parseInt(raw.substring(1, 3), 16),
                    Integer.parseInt(raw.substring(3, 5), 16), Integer.parseInt(raw.substring(5, 7), 16));
            }
            if ("orange".equalsIgnoreCase(raw)) return 0xffffa500;
            if (raw.matches("#[a-fA-F0-9]{6}") ||
                raw.matches("(?i)red|green|blue|yellow|orange|cyan|magenta|black|white|gray|grey|transparent")) return Color.parseColor(raw);
        } catch (IllegalArgumentException ignored) { }
        return COLORS[Math.floorMod(index, COLORS.length)];
    }

    private float dp(float value) { return value * density; }
    private float sp(float value) { return value * textScale; }

    private static long timestamp(String label) {
        String iso = label.trim().replace(' ', 'T');
        try { return OffsetDateTime.parse(iso).toInstant().toEpochMilli(); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC).toEpochMilli(); }
        catch (DateTimeParseException ignored) { return Long.MIN_VALUE; }
    }

    private void recalculate() {
        start = 0;
        if (!labels.isEmpty() && periodHours < sourceHours) {
            long last = timestamp(labels.get(labels.size() - 1));
            if (last != Long.MIN_VALUE) {
                long cutoff = last - periodHours * 3_600_000L;
                while (start < labels.size() - 1 && timestamp(labels.get(start)) < cutoff) start++;
            } else start = Math.max(0, labels.size() - (int) Math.ceil(labels.size() * (double) periodHours / sourceHours));
        }
        minimum = Double.POSITIVE_INFINITY;
        maximum = Double.NEGATIVE_INFINITY;
        alarm = false;
        for (int s = 0; s < series.size(); s++) {
            if (hidden.contains(s)) continue;
            Series data = series.get(s);
            for (int i = start; i < labels.size(); i++) {
                double value = data.at(i);
                if (!Double.isFinite(value)) continue;
                minimum = Math.min(minimum, value);
                maximum = Math.max(maximum, value);
                if ("voltage".equals(id) && data.phase && value > 251) alarm = true;
            }
        }
        hasData = !labels.isEmpty() && Double.isFinite(minimum) && Double.isFinite(maximum);
        if (hasData) {
            double padding = Math.max((maximum - minimum) * .13, .5);
            minimum -= padding;
            maximum += padding;
        }
        updateDescription();
        invalidate();
    }

    private void updateDescription() {
        StringBuilder description = new StringBuilder("Grafikon: ").append(title).append("; ")
            .append(Math.max(0, labels.size() - start)).append(" očitanja. ");
        if (!hasData) description.append(hidden.size() >= series.size() && !series.isEmpty() ? "Sve su serije skrivene." : "Nema dostupnih očitanja.");
        else {
            int point = selected >= start && selected < labels.size() ? selected : labels.size() - 1;
            description.append(labels.get(point)).append(". ");
            for (int i = 0; i < series.size(); i++) {
                if (hidden.contains(i)) continue;
                Series data = series.get(i);
                description.append(data.label).append(": ").append(format(data.at(point))).append(' ').append(unit).append(". ");
            }
            if (alarm) description.append("Upozorenje: napon iznad 251 V.");
            description.append("Povucite prst za pojedinačna očitanja.");
        }
        setContentDescription(description.toString());
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = (int) dp(320) + getPaddingLeft() + getPaddingRight();
        int desiredHeight = Math.max(getSuggestedMinimumHeight(), (int) dp(240)) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
        if (!hasData) {
            paint.setColor(MUTED);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(sp(14));
            String empty = hidden.size() >= series.size() && !series.isEmpty() ? "Odaberite seriju u legendi" : "Nema dostupnih očitanja";
            canvas.drawText(empty, getWidth() / 2f, getHeight() / 2f - dp(6), paint);
            paint.setTextSize(sp(11));
            canvas.drawText("Podaci iz izvornog sustava", getWidth() / 2f, getHeight() / 2f + dp(16), paint);
            return;
        }
        paint.setTextSize(sp(10));
        float axisWidth = Math.max(paint.measureText(format(minimum)), paint.measureText(format(maximum))) + dp(10);
        plot.set(getPaddingLeft() + Math.max(dp(35), axisWidth), getPaddingTop() + dp(alarm ? 28 : 18),
            getWidth() - getPaddingRight() - dp(12), getHeight() - getPaddingBottom() - dp(28));
        if (plot.width() < dp(20) || plot.height() < dp(20)) return;
        drawGrid(canvas);
        int save = canvas.save();
        canvas.clipRect(plot.left - dp(5), plot.top - dp(5), plot.right + dp(5), plot.bottom + dp(5));
        for (int s = 0; s < series.size(); s++) {
            if (!hidden.contains(s)) drawSeries(canvas, series.get(s), s);
        }
        canvas.restoreToCount(save);
        if (alarm) {
            paint.setColor(ALARM);
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setTextSize(sp(10));
            canvas.drawText("Upozorenje: napon iznad 251 V", plot.right, getPaddingTop() + dp(12), paint);
        }
        if (selected >= start && selected < labels.size()) drawSelection(canvas);
    }

    private void drawGrid(Canvas canvas) {
        paint.setStrokeWidth(dp(1));
        paint.setTextSize(sp(10));
        for (int i = 0; i < 5; i++) {
            float y = plot.top + plot.height() * i / 4;
            paint.setColor(GRID);
            canvas.drawLine(plot.left, y, plot.right, y, paint);
            paint.setColor(MUTED);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(format(maximum - (maximum - minimum) * i / 4), plot.left - dp(7), y + sp(3), paint);
        }
        int count = Math.min(4, labels.size() - start);
        paint.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < count; i++) {
            int point = start + (count == 1 ? 0 : Math.round((labels.size() - 1 - start) * (float) i / (count - 1)));
            String label = labels.get(point);
            Matcher match = TIME.matcher(label);
            String display = match.find() ? match.group() : label.length() > 9 ? label.substring(label.length() - 9) : label;
            paint.setTextAlign(i == 0 ? Paint.Align.LEFT : i == count - 1 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            canvas.drawText(display, x(point), plot.bottom + dp(18), paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(sp(10));
        canvas.drawText(unit, plot.left, getPaddingTop() + dp(10), paint);
    }

    private float x(int index) {
        int count = labels.size() - start;
        return count <= 1 ? plot.centerX() : plot.left + (index - start) * plot.width() / (count - 1);
    }
    private float y(double value) { return plot.bottom - (float) ((value - minimum) / (maximum - minimum)) * plot.height(); }

    private void drawSeries(Canvas canvas, Series data, int index) {
        Path path = new Path();
        boolean active = false;
        float previousX = 0, previousY = 0;
        for (int i = start; i < labels.size(); i++) {
            double value = data.at(i);
            if (!Double.isFinite(value)) { active = false; continue; }
            float x = x(i), y = y(value);
            if (!active) path.moveTo(x, y);
            else if (data.stepped) {
                if ("after".equals(data.steppedMode)) {
                    path.lineTo(previousX, y);
                } else if ("middle".equals(data.steppedMode)) {
                    float middle = (previousX + x) / 2;
                    path.lineTo(middle, previousY);
                    path.lineTo(middle, y);
                } else path.lineTo(x, previousY);
                path.lineTo(x, y);
            } else path.lineTo(x, y);
            active = true;
            previousX = x;
            previousY = y;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(index == 0 ? 2.5f : 1.8f));
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(data.color);
        paint.setPathEffect(data.stepped ? new DashPathEffect(new float[]{dp(5), dp(3)}, 0) : null);
        canvas.drawPath(path, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
        // A moveTo-only path is invisible: retain isolated readings between gaps.
        for (int i = start; i < labels.size(); i++) {
            if (Double.isFinite(data.at(i)) && (i == start || !Double.isFinite(data.at(i - 1))) &&
                (i == labels.size() - 1 || !Double.isFinite(data.at(i + 1)))) {
                canvas.drawCircle(x(i), y(data.at(i)), dp(3), paint);
            }
        }
        if ("voltage".equals(id) && data.phase) {
            for (int i = start; i < labels.size(); i++) {
                double value = data.at(i);
                if (!Double.isFinite(value)) continue;
                paint.setColor(value > 251 ? ALARM : data.color);
                float x = x(i), y = y(value);
                if (i > 0 && Double.isFinite(data.at(i - 1)) && Math.abs(value - data.at(i - 1)) > 10) {
                    Path diamond = new Path();
                    diamond.moveTo(x, y - dp(4)); diamond.lineTo(x + dp(4), y);
                    diamond.lineTo(x, y + dp(4)); diamond.lineTo(x - dp(4), y); diamond.close();
                    canvas.drawPath(diamond, paint);
                } else if (value > 251 || labels.size() - start < 500) canvas.drawCircle(x, y, dp(2), paint);
            }
        }
    }

    private void drawSelection(Canvas canvas) {
        float selectedX = x(selected);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0xff91a695);
        paint.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(3)}, 0));
        canvas.drawLine(selectedX, plot.top, selectedX, plot.bottom, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
        List<String> rows = new ArrayList<>();
        List<Integer> rowColors = new ArrayList<>();
        for (int s = 0; s < series.size(); s++) {
            if (hidden.contains(s)) continue;
            Series data = series.get(s);
            double value = data.at(selected);
            if (Double.isFinite(value)) {
                paint.setColor(data.color);
                canvas.drawCircle(selectedX, y(value), dp(4), paint);
            }
            rows.add(data.label + ": " + format(value) + " " + unit);
            rowColors.add(data.color);
        }
        paint.setTextSize(sp(11));
        float widest = paint.measureText(labels.get(selected));
        for (String row : rows) widest = Math.max(widest, paint.measureText(row));
        float width = Math.min(getWidth() - dp(20), widest + dp(28));
        float line = sp(17);
        float height = line * (rows.size() + 1) + dp(18);
        float left = Math.min(Math.max(dp(8), selectedX - width / 2), getWidth() - dp(8) - width);
        float top = plot.top + dp(5);
        RectF box = new RectF(left, top, left + width, top + height);
        paint.setColor(0xf5314d43);
        canvas.drawRoundRect(box, dp(10), dp(10), paint);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(ellipsize(labels.get(selected), width - dp(20)), left + dp(10), top + dp(13), paint);
        for (int i = 0; i < rows.size(); i++) {
            paint.setColor(rowColors.get(i));
            canvas.drawCircle(left + dp(11), top + dp(13) + line * (i + 1) - sp(3), dp(2.5f), paint);
            paint.setColor(Color.WHITE);
            canvas.drawText(ellipsize(rows.get(i), width - dp(30)), left + dp(19), top + dp(13) + line * (i + 1), paint);
        }
    }

    private String ellipsize(String value, float width) {
        if (paint.measureText(value) <= width) return value;
        int length = paint.breakText(value, true, Math.max(0, width - paint.measureText("…")), null);
        if (length > 0 && Character.isHighSurrogate(value.charAt(length - 1))) length--;
        return value.substring(0, length) + "…";
    }

    private static String format(double value) {
        if (!Double.isFinite(value)) return "—";
        return String.format(Locale.forLanguageTag("hr-HR"), Math.abs(value) >= 100 ? "%.0f" : "%.1f", value);
    }

    private void selectPoint(float touchX) {
        if (!hasData || plot.width() <= 0) return;
        int point = start + Math.round((touchX - plot.left) / plot.width() * Math.max(0, labels.size() - 1 - start));
        point = Math.max(start, Math.min(labels.size() - 1, point));
        if (point != selected) {
            selected = point;
            updateDescription();
            invalidate();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!hasData) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (event.getY() < plot.top || event.getY() > plot.bottom + dp(20)) return false;
                ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                selectPoint(event.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                selectPoint(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
                selectPoint(event.getX());
                performClick();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                selected = -1;
                updateDescription();
                invalidate();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default: return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        if (selected < start && hasData) {
            selected = labels.size() - 1;
            updateDescription();
            invalidate();
        }
        return true;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (hasData && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (selected < start) selected = labels.size() - 1;
            selected = Math.max(start, Math.min(labels.size() - 1, selected + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
            updateDescription();
            invalidate();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
