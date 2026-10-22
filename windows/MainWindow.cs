using Microsoft.Win32;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace TermoDom;

public sealed class MainWindow : Window
{
    static readonly CultureInfo Hr = CultureInfo.GetCultureInfo("hr-HR");
    static readonly Brush Mint = Color("#79D8C4"), Amber = Color("#E7B469"), Muted = Color("#9FB1BC"), Surface = Color("#192632");
    readonly HeatingRepository repository;
    readonly SolarRepository solarRepository = new();
    readonly bool verification;
    readonly Grid layout = new();
    readonly StackPanel menu = new();
    readonly StackPanel body = new();
    readonly StackPanel footer = new();
    readonly TextBlock notice = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 12) };
    readonly TextBlock freshness = new() { Foreground = Muted, FontSize = 12, Margin = new Thickness(0, 7, 0, 0) };
    readonly ScrollViewer scroll;
    readonly Dictionary<string, Schedule> schedules = [];
    readonly Dictionary<string, double[]> drafts = [];
    readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(90) };
    HeatingStatus? status;
    SolarData? solar;
    SettingsData? settings;
    string[]? settingDraft;
    ChartData? chart;
    string page = "home", heatingTab = "zones", zone = "kat", chartId = "kat";
    int hours = 24;
    bool saving, refreshing;
    DateTime? fetchedAt;
    NativeChart? activeChart;
    readonly List<Slider> scheduleSliders = [];
    CameraPanel? cameraPanel;

    public MainWindow(bool verification = false)
    {
        this.verification = verification;
        repository = verification ? NativeVerification.Repository() : new HeatingRepository();
        Title = "TermoDom · Pametni dom"; Width = 1320; Height = 940; MinWidth = 1000; MinHeight = 720; WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Background = Color("#101923"); Foreground = Color("#EDF3F5"); FontFamily = new FontFamily("Segoe UI"); FontSize = 14;
        layout.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(214) });
        layout.ColumnDefinitions.Add(new ColumnDefinition()); Content = layout;
        BuildMenu();
        var main = new DockPanel { Margin = new Thickness(30, 20, 30, 18) }; Grid.SetColumn(main, 1); layout.Children.Add(main);
        var header = new DockPanel { Margin = new Thickness(0, 0, 0, 10) }; DockPanel.SetDock(header, Dock.Top); main.Children.Add(header);
        var refresh = Button("↻  Osvježi", async () => await RefreshCurrent(), "refresh"); DockPanel.SetDock(refresh, Dock.Right); header.Children.Add(refresh);
        var identity = new StackPanel(); identity.Children.Add(T("VAŠ DOM, NA JEDNOM MJESTU", 11, Mint, true));
        identity.Children.Add(T(DateTime.Now.ToString("dddd, d. MMMM yyyy.", Hr), 15)); identity.Children.Add(freshness); header.Children.Add(identity);
        DockPanel.SetDock(footer, Dock.Bottom); main.Children.Add(footer);
        DockPanel.SetDock(notice, Dock.Top); main.Children.Add(notice);
        scroll = new ScrollViewer { Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, Padding = new Thickness(0, 0, 10, 0) };
        main.Children.Add(scroll);
        Loaded += async (_, _) => { if (!verification) { await LoadStatus(); timer.Start(); } };
        timer.Tick += async (_, _) => { if (IsActive && !saving) { await LoadStatus(false); if (page == "solar" && solarRepository.SharedUrl != null) await LoadSolar(false); } };
        Activated += async (_, _) => { if (!verification && fetchedAt.HasValue && DateTime.Now - fetchedAt.Value > TimeSpan.FromSeconds(90) && !saving) await LoadStatus(false); };
        Closing += (_, e) =>
        {
            if (saving) { e.Cancel = true; return; }
            if (!verification && IsDirty() && MessageBox.Show(this, "Postoje nespremljene promjene. Zatvoriti aplikaciju i odbaciti ih?", "TermoDom", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) e.Cancel = true;
            if (!e.Cancel) timer.Stop();
        };
        Closed += async (_, _) => { if (cameraPanel != null) try { await cameraPanel.DisposeAsync(); } catch { } };
        Render();
    }
    static Brush Color(string hex) => new SolidColorBrush((System.Windows.Media.Color)ColorConverter.ConvertFromString(hex));
    static TextBlock T(string text, double size = 14, Brush? color = null, bool bold = false) => new() { Text = text, FontSize = size, Foreground = color ?? Color("#EDF3F5"), FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 6) };
    static string N(double? value, string unit = "°C") => value.HasValue ? value.Value.ToString("0.#", Hr) + (unit.Length != 0 ? " " + unit : "") : "—";
    static string Device(bool? value) => value.HasValue ? value.Value ? "Uključeno" : "Isključeno" : "Nije dostupno";
    static Button Button(string text, Func<Task> action, string? id = null, Brush? background = null)
    {
        var button = new Button { Content = text }; if (background != null) button.Background = background;
        if (id != null) { AutomationProperties.SetAutomationId(button, id); AutomationProperties.SetName(button, text); }
        button.Click += async (_, _) => await action(); return button;
    }
    static Button Button(string text, Action action, string? id = null, Brush? background = null) => Button(text, () => { action(); return Task.CompletedTask; }, id, background);
    static Border Card(UIElement content, string? accent = null) => new() { Child = content, Background = Surface, CornerRadius = new CornerRadius(18), Padding = new Thickness(22), Margin = new Thickness(0, 0, 16, 16), BorderThickness = new Thickness(1), BorderBrush = Color(accent ?? "#2B3B47") };
    static StackPanel Stack(params UIElement[] children) { var stack = new StackPanel(); foreach (var child in children) stack.Children.Add(child); return stack; }
    static Border Metric(string name, double? value, string unit = "°C", Brush? accent = null, string? description = null) => Card(Stack(T(name.ToUpper(Hr), 11, Muted, true), T(N(value, unit), 30, accent ?? Mint, true), T(description ?? "Izvorni sustav grijanja", 12, Muted)));
    static UniformGrid Tiles(int columns, params UIElement[] children) { var grid = new UniformGrid { Columns = columns }; foreach (var child in children) grid.Children.Add(child); return grid; }
    void Heading(string title, string subtitle) { body.Children.Add(T(title, 32, null, true)); body.Children.Add(T(subtitle, 14, Muted)); body.Children.Add(new Border { Height = 13 }); }
    void BuildMenu()
    {
        var sidebar = new DockPanel { Background = Color("#14202B"), Margin = new Thickness(0) }; layout.Children.Add(sidebar);
        var brand = Stack(T("◉  TermoDom", 24, Mint, true), T("PAMETNI DOM", 10, Muted)); brand.Margin = new Thickness(24, 31, 16, 35); DockPanel.SetDock(brand, Dock.Top); sidebar.Children.Add(brand);
        var meta = Stack(T("Izvorna Windows aplikacija", 11, Muted), T("Verzija 2.0.0", 11, Muted)); meta.Margin = new Thickness(24, 18, 16, 22); DockPanel.SetDock(meta, Dock.Bottom); sidebar.Children.Add(meta);
        menu.Margin = new Thickness(14, 0, 10, 0); sidebar.Children.Add(menu); RenderMenu();
    }
    void RenderMenu()
    {
        menu.Children.Clear();
        foreach (var (id, title) in new[] { ("home", "⌂   Moj dom"), ("heating", "♨   Grijanje"), ("solar", "☀   Solarna elektrana"), ("camera", "◉   Kamera"), ("prepared", "＋   U pripremi") })
        {
            var button = Button(title, () => Navigate(id), "nav-" + id, page == id ? Color("#285248") : Color("#14202B"));
            button.HorizontalContentAlignment = HorizontalAlignment.Left; button.Margin = new Thickness(0, 0, 0, 8); menu.Children.Add(button);
        }
    }
    async Task Navigate(string id)
    {
        if (saving) return; if (page == "camera" && id != "camera" && cameraPanel != null) try { await cameraPanel.Stop(); } catch { }
        page = id; notice.Text = ""; scroll.ScrollToTop(); RenderMenu(); Render();
        if (page == "heating") await LoadHeating();
        else if (page == "solar" && solarRepository.SharedUrl != null && solar == null) await LoadSolar();
    }
    void Render()
    {
        body.Children.Clear(); footer.Children.Clear(); activeChart = null;
        if (page == "home") Home(); else if (page == "heating") Heating(); else if (page == "solar") Solar(); else if (page == "camera") Camera(); else Prepared();
        freshness.Text = fetchedAt.HasValue ? "Grijanje osvježeno u " + fetchedAt.Value.ToString("HH:mm:ss", Hr) : "Čekam podatke izvornog sustava";
    }
    void Home()
    {
        Heading("Dobar dan. Dobro došli kući.", "Pregled grijanja, energije i povezanih uređaja.");
        var overview = Stack(T("CENTRALNO GRIJANJE", 11, Mint, true), T("Ugodan dom. Jasne informacije.", 25, null, true), T("Temperature i stanja prikazani su iz vašeg postojećeg sustava.", 14, Muted));
        overview.Children.Add(new Border { Height = 15 });
        var zoneGrid = new UniformGrid { Columns = 2 };
        foreach (var id in new[] { "kat", "prizemlje" })
        {
            var z = status?.Zones.FirstOrDefault(z => z.Id == id);
            var block = Stack(T(z?.Name ?? (id == "kat" ? "Kat" : "Prizemlje"), 16, Muted), T(N(z?.Temperature), 42, null, true), T("Voda " + N(z?.Water) + "  ·  Cilj " + N(z?.Planned), 13, Muted), T("Pumpa · " + Device(z?.Pump), 12, z?.Pump == true ? Mint : Muted));
            var button = Button("Otvori grijanje  →", async () => { zone = id; heatingTab = "zones"; await Navigate("heating"); }); block.Children.Add(button); zoneGrid.Children.Add(block);
        }
        overview.Children.Add(zoneGrid); body.Children.Add(Card(overview, "#3C655B"));
        body.Children.Add(Tiles(3, Metric("Vanjska temperatura", status?.Outside), Metric("Peć", status?.Boiler, accent: Amber), Metric("Sanitarna voda", status?.Sanitary, accent: Color("#8CAEE8"))));
        var solarCard = Stack(T("☀  SOLARNA ELEKTRANA", 12, Amber, true), T(solar != null ? N(solar.Power, "kW") : "FusionSolar", 26, null, true), T(solar != null ? "Danas " + N(solar.Daily, "kWh") : solarRepository.SharedUrl != null ? "Povezivanje je spremljeno" : "Povežite postojeći kiosk bez API ključa", 13, Muted), Button("Pregled elektrane  →", () => Navigate("solar")));
        var cameraCard = Stack(T("◉  XIAOMI MI 360", 12, Mint, true), T("Vaša kamera", 26, null, true), T("Izvorna Xiaomi Home prijava i dozvole.", 13, Muted), Button("Otvori kameru  →", () => Navigate("camera")));
        body.Children.Add(Tiles(2, Card(solarCard), Card(cameraCard)));
        body.Children.Add(Card(Stack(T("Spremno za vaš dom koji raste", 18, null, true), T("Rasvjeta, dodatni senzori i drugi uređaji ostaju u pripremi dok se ne povežu stvarni izvori.", 13, Muted), Button("Pogledaj pripremu  →", () => Navigate("prepared")))));
    }
    void Heating()
    {
        Heading("Grijanje", "Isti regulator i sve funkcije, u izvornom Windows sučelju.");
        var tabs = new WrapPanel();
        foreach (var (id, title) in new[] { ("zones", "Pregled sustava"), ("schedule", "Rasporedi"), ("charts", "Grafikoni"), ("settings", "Postavke") })
            tabs.Children.Add(Button(title, async () => { heatingTab = id; notice.Text = ""; Render(); await LoadHeating(); }, "heating-" + id, heatingTab == id ? Color("#285248") : null));
        body.Children.Add(tabs); body.Children.Add(new Border { Height = 12 });
        if (heatingTab == "zones") Zones(); else if (heatingTab == "schedule") Schedule(); else if (heatingTab == "charts") Charts(); else Settings();
    }
    void Zones()
    {
        foreach (var z in status?.Zones ?? new[] { new Zone("kat", "Kat", null, null, null, null), new Zone("prizemlje", "Prizemlje", null, null, null, null) })
        {
            var row = new Grid(); row.ColumnDefinitions.Add(new()); row.ColumnDefinitions.Add(new()); row.ColumnDefinitions.Add(new());
            var room = Stack(T(z.Name, 20, null, true), T(N(z.Temperature), 38, Mint, true));
            var water = Stack(T("VODA GRIJANJA", 11, Muted, true), T(N(z.Water), 27, Amber, true), T("Planirano " + N(z.Planned), 13, Muted)); Grid.SetColumn(water, 1);
            var pump = Stack(T("PUMPA", 11, Muted, true), T(Device(z.Pump), 20, z.Pump == true ? Mint : Muted), Button("Uredi raspored", async () => { zone = z.Id; heatingTab = "schedule"; Render(); await LoadHeating(); })); Grid.SetColumn(pump, 2);
            row.Children.Add(room); row.Children.Add(water); row.Children.Add(pump); body.Children.Add(Card(row));
        }
        body.Children.Add(T("Spremnik topline", 20, null, true));
        body.Children.Add(Tiles(3, Metric("Puffer · vrh", status?.BufferTop, accent: Amber), Metric("Puffer · sredina", status?.BufferMiddle), Metric("Puffer · dno", status?.BufferBottom, accent: Color("#8CAEE8"))));
        body.Children.Add(Tiles(2, Card(Stack(T("Peć", 18, null, true), T(N(status?.Boiler), 31, Amber, true), T("Pumpa · " + Device(status?.BoilerPump), 13, Muted), T("Ventilator · " + Device(status?.Fan), 13, Muted))),
            Card(Stack(T("Sanitarna voda", 18, null, true), T(N(status?.Sanitary), 31, Color("#8CAEE8"), true), T("Pumpa · " + Device(status?.SanitaryPump), 13, Muted)))));
        body.Children.Add(Card(Stack(T("Podatci regulatora", 18, null, true), T("Vrijeme servera: " + (status?.ServerTime ?? "—") + "  ·  Vrijeme sustava: " + (status?.SystemTime ?? "—"), 13, Muted), T(status?.SyncKat ?? "SYNC kat: —", 13, Muted), T(status?.SyncPrizemlje ?? "SYNC prizemlje: —", 13, Muted), T("Resetirano: " + N(status?.Resets, "") + "  ·  Postotak: " + N(status?.Percentage, "%"), 13, Muted))));
    }
    bool ScheduleDirty(string id) => schedules.TryGetValue(id, out var original) && drafts.TryGetValue(id, out var draft) && original.Fields.Where((f, i) => Math.Abs(f.Value - draft[i]) > 0.00001).Any();
    bool SettingsDirty() => settings != null && settingDraft != null && settings.Fields.Where((f, i) => !double.TryParse(settingDraft[i].Replace(',', '.'), CultureInfo.InvariantCulture, out var value) || Math.Abs(f.Value - value) > 0.00001).Any();
    bool IsDirty() => schedules.Keys.Any(ScheduleDirty) || SettingsDirty();
    void Schedule()
    {
        var selector = new WrapPanel();
        foreach (var (id, title) in new[] { ("kat", "Kat"), ("prizemlje", "Prizemlje") })
            selector.Children.Add(Button(title + (ScheduleDirty(id) ? " •" : ""), async () => { zone = id; Render(); await LoadHeating(); }, "zone-" + id, id == zone ? Color("#285248") : null));
        body.Children.Add(selector);
        if (!schedules.TryGetValue(zone, out var schedule)) { body.Children.Add(T("Učitavam raspored…", 16, Muted)); return; }
        var draft = drafts[zone]; scheduleSliders.Clear();
        body.Children.Add(T("24 sata · klizači temperature vode", 21, null, true)); body.Children.Add(T("Promjene se šalju tek nakon vaše potvrde. Možete slobodno prijeći na drugi zaslon i zadržati nacrt.", 13, Muted));
        var bulk = new WrapPanel { VerticalAlignment = VerticalAlignment.Center };
        var from = new ComboBox { Width = 90, ItemsSource = Enumerable.Range(0, 24).Select(i => i.ToString("00") + ":00"), SelectedIndex = 6 };
        var to = new ComboBox { Width = 90, ItemsSource = Enumerable.Range(0, 24).Select(i => i.ToString("00") + ":00"), SelectedIndex = 22 };
        var bulkTemperature = new TextBox { Text = draft[6].ToString("0.##", Hr), Width = 95, Margin = new Thickness(0, 0, 10, 10) };
        AutomationProperties.SetName(from, "Početni sat"); AutomationProperties.SetName(to, "Završni sat"); AutomationProperties.SetName(bulkTemperature, "Temperatura za odabrane sate");
        bulk.Children.Add(T("Primijeni od  ", 13, Muted)); bulk.Children.Add(from); bulk.Children.Add(T("do  ", 13, Muted)); bulk.Children.Add(to); bulk.Children.Add(bulkTemperature);
        bulk.Children.Add(Button("Primijeni na sate", () =>
        {
            if (!double.TryParse(bulkTemperature.Text.Replace(',', '.'), CultureInfo.InvariantCulture, out var value)) { ShowNotice("Unesite brojčanu temperaturu.", true); return; }
            var copy = (double[])draft.Clone(); var h = from.SelectedIndex;
            for (var count = 0; count < 24; count++) { copy[h] = value; if (h == to.SelectedIndex) break; h = (h + 1) % 24; }
            try { HeatingParser.ValidateSchedule(copy, schedule); Array.Copy(copy, draft, 24); Render(); } catch (Exception error) { ShowNotice(error.Message, true); }
        })); body.Children.Add(Card(bulk));
        var grid = new UniformGrid { Columns = 3 };
        var preview = SchedulePreview(schedule, draft); var previewControl = new NativeChart { Data = preview, Height = 220, MinHeight = 220 };
        for (var i = 0; i < 24; i++)
        {
            var hour = i; var field = schedule.Fields[i]; var valueLabel = T(N(draft[i]), 19, Mint, true);
            var top = new DockPanel(); var hourLabel = T(i.ToString("00") + ":00", 14, null, true); DockPanel.SetDock(hourLabel, Dock.Left); top.Children.Add(hourLabel); valueLabel.TextAlignment = TextAlignment.Right; top.Children.Add(valueLabel);
            var slider = new Slider { Minimum = field.Min, Maximum = field.Max, TickFrequency = field.Step, IsSnapToTickEnabled = true, Value = draft[i], SmallChange = field.Step, LargeChange = 1, Margin = new Thickness(0, 1, 0, 0) };
            AutomationProperties.SetAutomationId(slider, "hour-" + i); AutomationProperties.SetName(slider, (zone == "kat" ? "Kat" : "Prizemlje") + " temperatura u " + i + " sati");
            slider.ValueChanged += (_, _) => { draft[hour] = Math.Round(slider.Value, 6); valueLabel.Text = N(draft[hour]); previewControl.Data = SchedulePreview(schedule, draft); previewControl.InvalidateVisual(); UpdateFooter(); };
            var reference = "Spremljeno " + N(field.Value);
            if (i < schedule.Defaults.Count && schedule.Defaults[i] != null) reference += "  ·  Referenca " + N(schedule.Defaults[i]);
            var card = Card(Stack(top, slider, T(reference, 10, Muted))); card.Padding = new Thickness(13, 10, 13, 8); card.Margin = new Thickness(0, 0, 10, 10); grid.Children.Add(card); scheduleSliders.Add(slider);
        }
        body.Children.Add(grid); body.Children.Add(T("Pregled dnevnog rasporeda", 19, null, true)); body.Children.Add(previewControl); UpdateFooter();
    }
    static ChartData SchedulePreview(Schedule schedule, double[] draft) => new(new("schedule", "Raspored", "", "°C"), Enumerable.Range(0, 24).Select(i => i.ToString("00") + ":00").ToArray(),
        [new("Nacrt", draft.Select(v => (double?)v).ToArray(), "#79D8C4", true), new("Spremljeno", schedule.Fields.Select(f => (double?)f.Value).ToArray(), "#7F93A1", true)]);
    void UpdateFooter()
    {
        footer.Children.Clear(); if (page != "heating") return;
        if (heatingTab == "schedule" && schedules.ContainsKey(zone))
        {
            var bar = new DockPanel { Margin = new Thickness(0, 14, 0, 0) };
            var actions = new WrapPanel(); actions.Children.Add(Button("Vrati spremljeno", () => { drafts[zone] = schedules[zone].Fields.Select(f => f.Value).ToArray(); Render(); }));
            var save = Button(saving ? "Provjeravam spremanje…" : "Spremi raspored", SaveSchedule, "save-schedule", Color("#285C4E")); save.IsEnabled = ScheduleDirty(zone) && !saving; actions.Children.Add(save); DockPanel.SetDock(actions, Dock.Right); bar.Children.Add(actions);
            bar.Children.Add(T(ScheduleDirty(zone) ? "• Imate nespremljene promjene" : "Raspored odgovara izvorniku", 13, ScheduleDirty(zone) ? Amber : Muted)); footer.Children.Add(bar);
        }
        else if (heatingTab == "settings" && settings != null)
        {
            var bar = new WrapPanel { Margin = new Thickness(0, 14, 0, 0) };
            bar.Children.Add(Button("Vrati spremljeno", () => { settingDraft = settings.Fields.Select(f => f.Value.ToString("R", CultureInfo.InvariantCulture)).ToArray(); Render(); }));
            var save = Button(saving ? "Provjeravam spremanje…" : "Spremi postavke", SaveSettings, "save-settings", Color("#285C4E")); save.IsEnabled = SettingsDirty() && !saving; bar.Children.Add(save); bar.Children.Add(T(SettingsDirty() ? "• Nespremljene promjene" : "Postavke odgovaraju izvorniku", 13, SettingsDirty() ? Amber : Muted)); footer.Children.Add(bar);
        }
    }
    void Settings()
    {
        body.Children.Add(T("Postavke regulatora", 23, null, true));
        body.Children.Add(T("Vrijednosti, nazivi i preciznost dolaze iz postojećeg sustava. Primjena na regulatoru prati se na pregledu.", 13, Muted));
        if (settings == null || settingDraft == null) { body.Children.Add(T("Učitavam postavke…", 16, Muted)); return; }
        var fields = new StackPanel();
        for (var i = 0; i < 6; i++)
        {
            var n = i; var field = settings.Fields[i]; fields.Children.Add(T(field.Label, 14, null, true));
            if (i == 5)
            {
                var checkbox = new CheckBox { Content = "Klimatizacija uključena", IsChecked = settingDraft[i] == "1" }; AutomationProperties.SetAutomationId(checkbox, "setting-n5");
                checkbox.Checked += (_, _) => { settingDraft[n] = "1"; UpdateFooter(); }; checkbox.Unchecked += (_, _) => { settingDraft[n] = "0"; UpdateFooter(); }; fields.Children.Add(checkbox);
            }
            else
            {
                var input = new TextBox { Text = settingDraft[i], MaxWidth = 420, HorizontalAlignment = HorizontalAlignment.Left, MinWidth = 260 };
                AutomationProperties.SetAutomationId(input, "setting-n" + i); AutomationProperties.SetName(input, field.Label);
                input.TextChanged += (_, _) => { settingDraft[n] = input.Text; UpdateFooter(); }; fields.Children.Add(input);
            }
            fields.Children.Add(T("Izvorna vrijednost: " + field.Value.ToString("R", Hr), 12, Muted));
        }
        body.Children.Add(Card(fields)); UpdateFooter();
        body.Children.Add(Button("Odjava iz sustava grijanja", async () =>
        {
            if (MessageBox.Show(this, "Odjaviti ovu aplikaciju iz izvornog sustava grijanja?", "Odjava", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
            await Run(async () => { await repository.Logout(); status = null; schedules.Clear(); drafts.Clear(); settings = null; settingDraft = null; chart = null; ShowNotice("Sesija je odjavljena."); Render(); });
        }));
    }
    void Charts()
    {
        var controls = new WrapPanel(); var picker = new ComboBox { ItemsSource = ChartParser.Catalog, DisplayMemberPath = "Title", SelectedItem = ChartParser.Find(chartId), Width = 240 };
        AutomationProperties.SetName(picker, "Odaberi grafikon"); picker.SelectionChanged += async (_, _) => { if (picker.SelectedItem is ChartMeta meta) { chartId = meta.Id; chart = null; await LoadChart(); } }; controls.Children.Add(picker);
        foreach (var period in new[] { 1, 6, 12, 24 }) controls.Children.Add(Button(period + " h", () => { hours = period; Render(); }, background: period == hours ? Color("#285248") : null));
        controls.Children.Add(Button("Izvezi CSV", ExportCsv, "export-csv")); body.Children.Add(controls);
        if (chart == null || chart.Meta.Id != chartId) { body.Children.Add(T("Učitavam grafikon…", 16, Muted)); return; }
        body.Children.Add(T(chart.Meta.Title, 25, null, true));
        var native = new NativeChart { Data = chart, Hours = hours, Height = 365 }; activeChart = native; AutomationProperties.SetName(native, "Izvorni grafikon " + chart.Meta.Title);
        var legend = new WrapPanel();
        for (var i = 0; i < chart.Series.Count; i++)
        {
            var index = i; var series = chart.Series[i]; var toggle = new CheckBox { Content = series.Label, IsChecked = true, Foreground = ColorSafe(series.Color) };
            toggle.Checked += (_, _) => { native.Hidden.Remove(index); native.InvalidateVisual(); }; toggle.Unchecked += (_, _) => { native.Hidden.Add(index); native.InvalidateVisual(); }; legend.Children.Add(toggle);
        }
        body.Children.Add(legend); body.Children.Add(Card(native));
        body.Children.Add(T("Pokazivačem prijeđite preko krivulje za vrijednosti. Praznine označavaju nedostupna mjerenja.", 12, Muted));
        if (chartId == "voltage") body.Children.Add(T("Granica izvornika: 251 V  ·  Promjene veće od 10 V označene su točkom.", 13, Amber));
        foreach (var series in chart.Series)
        {
            var take = (int)Math.Ceiling(chart.Labels.Count * Math.Min(hours, chart.Meta.Hours) / (double)chart.Meta.Hours);
            var values = series.Values.Skip(Math.Max(0, chart.Labels.Count - take)).Where(x => x.HasValue).Select(x => x!.Value).ToArray();
            body.Children.Add(Card(Stack(T(series.Label, 15, ColorSafe(series.Color), true), T(values.Length == 0 ? "Nema mjerenja u razdoblju" : "Min " + N(values.Min(), chart.Meta.Unit) + "   ·   Maks " + N(values.Max(), chart.Meta.Unit) + "   ·   Posljednje " + N(values.Last(), chart.Meta.Unit), 14, Muted))));
        }
    }
    static Brush ColorSafe(string color) { try { return Color(color); } catch { return Mint; } }
    void ExportCsv()
    {
        if (chart == null) return;
        var dialog = new SaveFileDialog { Title = "Izvoz izvornih podataka", Filter = "CSV datoteka (*.csv)|*.csv", FileName = "TermoDom-" + chart.Meta.Id + "-" + DateTime.Now.ToString("yyyyMMdd-HHmm") + ".csv" };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            static string Escape(string s) { if (s.StartsWith('=') || s.StartsWith('+') || s.StartsWith('-') || s.StartsWith('@')) s = "'" + s; return "\"" + s.Replace("\"", "\"\"") + "\""; }
            var csv = new StringBuilder(); csv.AppendLine(string.Join(';', new[] { Escape("Vrijeme") }.Concat(chart.Series.Select(s => Escape(s.Label + " (" + chart.Meta.Unit + ")")))));
            for (var i = 0; i < chart.Labels.Count; i++) csv.AppendLine(string.Join(';', new[] { Escape(chart.Labels[i]) }.Concat(chart.Series.Select(s => i < s.Values.Count && s.Values[i] is double value ? value.ToString("R", CultureInfo.InvariantCulture) : ""))));
            File.WriteAllText(dialog.FileName, csv.ToString(), new UTF8Encoding(true)); ShowNotice("CSV je spremljen.");
        }
        catch (Exception error) { ShowNotice("CSV nije spremljen: " + error.Message, true); }
    }
    void Solar()
    {
        Heading("Solarna elektrana", "FusionSolar · podaci iz vašeg izvornog kioska.");
        if (solarRepository.SharedUrl == null && !verification)
        {
            var url = new TextBox { MaxLength = 16000, TextWrapping = TextWrapping.Wrap }; AutomationProperties.SetName(url, "Izvorna FusionSolar kiosk poveznica");
            var panel = Stack(T("Povežite svoju elektranu", 23, null, true), T("Uvezite datoteku povezivanja ili umetnite postojeću FusionSolar kiosk poveznicu. API ključ nije potreban. Poveznica ostaje spremljena samo na ovom računalu.", 14, Muted), url,
                Button("Poveži FusionSolar", async () => await Run(async () => { solarRepository.SetUrl(url.Text); await LoadSolar(); })), Button("Uvezi datoteku povezivanja", ImportConfiguration, "solar-import"));
            body.Children.Add(Card(panel)); body.Children.Add(T("Upravljanje inverterom · U pripremi", 16, Muted)); return;
        }
        if (solar == null) { body.Children.Add(Card(Stack(T("Elektrana je povezana", 23, null, true), T("Učitavam podatke…", 14, Muted)))); return; }
        body.Children.Add(Card(Stack(T(solar.Name, 23, null, true), T(N(solar.Power, "kW"), 48, Amber, true), T("Trenutna proizvodnja · FusionSolar", 13, Muted)), "#68553A"));
        body.Children.Add(Tiles(3, Metric("Danas", solar.Daily, "kWh", Amber, "Proizvodnja elektrane"), Metric("Ovaj mjesec", solar.Monthly, "kWh", Amber, "Proizvodnja elektrane"), Metric("Ova godina", solar.Yearly, "kWh", Amber, "Proizvodnja elektrane")));
        body.Children.Add(Tiles(3, Metric("Ukupna energija", solar.Total, "kWh", Mint, "Od puštanja elektrane u rad"), Metric("Smanjenje CO₂", solar.Co2, "kg", Mint, "FusionSolar pokazatelj"), Metric("Ušteda ugljena", solar.Coal, "kg", Mint, "FusionSolar pokazatelj")));
        if (solar.Curve != null) { body.Children.Add(T("Proizvodnja tijekom dana", 21, null, true)); body.Children.Add(Card(new NativeChart { Data = solar.Curve })); }
        body.Children.Add(Card(Stack(T("Upravljanje inverterom · U pripremi", 18, null, true), T("Dostavljeni kiosk pruža pregled proizvodnje. Naredbe inverteru bit će dostupne kada postoji podržano povezivanje.", 13, Muted))));
        if (!verification) body.Children.Add(Button("Promijeni ili ukloni povezivanje", () => { if (MessageBox.Show(this, "Ukloniti spremljenu FusionSolar poveznicu s ovog računala?", "Povezivanje", MessageBoxButton.YesNo) == MessageBoxResult.Yes) { solarRepository.Disconnect(); solar = null; Render(); } }));
    }
    async Task ImportConfiguration()
    {
        var dialog = new OpenFileDialog { Title = "Povezivanje elektrane", Filter = "TermoDom povezivanje (*.termodom;*.json)|*.termodom;*.json" };
        if (dialog.ShowDialog(this) != true) return;
        await Run(async () => { if (new FileInfo(dialog.FileName).Length > 16384) throw new ControllerException("Datoteka povezivanja je prevelika."); solarRepository.Import(File.ReadAllText(dialog.FileName)); await LoadSolar(); });
    }
    void Camera()
    {
        Heading("Xiaomi Mi 360", "Izvorni Xiaomi račun · izravan prikaz u aplikaciji.");
        cameraPanel ??= new CameraPanel(verification); body.Children.Add(Card(cameraPanel, "#3C655B"));
    }
    void Prepared()
    {
        Heading("Spremno za nadogradnju", "Opcije se uključuju tek kada postoji stvaran uređaj i podržano povezivanje.");
        var grid = new UniformGrid { Columns = 2 };
        foreach (var (name, description) in new[] { ("Rasvjeta", "Svjetla, grupe i scene"), ("Dodatni senzori", "Vlaga, kvaliteta zraka i propuštanje vode"), ("Kućanski uređaji", "Potrošnja i podržane naredbe"), ("Punjenje vozila", "Vozilo i kućna punionica"), ("Kalendar i obavijesti", "Događaji i obavijesti iz podržanih izvora"), ("Napredna energija", "Mreža, baterija, tarife i stvarni troškovi") })
            grid.Children.Add(Card(Stack(T("U PRIPREMI", 10, Muted, true), T(name, 21, null, true), T(description, 13, Muted))));
        body.Children.Add(grid);
    }
    async Task LoadStatus(bool render = true)
    {
        if (refreshing || saving) return; refreshing = true;
        try { status = await repository.Status(); fetchedAt = DateTime.Now; if (page == "home" || page == "heating" && heatingTab == "zones") Render(); else freshness.Text = "Grijanje osvježeno u " + fetchedAt.Value.ToString("HH:mm:ss", Hr); if (render) ShowNotice("Podatci grijanja su osvježeni."); }
        catch (Exception error) { ShowNotice(error.Message + (status != null ? " Prikazani su prethodno učitani podatci." : ""), true); }
        finally { refreshing = false; }
    }
    async Task LoadHeating()
    {
        if (page != "heating" || saving) return;
        await Run(async () =>
        {
            if (heatingTab == "schedule" && !schedules.ContainsKey(zone)) { var id = zone; var result = await repository.Schedule(id); schedules[id] = result; drafts[id] = result.Fields.Select(f => f.Value).ToArray(); }
            else if (heatingTab == "settings" && settings == null) { settings = await repository.Settings(); settingDraft = settings.Fields.Select(f => f.Value.ToString("R", CultureInfo.InvariantCulture)).ToArray(); }
            else if (heatingTab == "charts" && (chart == null || chart.Meta.Id != chartId)) { var id = chartId; var result = await repository.Chart(id); if (chartId == id) chart = result; }
            Render();
        });
    }
    async Task LoadChart() { await LoadHeating(); }
    async Task LoadSolar(bool render = true)
    {
        await Run(async () => { solar = verification ? NativeVerification.SolarFixture() : await solarRepository.Read(); if (page == "solar" || page == "home") Render(); if (render) ShowNotice("Podatci elektrane su osvježeni."); });
    }
    async Task RefreshCurrent()
    {
        if (saving) return;
        if (page == "heating" && heatingTab == "schedule")
        {
            if (ScheduleDirty(zone) && MessageBox.Show(this, "Osvježavanje će odbaciti nespremljene promjene ovog rasporeda. Nastaviti?", "Osvježavanje", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
            schedules.Remove(zone); drafts.Remove(zone); await LoadHeating();
        }
        else if (page == "heating" && heatingTab == "settings")
        {
            if (SettingsDirty() && MessageBox.Show(this, "Odbaciti nespremljene postavke i ponovno učitati izvor?", "Osvježavanje", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
            settings = null; settingDraft = null; await LoadHeating();
        }
        else if (page == "heating" && heatingTab == "charts") { chart = null; await LoadHeating(); }
        else if (page == "solar" && (solarRepository.SharedUrl != null || verification)) await LoadSolar();
        else await LoadStatus();
    }
    async Task SaveSchedule()
    {
        if (saving || !ScheduleDirty(zone)) return; var id = zone; var values = (double[])drafts[id].Clone();
        if (MessageBox.Show(this, "Poslati svih 24 temperatura za " + (id == "kat" ? "kat" : "prizemlje") + " izvornom regulatoru?", "Potvrda rasporeda", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        await Mutate(async () => { var result = await repository.SaveSchedule(id, values); schedules[id] = result; drafts[id] = result.Fields.Select(f => f.Value).ToArray(); ShowNotice("Izvor je potvrdio sve temperature. Primjenu pratite preko SYNC stanja."); });
    }
    async Task SaveSettings()
    {
        if (saving || settingDraft == null) return;
        var values = new double[6];
        for (var i = 0; i < 6; i++) if (!double.TryParse(settingDraft[i].Replace(',', '.'), CultureInfo.InvariantCulture, out values[i])) { ShowNotice("Sve postavke moraju biti brojčane.", true); return; }
        try { HeatingParser.ValidateSettings(values); } catch (Exception error) { ShowNotice(error.Message, true); return; }
        if (MessageBox.Show(this, "Poslati svih šest postavki izvornom regulatoru?", "Potvrda postavki", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        await Mutate(async () => { settings = await repository.SaveSettings(values); settingDraft = settings.Fields.Select(f => f.Value.ToString("R", CultureInfo.InvariantCulture)).ToArray(); ShowNotice("Izvor je potvrdio sve postavke."); });
    }
    async Task Mutate(Func<Task> action)
    {
        saving = true; menu.IsEnabled = false; scroll.IsEnabled = false; footer.IsEnabled = false;
        try { await action(); }
        catch (Exception error) { ShowNotice(error.Message, true); }
        finally { saving = false; menu.IsEnabled = true; scroll.IsEnabled = true; footer.IsEnabled = true; Render(); }
    }
    async Task Run(Func<Task> action) { try { await action(); } catch (Exception error) { ShowNotice(error.Message, true); } }
    void ShowNotice(string message, bool error = false) { notice.Text = message; notice.Foreground = error ? Color("#E6B48C") : Mint; }
    public async Task VerifyNative(string directory)
    {
        Directory.CreateDirectory(directory); await NativeVerification.TestCore(); await LoadStatus(); solar = NativeVerification.SolarFixture();
        await using (var engine = new CameraEngine(false))
        {
            await engine.StartAsync();
            File.WriteAllText(Path.Combine(directory, "native-camera-engine.txt"), "TERMODOM_WINDOWS_NATIVE_CAMERA_ENGINE_OK\nNative C# controller started bundled process; anonymous HTTP and RTSP rejected; no account, Xiaomi login or camera command used.\n");
        }
        foreach (var (id, sub, file) in new[] { ("home", "zones", "01-moj-dom"), ("heating", "zones", "02-grijanje"), ("heating", "schedule", "03-rasporedi"), ("heating", "charts", "04-grafikoni"), ("heating", "settings", "05-postavke"), ("solar", "zones", "06-solarna"), ("camera", "zones", "07-kamera"), ("prepared", "zones", "08-priprema") })
        {
            heatingTab = sub; await Navigate(id); await Dispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle); UpdateLayout();
            if (id == "heating" && sub == "schedule")
            {
                var sliders = Descendants<Slider>(this).ToArray();
                if (sliders.Length != 24) throw new Exception("Native UI mora prikazati 24 klizača.");
                var original = drafts[zone][0]; sliders[0].Value = 31.2;
                if (!ScheduleDirty(zone) || Math.Abs(drafts[zone][0] - 31.2) > 0.00001) throw new Exception("Native slider ne ažurira nacrt.");
                await Navigate("home"); await Navigate("heating");
                if (Math.Abs(drafts[zone][0] - 31.2) > 0.00001) throw new Exception("Navigacija je izgubila nespremljeni raspored.");
                drafts[zone][0] = original; Render(); UpdateLayout();
            }
            if (id == "heating" && sub == "settings" && Descendants<TextBox>(this).Count() != 5) throw new Exception("Native UI mora prikazati pet brojčanih postavki.");
            if (id == "heating" && sub == "charts" && !Descendants<NativeChart>(this).Any()) throw new Exception("Native Canvas grafikon nedostaje.");
            if (Descendants<DependencyObject>(this).Any(x => x.GetType().FullName?.Contains("WebBrowser", StringComparison.Ordinal) == true || x.GetType().FullName?.Contains("WebView", StringComparison.Ordinal) == true)) throw new Exception("Web prikaz nije dopušten.");
            var bitmap = new RenderTargetBitmap((int)ActualWidth, (int)ActualHeight, 96, 96, PixelFormats.Pbgra32); bitmap.Render(this);
            using var output = File.Create(Path.Combine(directory, file + ".png")); var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap)); encoder.Save(output);
        }
        File.WriteAllText(Path.Combine(directory, "native-smoke.txt"), "TERMODOM_WINDOWS_NATIVE_SMOKE_OK\nWPF native controls; 24 sliders; 5 numeric settings + climate; 10 chart sources; fixture reads only; no WebView.\n");
    }
    static IEnumerable<T> Descendants<T>(DependencyObject root) where T : DependencyObject
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++) { var child = VisualTreeHelper.GetChild(root, i); if (child is T match) yield return match; foreach (var nested in Descendants<T>(child)) yield return nested; }
    }
}
