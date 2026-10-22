using LibVLCSharp.Shared;
using LibVLCSharp.WPF;
using Microsoft.Win32;
using System.Globalization;
using System.IO;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using MediaPlayer = LibVLCSharp.Shared.MediaPlayer;

namespace TermoDom;

/// <summary>Original Xiaomi cloud authentication and camera video in native Windows controls.</summary>
public sealed class CameraPanel : StackPanel, IAsyncDisposable
{
    readonly CameraEngine engine;
    readonly bool verification;
    readonly TextBox username = new();
    readonly PasswordBox password = new() { Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(14, 27, 37)), Foreground = Brushes.White, Padding = new Thickness(12, 9, 12, 9), MinHeight = 42, BorderBrush = Brushes.SlateGray, Margin = new Thickness(0, 5, 0, 14) };
    readonly TextBlock message = new() { TextWrapping = TextWrapping.Wrap, Foreground = Brushes.LightSeaGreen, Margin = new Thickness(0, 5, 0, 15) };
    readonly StackPanel authIntro = new(), login = new(), challenge = new(), connected = new(), modelControls = new(), preparedControls = new();
    readonly ComboBox devices = new() { DisplayMemberPath = "Name", MinWidth = 260, HorizontalAlignment = HorizontalAlignment.Left };
    readonly ComboBox quality = new() { ItemsSource = new[] { "Automatski", "SD", "HD" }, SelectedIndex = 0, MinWidth = 180, HorizontalAlignment = HorizontalAlignment.Left };
    readonly Border videoContainer = new() { Height = 300, Background = Brushes.Black, CornerRadius = new CornerRadius(12), Margin = new Thickness(0, 8, 0, 12), ClipToBounds = true };
    readonly Button signIn = Btn("Prijavi se Xiaomi računom"), refresh = Btn("Osvježi kamere"), play = Btn("Pokreni prikaz uživo"), stop = Btn("Zaustavi prikaz"), logout = Btn("Odjavi Xiaomi račun");
    readonly Button mute = Btn("Zvuk isključen"), fullscreen = Btn("Cijeli zaslon"), snapshot = Btn("Spremi sliku");
    LibVLC? vlc;
    MediaPlayer? player;
    VideoView? video;
    Window? fullWindow;
    bool busy, started, connecting;
    bool cameraOff, hasPtz;
    Uri? streamUri;
    CameraDevice? registeredDevice;
    JsonObject? controlsMetadata;
    readonly List<Button> ptzButtons = [];
    readonly CheckBox invertPtz = new() { Content = "Obrnuti smjerovi za stropnu montažu" };
    string playingName = "";
    string selectedQuality = "auto";
    static TextBlock Text(string text, double size = 14, Brush? brush = null) => new() { Text = text, FontSize = size, Foreground = brush ?? Brushes.WhiteSmoke, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 12) };
    static Button Btn(string text) => new() { Content = text };
    public CameraPanel(bool verification)
    {
        this.verification = verification; engine = new CameraEngine(!verification);
        authIntro.Children.Add(Text("Vaša Mi 360 kamera, ovdje u TermoDomu.", 25));
        authIntro.Children.Add(Text("Prijavite se postojećim Xiaomi računom. Regija je Europa (Hrvatska); autentikaciju i dozvole provjerava izvorni Xiaomi sustav.", 14, Brushes.LightSlateGray));
        Children.Add(authIntro); Children.Add(message); Children.Add(login); Children.Add(challenge); Children.Add(connected);
        login.MaxWidth = 510; login.HorizontalAlignment = HorizontalAlignment.Left;
        login.Children.Add(Text("Xiaomi račun · e-mail ili telefon")); login.Children.Add(username);
        AutomationProperties.SetAutomationId(username, "xiaomi-username"); AutomationProperties.SetName(username, "Xiaomi e-mail ili telefon");
        login.Children.Add(Text("Lozinka")); login.Children.Add(password); AutomationProperties.SetAutomationId(password, "xiaomi-password"); AutomationProperties.SetName(password, "Lozinka izvornog Xiaomi računa");
        login.Children.Add(signIn); signIn.Click += async (_, _) => await Run(async () =>
        {
            var secret = password.Password; password.Clear();
            if (string.IsNullOrWhiteSpace(username.Text) || secret.Length == 0) throw new ControllerException("Unesite postojeći Xiaomi račun i lozinku.");
            await EnsureStarted(); var result = await engine.BeginLoginAsync(username.Text.Trim(), secret); await HandleLogin(result);
        });
        login.Children.Add(Text("Račun se koristi za povezivanje vaše kamere; ne tražimo API ključeve. Sesiju možete ukloniti odjavom. Kamera mora biti dostupna na istoj lokalnoj mreži.", 12, Brushes.LightSlateGray));
        connected.Visibility = Visibility.Collapsed;
        var selector = new DockPanel(); DockPanel.SetDock(refresh, Dock.Right); selector.Children.Add(refresh); selector.Children.Add(devices); connected.Children.Add(selector);
        AutomationProperties.SetAutomationId(devices, "xiaomi-devices"); devices.SelectionChanged += async (_, _) =>
        {
            modelControls.Children.Clear(); controlsMetadata = null; hasPtz = false; Prepared([]); UpdateControls();
            if (!verification && !busy && started && devices.SelectedItem is CameraDevice) await Run(RegisterSelected);
        };
        connected.Children.Add(videoContainer); Stage("Kamera je spremna", "Odaberite kameru i pokrenite prikaz uživo.");
        var actions = new WrapPanel(); foreach (var button in new[] { play, stop, mute, snapshot, fullscreen }) actions.Children.Add(button); connected.Children.Add(actions);
        connected.Children.Add(Text("Kvaliteta prijenosa", 12, Brushes.LightSlateGray)); connected.Children.Add(quality);
        AutomationProperties.SetAutomationId(quality, "camera-quality");
        quality.SelectionChanged += async (_, _) => await Run(async () =>
        {
            var next = quality.SelectedIndex switch { 1 => "sd", 2 => "hd", _ => "auto" };
            if (next == selectedQuality) return;
            var resume = player?.IsPlaying == true && !cameraOff; selectedQuality = next;
            if (devices.SelectedItem is CameraDevice) { await RegisterSelected(); if (resume && !cameraOff) await Play(); }
            message.Text = cameraOff ? "Kvaliteta je odabrana. Kamera ostaje isključena." : "Kvaliteta je odabrana; dostupnu rezoluciju određuje kamera.";
        });
        AutomationProperties.SetAutomationId(videoContainer, "camera-video-stage"); AutomationProperties.SetAutomationId(mute, "camera-mute"); AutomationProperties.SetAutomationId(snapshot, "camera-snapshot"); AutomationProperties.SetAutomationId(fullscreen, "camera-fullscreen");
        connected.Children.Add(Text("Slika se sprema iz stvarnog video prijenosa. Mogućnosti ovog modela učitavaju se iz izvornog Xiaomi računa.", 12, Brushes.LightSlateGray));
        connected.Children.Add(modelControls); connected.Children.Add(preparedControls); RenderMetadata(new JsonObject { ["controls"] = new JsonArray(), ["ptz"] = false }); connected.Children.Add(logout);
        refresh.Click += async (_, _) => await Run(LoadDevices);
        play.Click += async (_, _) => await Run(Play);
        stop.Click += async (_, _) => await Run(async () => { await Stop(); message.Text = "Prikaz kamere je zaustavljen."; });
        mute.Click += (_, _) => { if (player?.IsPlaying == true) { player.Mute = !player.Mute; UpdateControls(); } };
        snapshot.Click += async (_, _) => await SaveSnapshot(); fullscreen.Click += (_, _) => OpenFullscreen();
        logout.Click += async (_, _) => await Run(async () => { await Stop(); await engine.LogoutAsync(); streamUri = null; registeredDevice = null; cameraOff = false; connected.Visibility = Visibility.Collapsed; login.Visibility = authIntro.Visibility = Visibility.Visible; challenge.Children.Clear(); devices.ItemsSource = null; RenderMetadata(new JsonObject { ["controls"] = new JsonArray(), ["ptz"] = false }); message.Text = "Xiaomi sesija je uklonjena s ovog računala."; });
        if (verification)
        {
            authIntro.Visibility = login.Visibility = Visibility.Collapsed; connected.Visibility = Visibility.Visible;
            devices.ItemsSource = new[] { new CameraDevice("Xiaomi Mi 360 · pregled sučelja", "", "", "") }; devices.SelectedIndex = 0;
            RenderMetadata(VerificationMetadata());
            message.Text = "Primjer sučelja nakon prijave · sintetičke postavke, bez povezivanja s računom ili kamerom."; Stage("◉", "Pregled sučelja · bez prijenosa");
        }
        else if (engine.HasSavedAccount)
        {
            Loaded += async (_, _) => { if (!started) await Run(async () => { await EnsureStarted(); await LoadDevices(); }); };
        }
        UpdateControls();
    }
    void Stage(string heading, string subtitle)
    {
        var label = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(22) };
        var title = Text(heading, 26, Brushes.SlateGray); title.TextAlignment = TextAlignment.Center; label.Children.Add(title);
        var detail = Text(subtitle, 13, Brushes.LightSlateGray); detail.TextAlignment = TextAlignment.Center; label.Children.Add(detail); videoContainer.Child = label;
    }
    void UpdateControls()
    {
        var live = player?.IsPlaying == true;
        signIn.IsEnabled = refresh.IsEnabled = logout.IsEnabled = challenge.IsEnabled = !busy && !verification;
        devices.IsEnabled = !busy && !verification;
        quality.IsEnabled = !busy && !verification;
        play.IsEnabled = !busy && !verification && !cameraOff && devices.SelectedItem is CameraDevice;
        stop.IsEnabled = !busy && !verification && (live || connecting);
        mute.IsEnabled = snapshot.IsEnabled = fullscreen.IsEnabled = !busy && live && !verification;
        modelControls.IsEnabled = !busy && !verification;
        foreach (var button in ptzButtons) button.IsEnabled = !busy && !verification && live && hasPtz && !cameraOff;
        mute.Content = player?.Mute == false ? "Zvuk uključen" : "Zvuk isključen";
    }
    void Prepared(IEnumerable<JsonObject> controls)
    {
        var present = controls.Where(c => Flag(c["known"])).Select(c => Value(c["id"])).ToHashSet(StringComparer.Ordinal);
        preparedControls.Children.Clear(); preparedControls.Children.Add(Text("Dodatne mogućnosti kamere", 19));
        var grid = new UniformGrid { Columns = 2 };
        foreach (var (id, title, subtitle) in new[] {
            ("motion-detection", "Detekcija pokreta", "Osjetljivost i obavijesti"),
            ("motion-tracking", "Praćenje pokreta", "Automatsko praćenje objekta"), ("night-shot", "Noćni način i IR", "Automatski ili ručni prikaz"),
            ("camera-on", "Privatnost", "Uključivanje i isključivanje kamere"), ("sound-detection", "Detekcija zvuka", "Upozorenja na zvuk"),
            ("talk", "Mikrofon i razgovor", "Dvosmjerni zvuk"), ("local-recording", "Snimanje na računalo", "Lokalno spremanje video zapisa"),
            ("archive", "Reprodukcija i arhiva", "SD kartica i cloud povijest"), ("image-rollover", "Postavke slike", "Kvaliteta, okretanje i oznake") })
        {
            if (present.Contains(id)) continue;
            var content = new StackPanel(); content.Children.Add(Text("U PRIPREMI", 10, Brushes.LightSlateGray)); content.Children.Add(Text(title, 15)); content.Children.Add(Text(subtitle, 11, Brushes.LightSlateGray));
            grid.Children.Add(new Border { Child = content, Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(20, 33, 44)), BorderBrush = Brushes.DarkSlateGray, BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(12), Padding = new Thickness(14), Margin = new Thickness(0, 0, 10, 10) });
        }
        preparedControls.Children.Add(grid);
    }
    static bool Flag(JsonNode? node) => bool.TryParse(node?.ToString(), out var value) && value;
    static string Value(JsonNode? node) => node?.ToString() ?? "";
    static double? Numeric(JsonNode? node) => double.TryParse(node?.ToString(), CultureInfo.InvariantCulture, out var value) && double.IsFinite(value) ? value : null;
    sealed record Option(JsonNode? Value, string Label) { public override string ToString() => Label; }
    void RenderMetadata(JsonObject metadata)
    {
        if (invertPtz.Parent is Panel oldPad) oldPad.Children.Remove(invertPtz);
        controlsMetadata = metadata; modelControls.Children.Clear(); ptzButtons.Clear(); hasPtz = Flag(metadata["ptz"]);
        var pad = new StackPanel(); pad.Children.Add(Text(hasPtz ? "Smjer kamere · tijekom prikaza uživo" : "Smjer kamere · U pripremi za podržane modele", 15));
        var directions = new UniformGrid { Columns = 4, MaxWidth = 360, HorizontalAlignment = HorizontalAlignment.Left };
        foreach (var (direction, label) in new[] { ("left", "←"), ("up", "↑"), ("down", "↓"), ("right", "→") })
        {
            var arrow = Btn(label); AutomationProperties.SetAutomationId(arrow, "camera-ptz-" + direction); AutomationProperties.SetName(arrow, "Pomak kamere " + direction); ptzButtons.Add(arrow); directions.Children.Add(arrow);
            arrow.Click += async (_, _) => await Run(async () =>
            {
                if (cameraOff || player?.IsPlaying != true || !hasPtz) throw new ControllerException("Pomak kamere dostupan je tijekom podržanog prikaza uživo.");
                var actual = invertPtz.IsChecked == true ? direction switch { "left" => "right", "right" => "left", "up" => "down", _ => "up" } : direction;
                var response = await engine.MoveCameraAsync(actual);
                message.Text = Flag(response["sent"]) ? "Zahtjev za pomak kamere je poslan." : "Kamera nije prihvatila zahtjev za pomak.";
            });
        }
        pad.Children.Add(directions); invertPtz.IsEnabled = hasPtz && !verification; pad.Children.Add(invertPtz); modelControls.Children.Add(pad);
        var controls = (metadata["controls"] as JsonArray)?.OfType<JsonObject>().ToArray() ?? [];
        if (controls.Length > 0) modelControls.Children.Add(Text("Postavke ovog modela", 19));
        foreach (var control in controls) AddControl(control);
        Prepared(controls); UpdateControls();
    }
    static string ControlValue(JsonObject control)
    {
        var options = control["options"] as JsonArray;
        var match = options?.OfType<JsonObject>().FirstOrDefault(o => JsonNode.DeepEquals(o["value"], control["value"]));
        return match != null ? Value(match["label"]) : Value(control["value"]) + (control["unit"] != null ? " " + Value(control["unit"]) : "");
    }
    void AddControl(JsonObject control)
    {
        var id = Value(control["id"]); var label = Value(control["label"]); var format = Value(control["format"]);
        var row = new StackPanel { Margin = new Thickness(0, 9, 0, 11) }; row.Children.Add(Text(label, 14)); modelControls.Children.Add(row);
        if (!Flag(control["known"]) || !Flag(control["readable"])) { row.Children.Add(Text("Nije dostupno · U pripremi", 12, Brushes.LightSlateGray)); return; }
        if (!Flag(control["writable"])) { var display = Text(ControlValue(control), 15, Brushes.LightSeaGreen); AutomationProperties.SetAutomationId(display, "camera-option-" + id); row.Children.Add(display); return; }
        if (format == "bool" && bool.TryParse(Value(control["value"]), out var boolean))
        {
            var checkbox = new CheckBox { Content = label, IsChecked = boolean }; AutomationProperties.SetAutomationId(checkbox, "camera-option-" + id); row.Children.Add(checkbox);
            checkbox.Checked += async (_, _) => await ApplyControl(id, JsonValue.Create(true)); checkbox.Unchecked += async (_, _) => await ApplyControl(id, JsonValue.Create(false)); return;
        }
        if (control["options"] is JsonArray rawOptions && rawOptions.Count > 0)
        {
            var options = rawOptions.OfType<JsonObject>().Select(o => new Option(o["value"]?.DeepClone(), Value(o["label"]))).ToArray();
            var picker = new ComboBox { ItemsSource = options, SelectedItem = options.FirstOrDefault(o => JsonNode.DeepEquals(o.Value, control["value"])), MaxWidth = 420, HorizontalAlignment = HorizontalAlignment.Left, MinWidth = 220 };
            AutomationProperties.SetAutomationId(picker, "camera-option-" + id); row.Children.Add(picker);
            picker.SelectionChanged += async (_, _) => { if (picker.SelectedItem is Option option) await ApplyControl(id, option.Value); }; return;
        }
        if (format == "time")
        {
            var input = new TextBox { Text = Value(control["value"]), Width = 140, MaxLength = 5, HorizontalAlignment = HorizontalAlignment.Left }; row.Children.Add(input);
            AutomationProperties.SetAutomationId(input, "camera-option-" + id);
            var apply = Btn("Primijeni vrijeme"); row.Children.Add(apply);
            apply.Click += async (_, _) => { if (!Regex.IsMatch(input.Text, @"^(?:[01]\d|2[0-3]):[0-5]\d$")) { message.Text = "Unesite vrijeme u obliku HH:mm."; return; } await ApplyControl(id, JsonValue.Create(input.Text)); }; return;
        }
        if (Numeric(control["minimum"]) is double minimum && Numeric(control["maximum"]) is double maximum && Numeric(control["step"]) is double step && Numeric(control["value"]) is double current && maximum > minimum && step > 0)
        {
            var valueText = Text(ControlValue(control), 14, Brushes.LightSeaGreen); row.Children.Add(valueText);
            var slider = new Slider { Minimum = minimum, Maximum = maximum, TickFrequency = step, IsSnapToTickEnabled = true, Value = current, MaxWidth = 420, HorizontalAlignment = HorizontalAlignment.Left, MinWidth = 220 };
            AutomationProperties.SetAutomationId(slider, "camera-option-" + id); row.Children.Add(slider); slider.ValueChanged += (_, _) => valueText.Text = slider.Value.ToString("0.###", CultureInfo.GetCultureInfo("hr-HR"));
            var apply = Btn("Primijeni vrijednost"); row.Children.Add(apply); apply.Click += async (_, _) => await ApplyControl(id, JsonValue.Create(slider.Value)); return;
        }
        row.Children.Add(Text("Ova vrsta postavke je u pripremi.", 12, Brushes.LightSlateGray));
    }
    async Task ApplyControl(string id, JsonNode? value)
    {
        await Run(async () =>
        {
            // WPF changes a checkbox/picker before its event runs. Keep the confirmed readback visible while awaiting a write.
            if (controlsMetadata != null) RenderMetadata(controlsMetadata);
            try
            {
                var response = await engine.SetControlAsync(id, value);
                var current = response["control"] as JsonObject;
                if (controlsMetadata?["controls"] is JsonArray controls)
                {
                    var index = Enumerable.Range(0, controls.Count).FirstOrDefault(i => Value(controls[i]?["id"]) == id, -1);
                    if (index >= 0)
                    {
                        if (current != null) controls[index] = current.DeepClone(); else if (controls[index] is JsonObject unknown) { unknown["known"] = false; unknown["writable"] = false; unknown["value"] = null; }
                    }
                }
                if (Flag(response["confirmed"]) && Value(current?["role"]) == "camera-power" && bool.TryParse(Value(current?["value"]), out var enabled) && !enabled)
                { cameraOff = true; await Stop(); }
                else if (Flag(response["confirmed"]) && Value(current?["role"]) == "camera-power" && Flag(current?["value"])) cameraOff = false;
                message.Text = Flag(response["confirmed"]) ? "Kamera je potvrdila spremljenu postavku." : "Zahtjev je poslan, ali kamera nije potvrdila traženu vrijednost. Prikazano je stvarno pročitano stanje.";
            }
            catch
            {
                if (controlsMetadata?["controls"] is JsonArray controls)
                    foreach (var unknown in controls.OfType<JsonObject>().Where(c => Value(c["id"]) == id)) { unknown["known"] = false; unknown["writable"] = false; unknown["value"] = null; }
                throw;
            }
            finally { if (controlsMetadata != null) RenderMetadata(controlsMetadata); }
        });
    }
    async Task RegisterSelected()
    {
        if (devices.SelectedItem is not CameraDevice selected) return;
        await Stop(); streamUri = null; registeredDevice = null; modelControls.Children.Clear(); Prepared([]); cameraOff = false;
        streamUri = await engine.OpenStreamAsync(selected with { SourceUrl = WithQuality(selected.SourceUrl, selectedQuality) }); registeredDevice = selected;
        try
        {
            var metadata = await engine.GetControlsAsync(); RenderMetadata(metadata);
            var power = (metadata["controls"] as JsonArray)?.OfType<JsonObject>().FirstOrDefault(c => Value(c["role"]) == "camera-power" && Flag(c["known"]));
            cameraOff = power != null && !Flag(power["value"]);
        }
        catch (CameraEngineException) { RenderMetadata(new JsonObject { ["controls"] = new JsonArray(), ["ptz"] = false }); }
        UpdateControls();
    }
    async Task EnsureStarted() { if (!started) { await engine.StartAsync(); started = true; } }
    internal static string WithQuality(string source, string quality)
    {
        if (quality is not ("auto" or "sd" or "hd") || !Uri.TryCreate(source, UriKind.Absolute, out var uri) || uri.Scheme != "xiaomi") throw new ControllerException("Kvaliteta ili povezivanje kamere nije valjano.");
        var builder = new UriBuilder(uri);
        var fields = uri.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries).Where(field => Uri.UnescapeDataString(field.Split('=', 2)[0]) != "subtype").ToList();
        fields.Add("subtype=" + quality); builder.Query = string.Join('&', fields); return builder.Uri.AbsoluteUri;
    }
    static JsonObject VerificationMetadata() => JsonNode.Parse("""
        {"model":"Primjer · sintetički model","ptz":false,"controls":[
          {"id":"camera-on","role":"camera-power","label":"Primjer · kamera uključena","format":"bool","readable":true,"writable":true,"known":true,"value":false},
          {"id":"night-shot","label":"Primjer · noćni prikaz","format":"uint8","readable":true,"writable":true,"known":true,"value":2,"options":[{"value":0,"label":"Primjer · uključeno"},{"value":1,"label":"Primjer · isključeno"},{"value":2,"label":"Primjer · automatski"}]},
          {"id":"speaker-volume","label":"Primjer · glasnoća zvučnika","format":"uint8","readable":true,"writable":true,"known":true,"value":40,"minimum":0,"maximum":100,"step":5},
          {"id":"detection-start","label":"Primjer · početak detekcije","format":"time","readable":true,"writable":true,"known":true,"value":"08:30"},
          {"id":"sd-status","label":"Primjer · stanje SD kartice","format":"uint8","readable":true,"writable":false,"known":true,"value":1,"options":[{"value":1,"label":"Primjer · spremna"}]}
        ]}
        """)!.AsObject();
    internal void VerifyBindings()
    {
        if (!verification) throw new InvalidOperationException("Camera fixture requires verification mode.");
        IEnumerable<DependencyObject> All(DependencyObject root)
        {
            for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++) { var child = VisualTreeHelper.GetChild(root, i); yield return child; foreach (var descendant in All(child)) yield return descendant; }
        }
        T Find<T>(string id) where T : DependencyObject => All(this).OfType<T>().Single(c => AutomationProperties.GetAutomationId(c) == id);
        var power = Find<CheckBox>("camera-option-camera-on"); var mode = Find<ComboBox>("camera-option-night-shot"); var volume = Find<Slider>("camera-option-speaker-volume");
        if (power.IsChecked != false || power.IsEnabled || mode.IsEnabled || volume.IsEnabled || quality.IsEnabled || quality.Items.Count != 3 || ptzButtons.Any(b => b.IsEnabled)) throw new Exception("Camera preview controls must be disabled and explicitly synthetic.");
        if (mode.SelectedItem is not Option option || Value(option.Value) != "2" || option.Label != "Primjer · automatski") throw new Exception("Camera enum binding lost provider values.");
        if (volume.Minimum != 0 || volume.Maximum != 100 || volume.TickFrequency != 5 || volume.Value != 40 || Find<TextBox>("camera-option-detection-start").Text != "08:30" || Find<TextBlock>("camera-option-sd-status").Text != "Primjer · spremna") throw new Exception("Camera range/time/read-only metadata bindings differ.");
        var sample = "xiaomi://192.0.2.1?did=123&model=chuangmi.camera.example&user_id=456&subtype=old&extra=a%2Fb&extra=c";
        var revised = WithQuality(sample, "hd"); var revisedUri = new Uri(revised);
        if (revisedUri.Scheme != "xiaomi" || revisedUri.Host != "192.0.2.1" || !revisedUri.Query.Contains("did=123&model=chuangmi.camera.example&user_id=456") || !revisedUri.Query.Contains("extra=a%2Fb&extra=c") || !revisedUri.Query.EndsWith("subtype=hd") || revisedUri.Query.Contains("subtype=old")) throw new Exception("Camera quality changed private source parameters.");
    }
    async Task HandleLogin(CameraLoginResult result)
    {
        challenge.Children.Clear();
        if (result.Status == "success") { await LoadDevices(); return; }
        if (result.Status is not ("captcha" or "verify")) throw new ControllerException("Xiaomi nije potvrdio prijavu.");
        login.Visibility = Visibility.Collapsed;
        challenge.Children.Add(Text(result.Status == "captcha" ? "Potvrdite prikazani sigurnosni kod" : "Potvrdite izvorni Xiaomi račun", 20));
        if (result.CaptchaBytes is { Length: > 0 } bytes)
        {
            using var stream = new MemoryStream(bytes); var bitmap = new BitmapImage(); bitmap.BeginInit(); bitmap.CacheOption = BitmapCacheOption.OnLoad; bitmap.StreamSource = stream; bitmap.EndInit(); bitmap.Freeze();
            challenge.Children.Add(new Image { Source = bitmap, Width = 220, MaxHeight = 110, Stretch = Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 0, 0, 12) });
        }
        if (result.Status == "verify") challenge.Children.Add(Text("Unesite kod koji je Xiaomi poslao na potvrđeni telefon ili e-mail" + (result.VerificationPhone != null ? " · " + result.VerificationPhone : result.VerificationEmail != null ? " · " + result.VerificationEmail : "") + ".", 14, Brushes.LightSlateGray));
        var code = new TextBox { MaxWidth = 320, MinWidth = 220, HorizontalAlignment = HorizontalAlignment.Left, MaxLength = 32 }; AutomationProperties.SetName(code, "Xiaomi sigurnosni kod"); challenge.Children.Add(code);
        var confirm = Btn("Potvrdi kod"); challenge.Children.Add(confirm);
        confirm.Click += async (_, _) => await Run(async () => { if (string.IsNullOrWhiteSpace(code.Text)) throw new ControllerException("Unesite sigurnosni kod."); var next = result.Status == "captcha" ? await engine.SubmitCaptchaAsync(code.Text.Trim()) : await engine.SubmitVerificationAsync(code.Text.Trim()); code.Clear(); await HandleLogin(next); });
        var cancel = Btn("Natrag na prijavu"); challenge.Children.Add(cancel); cancel.Click += (_, _) => { challenge.Children.Clear(); login.Visibility = Visibility.Visible; };
    }
    async Task LoadDevices()
    {
        await EnsureStarted(); var list = await engine.ListDevicesAsync(); devices.ItemsSource = list; devices.SelectedIndex = list.Count > 0 ? 0 : -1;
        login.Visibility = authIntro.Visibility = Visibility.Collapsed; challenge.Children.Clear(); connected.Visibility = Visibility.Visible;
        if (list.Count > 0) await RegisterSelected();
        else { await Stop(); await engine.StopStreamAsync(); streamUri = null; registeredDevice = null; RenderMetadata(new JsonObject { ["controls"] = new JsonArray(), ["ptz"] = false }); }
        message.Text = list.Count > 0 ? "Xiaomi račun je povezan. Odaberite kameru za prikaz uživo." : "Xiaomi račun je povezan, ali u europskoj regiji nisu pronađene podržane kamere. Provjerite regiju i dijeljenje uređaja u izvornom računu.";
    }
    async Task Play()
    {
        if (devices.SelectedItem is not CameraDevice device) throw new ControllerException("Najprije odaberite kameru.");
        await EnsureStarted();
        if (registeredDevice != device || streamUri == null) await RegisterSelected();
        if (cameraOff) throw new ControllerException("Kamera je isključena. Najprije uključite kameru u postavkama privatnosti.");
        var uri = streamUri ?? throw new ControllerException("Najprije osvježite odabranu kameru.");
        await Stop();
        Core.Initialize(); vlc ??= new LibVLC("--no-video-title-show", "--no-snapshot-preview", "--quiet");
        if (player == null)
        {
            player = new MediaPlayer(vlc);
            player.EncounteredError += (_, _) => Dispatcher.BeginInvoke(() => { connecting = false; message.Text = "Prijenos kamere nije dostupan. Provjerite podršku modela i lokalnu mrežu te pokušajte ponovno."; UpdateControls(); });
            player.Playing += (_, _) => Dispatcher.BeginInvoke(() => { connecting = false; player.Mute = true; message.Text = "Prikaz uživo · " + playingName; UpdateControls(); });
            player.Stopped += (_, _) => Dispatcher.BeginInvoke(UpdateControls);
        }
        playingName = device.Name; video ??= new VideoView { MediaPlayer = player }; videoContainer.Child = video; connecting = true;
        using var media = new Media(vlc, uri); media.AddOption(":network-caching=500"); media.AddOption(":rtsp-tcp");
        if (!player.Play(media)) { connecting = false; throw new ControllerException("Nije moguće pokrenuti prikaz ove kamere."); }
        message.Text = "Povezivanje s kamerom " + device.Name + "…";
    }
    public async Task Stop()
    {
        fullWindow?.Close(); if (player != null) await Task.Run(player.Stop); connecting = false;
        Stage(verification ? "◉" : "Prikaz je zaustavljen", verification ? "Pregled sučelja · bez prijenosa" : "Pokrenite prikaz za stvarnu sliku kamere."); UpdateControls();
    }
    async Task SaveSnapshot()
    {
        if (player?.IsPlaying != true) return;
        var dialog = new SaveFileDialog { Title = "Spremi stvarnu sliku kamere", Filter = "PNG slika (*.png)|*.png", FileName = "TermoDom-Kamera-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".png" };
        if (dialog.ShowDialog(fullWindow ?? Window.GetWindow(this)) != true) return;
        await Run(async () => { await NativeCameraActions.SaveSnapshotAsync(player, dialog.FileName); message.Text = "Slika video prijenosa je spremljena."; });
    }
    void OpenFullscreen()
    {
        if (video == null || player?.IsPlaying != true || fullWindow != null) return;
        videoContainer.Child = null;
        var window = new Window { Title = "TermoDom · Kamera", WindowStyle = WindowStyle.None, ResizeMode = ResizeMode.NoResize, WindowStartupLocation = WindowStartupLocation.CenterOwner, WindowState = WindowState.Maximized, Background = Brushes.Black, Owner = Window.GetWindow(this), ShowInTaskbar = false };
        fullWindow = window; var root = new DockPanel();
        var bar = new DockPanel { Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(20, 33, 44)) };
        var close = Btn("Vrati prikaz  ·  Esc"); close.Margin = new Thickness(12); close.Click += (_, _) => window.Close(); DockPanel.SetDock(close, Dock.Right); bar.Children.Add(close);
        var title = Text(playingName, 17); title.Margin = new Thickness(20, 16, 10, 8); bar.Children.Add(title); DockPanel.SetDock(bar, Dock.Top); root.Children.Add(bar); root.Children.Add(video); window.Content = root;
        window.KeyDown += (_, e) => { if (e.Key is Key.Escape or Key.F11) window.Close(); };
        window.Closed += (_, _) => { root.Children.Remove(video); window.Content = null; fullWindow = null; videoContainer.Child = video; };
        window.Show();
    }
    async Task Run(Func<Task> action)
    {
        if (busy || verification) return; busy = true; UpdateControls();
        try { message.Text = "Povezujem se s izvornim Xiaomi sustavom…"; await action(); }
        catch (Exception error) { message.Text = error is ControllerException or CameraEngineException ? error.Message : "Povezivanje Xiaomi kamere nije uspjelo. Provjerite prijavu, podržani model i lokalnu mrežu."; }
        finally { busy = false; UpdateControls(); }
    }
    public async ValueTask DisposeAsync()
    {
        try { await Stop(); } catch { }
        video?.Dispose(); player?.Dispose(); vlc?.Dispose(); await engine.DisposeAsync();
    }
}
