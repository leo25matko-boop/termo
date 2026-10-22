using System.Globalization;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;

namespace TermoDom;

/// <summary>Native WPF vector chart. No browser, chart script, HTML surface or web runtime.</summary>
public sealed class NativeChart : FrameworkElement
{
    public ChartData? Data { get; set; }
    public HashSet<int> Hidden { get; } = [];
    public int Hours { get; set; } = 24;
    int selected = -1;
    public NativeChart()
    {
        MinHeight = 300; Height = 330; Focusable = true; ClipToBounds = true;
        MouseMove += (_, e) => { selected = IndexAt(e.GetPosition(this).X); InvalidateVisual(); };
        MouseLeave += (_, _) => { selected = -1; InvalidateVisual(); };
        KeyDown += (_, e) => { if (e.Key is Key.Left or Key.Right) { selected = Math.Clamp(selected + (e.Key == Key.Right ? 1 : -1), Start(), (Data?.Labels.Count ?? 1) - 1); InvalidateVisual(); } };
    }
    static Brush Brush(string color) { try { return new SolidColorBrush((Color)ColorConverter.ConvertFromString(color)); } catch { return Brushes.CadetBlue; } }
    static FormattedText Label(string text, double size, Brush color) => new(text, CultureInfo.GetCultureInfo("hr-HR"), FlowDirection.LeftToRight, new Typeface("Segoe UI"), size, color, 1);
    int Start() => Data == null ? 0 : Math.Max(0, Data.Labels.Count - (int)Math.Ceiling(Data.Labels.Count * Math.Min(Hours, Data.Meta.Hours) / (double)Data.Meta.Hours));
    int IndexAt(double x)
    {
        if (Data == null || Data.Labels.Count == 0) return -1;
        return Math.Clamp(Start() + (int)Math.Round((x - 54) / Math.Max(1, ActualWidth - 78) * (Data.Labels.Count - Start() - 1)), Start(), Data.Labels.Count - 1);
    }
    protected override void OnRender(DrawingContext dc)
    {
        base.OnRender(dc); var bg = Brush("#14212C"); var subdued = Brush("#99ACB7"); var grid = new Pen(Brush("#2B3C48"), 1);
        dc.DrawRoundedRectangle(bg, null, new Rect(0, 0, ActualWidth, ActualHeight), 12, 12);
        if (Data == null || Data.Labels.Count == 0) { dc.DrawText(Label("Nema dostupnih podataka grafa.", 14, subdued), new Point(24, 35)); return; }
        var start = Start(); var end = Data.Labels.Count - 1;
        var visible = Data.Series.Select((s, i) => (s, i)).Where(x => !Hidden.Contains(x.i)).ToArray();
        var numbers = visible.SelectMany(x => x.s.Values.Skip(start)).Where(v => v.HasValue).Select(v => v!.Value).ToList();
        if (numbers.Count == 0) { dc.DrawText(Label("Nema vrijednosti u odabranom razdoblju.", 14, subdued), new Point(24, 35)); return; }
        var min = numbers.Min(); var max = numbers.Max();
        if (Data.Meta.Id == "voltage") { min = Math.Min(min, 251); max = Math.Max(max, 251); }
        var pad = Math.Max((max - min) * 0.12, 0.5); min -= pad; max += pad;
        var left = 54d; var top = 22d; var width = Math.Max(1, ActualWidth - 78); var height = Math.Max(1, ActualHeight - 68);
        double X(int i) => left + (i - start) * width / Math.Max(1, end - start);
        double Y(double v) => top + (max - v) * height / (max - min);
        for (var row = 0; row <= 4; row++)
        {
            var v = min + (max - min) * row / 4; var y = Y(v);
            dc.DrawLine(grid, new Point(left, y), new Point(left + width, y));
            dc.DrawText(Label(v.ToString("0.#", CultureInfo.GetCultureInfo("hr-HR")), 11, subdued), new Point(6, y - 7));
        }
        for (var column = 0; column <= 4; column++)
        {
            var index = start + (int)Math.Round((end - start) * column / 4d);
            var label = Label(Data.Labels[index], 10, subdued);
            dc.DrawText(label, new Point(Math.Clamp(X(index) - label.Width / 2, left - 5, Math.Max(left, ActualWidth - label.Width - 4)), top + height + 12));
        }
        if (Data.Meta.Id == "voltage")
        {
            var alarm = new Pen(Brush("#D98C91"), 1) { DashStyle = DashStyles.Dash };
            dc.DrawLine(alarm, new Point(left, Y(251)), new Point(left + width, Y(251)));
            dc.DrawText(Label("Granica 251 V", 11, Brush("#D98C91")), new Point(left + 6, Math.Clamp(Y(251) - 17, top, top + height - 18)));
        }
        foreach (var (series, _) in visible)
        {
            var color = Brush(series.Color); var pen = new Pen(color, 2.2) { LineJoin = PenLineJoin.Round };
            Point? previous = null;
            for (var i = start; i <= end && i < series.Values.Count; i++)
            {
                if (series.Values[i] is not double value) { previous = null; continue; }
                var current = new Point(X(i), Y(value));
                if (previous is Point p)
                {
                    if (!series.Stepped) dc.DrawLine(pen, p, current);
                    else if (series.SteppedMode == "after") { dc.DrawLine(pen, p, new Point(p.X, current.Y)); dc.DrawLine(pen, new Point(p.X, current.Y), current); }
                    else if (series.SteppedMode == "middle") { var middle = (p.X + current.X) / 2; dc.DrawLine(pen, p, new Point(middle, p.Y)); dc.DrawLine(pen, new Point(middle, p.Y), new Point(middle, current.Y)); dc.DrawLine(pen, new Point(middle, current.Y), current); }
                    else { dc.DrawLine(pen, p, new Point(current.X, p.Y)); dc.DrawLine(pen, new Point(current.X, p.Y), current); }
                    if (Data.Meta.Id == "voltage" && i > 0 && series.Values[i - 1] is double before && Math.Abs(value - before) > 10) dc.DrawEllipse(color, null, current, 4, 4);
                }
                previous = current;
            }
        }
        if (selected >= start && selected <= end)
        {
            dc.DrawLine(new Pen(Brush("#93D9CC"), 1) { DashStyle = DashStyles.Dot }, new Point(X(selected), top), new Point(X(selected), top + height));
            var lines = new List<string> { Data.Labels[selected] };
            lines.AddRange(visible.Select(x => x.s.Label + ": " + (selected < x.s.Values.Count && x.s.Values[selected] is double value ? value.ToString("0.##", CultureInfo.GetCultureInfo("hr-HR")) + " " + Data.Meta.Unit : "—")));
            var text = Label(string.Join('\n', lines), 12, Brush("#EDF3F5"));
            var x = Math.Clamp(X(selected) + 12, left, Math.Max(left, ActualWidth - text.Width - 32));
            dc.DrawRoundedRectangle(Brush("#263D49"), new Pen(Brush("#426271"), 1), new Rect(x, top + 4, text.Width + 22, text.Height + 18), 8, 8);
            dc.DrawText(text, new Point(x + 11, top + 13));
        }
    }
}
