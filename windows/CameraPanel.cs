using LibVLCSharp.Shared;
using LibVLCSharp.WPF;
using System.IO;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using MediaPlayer = LibVLCSharp.Shared.MediaPlayer;

namespace TermoDom;

/// <summary>Original Xiaomi cloud authentication and camera video in native Windows controls.</summary>
public sealed class CameraPanel : StackPanel, IAsyncDisposable
{
    readonly CameraEngine engine = new();
    readonly bool verification;
    readonly TextBox username = new();
    readonly PasswordBox password = new() { Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(14, 27, 37)), Foreground = Brushes.White, Padding = new Thickness(12, 9, 12, 9), MinHeight = 42, BorderBrush = Brushes.SlateGray, Margin = new Thickness(0, 5, 0, 14) };
    readonly TextBlock message = new() { TextWrapping = TextWrapping.Wrap, Foreground = Brushes.LightSeaGreen, Margin = new Thickness(0, 5, 0, 15) };
    readonly StackPanel login = new(), challenge = new(), connected = new();
    readonly ComboBox devices = new() { DisplayMemberPath = "Name", MinWidth = 260, HorizontalAlignment = HorizontalAlignment.Left };
    readonly Border videoContainer = new() { Height = 420, Background = Brushes.Black, CornerRadius = new CornerRadius(12), Margin = new Thickness(0, 14, 0, 16) };
    readonly Button signIn = Btn("Prijavi se Xiaomi računom"), refresh = Btn("Osvježi kamere"), play = Btn("Pokreni prikaz uživo"), stop = Btn("Zaustavi prikaz"), logout = Btn("Odjavi Xiaomi račun");
    LibVLC? vlc;
    MediaPlayer? player;
    VideoView? video;
    bool busy, started;
    string playingName = "";
    static TextBlock Text(string text, double size = 14, Brush? brush = null) => new() { Text = text, FontSize = size, Foreground = brush ?? Brushes.WhiteSmoke, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 12) };
    static Button Btn(string text) => new() { Content = text };
    public CameraPanel(bool verification)
    {
        this.verification = verification;
        Children.Add(Text("Vaša Mi 360 kamera, ovdje u TermoDomu.", 25));
        Children.Add(Text("Prijavite se postojećim Xiaomi računom. Regija je Europa (Hrvatska); autentikaciju i dozvole provjerava izvorni Xiaomi sustav.", 14, Brushes.LightSlateGray));
        Children.Add(message); Children.Add(login); Children.Add(challenge); Children.Add(connected);
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
        connected.Children.Add(Text("Povezane Xiaomi kamere", 19)); connected.Children.Add(devices); AutomationProperties.SetAutomationId(devices, "xiaomi-devices");
        var actions = new WrapPanel(); actions.Children.Add(refresh); actions.Children.Add(play); actions.Children.Add(stop); connected.Children.Add(actions);
        connected.Children.Add(videoContainer); connected.Children.Add(logout);
        refresh.Click += async (_, _) => await Run(LoadDevices);
        play.Click += async (_, _) => await Run(Play);
        stop.Click += async (_, _) => await Run(Stop);
        logout.Click += async (_, _) => await Run(async () => { await Stop(); await engine.LogoutAsync(); connected.Visibility = Visibility.Collapsed; login.Visibility = Visibility.Visible; challenge.Children.Clear(); devices.ItemsSource = null; message.Text = "Xiaomi sesija je uklonjena s ovog računala."; });
        if (verification)
        {
            signIn.IsEnabled = false; message.Text = "Provjera izgleda izvornog zaslona. U ovoj provjeri nije uspostavljena veza s kamerom.";
        }
        else if (engine.HasSavedAccount)
        {
            Loaded += async (_, _) => { if (!started) await Run(async () => { await EnsureStarted(); await LoadDevices(); }); };
        }
    }
    async Task EnsureStarted() { if (!started) { await engine.StartAsync(); started = true; } }
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
        login.Visibility = Visibility.Collapsed; challenge.Children.Clear(); connected.Visibility = Visibility.Visible;
        message.Text = list.Count > 0 ? "Xiaomi račun je povezan. Odaberite kameru za prikaz uživo." : "Xiaomi račun je povezan, ali u europskoj regiji nisu pronađene podržane kamere. Provjerite regiju i dijeljenje uređaja u izvornom računu.";
    }
    async Task Play()
    {
        if (devices.SelectedItem is not CameraDevice device) throw new ControllerException("Najprije odaberite kameru.");
        await EnsureStarted(); await Stop(); var uri = await engine.OpenStreamAsync(device);
        Core.Initialize(); vlc ??= new LibVLC("--no-video-title-show", "--no-snapshot-preview", "--quiet");
        if (player == null)
        {
            player = new MediaPlayer(vlc);
            player.EncounteredError += (_, _) => Dispatcher.BeginInvoke(() => { message.Text = "Prijenos kamere nije dostupan. Provjerite podršku modela i lokalnu mrežu te pokušajte ponovno."; });
            player.Playing += (_, _) => Dispatcher.BeginInvoke(() => { message.Text = "Prikaz uživo · " + playingName; });
        }
        playingName = device.Name; video ??= new VideoView { MediaPlayer = player }; videoContainer.Child = video;
        using var media = new Media(vlc, uri); media.AddOption(":network-caching=500"); media.AddOption(":rtsp-tcp");
        if (!player.Play(media)) throw new ControllerException("Nije moguće pokrenuti prikaz ove kamere.");
        message.Text = "Povezivanje s kamerom " + device.Name + "…";
    }
    public async Task Stop()
    {
        player?.Stop(); if (started) await engine.StopStreamAsync();
        if (video != null) videoContainer.Child = null;
    }
    async Task Run(Func<Task> action)
    {
        if (busy || verification) return; busy = true; signIn.IsEnabled = false; refresh.IsEnabled = false; play.IsEnabled = false; stop.IsEnabled = false; logout.IsEnabled = false; challenge.IsEnabled = false;
        try { message.Text = "Povezujem se s izvornim Xiaomi sustavom…"; await action(); }
        catch (Exception error) { message.Text = error is ControllerException or CameraEngineException ? error.Message : "Povezivanje Xiaomi kamere nije uspjelo. Provjerite prijavu, podržani model i lokalnu mrežu."; }
        finally { busy = false; signIn.IsEnabled = true; refresh.IsEnabled = true; play.IsEnabled = true; stop.IsEnabled = true; logout.IsEnabled = true; challenge.IsEnabled = true; }
    }
    public async ValueTask DisposeAsync()
    {
        try { await Stop(); } catch { }
        video?.Dispose(); player?.Dispose(); vlc?.Dispose(); await engine.DisposeAsync();
    }
}
