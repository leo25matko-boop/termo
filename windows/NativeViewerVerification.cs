using LibVLCSharp.Shared;
using LibVLCSharp.WPF;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using MediaPlayer = LibVLCSharp.Shared.MediaPlayer;

namespace TermoDom;

public static class NativeViewerVerification
{
    public static async Task Run(string localVideo)
    {
        if (!File.Exists(localVideo)) throw new Exception("Native viewer fixture is missing.");
        Core.Initialize();
        using var vlc = new LibVLC("--vout=wingdi", "--no-video-title-show", "--no-snapshot-preview", "--quiet");
        using var player = new MediaPlayer(vlc); player.Mute = true;
        using var view = new VideoView { MediaPlayer = player };
        var window = new Window { Title = "TermoDom · Provjera izvornog video prikaza", Width = 640, Height = 410, Content = view, ShowInTaskbar = false, WindowStartupLocation = WindowStartupLocation.CenterScreen };
        window.Show();
        try
        {
            using var media = new Media(vlc, Path.GetFullPath(localVideo), FromType.FromPath);
            if (!player.Play(media)) throw new Exception("Native viewer did not start.");
            for (var attempt = 0; attempt < 150 && !player.IsPlaying; attempt++) await Task.Delay(50);
            if (!player.IsPlaying) throw new Exception("Native viewer did not play.");
            player.Mute = true; player.Position = 0.25f; await Task.Delay(800);
            var filename = Path.Combine(AppContext.BaseDirectory, "native-decoder-snapshot.png");
            await NativeCameraActions.SaveSnapshotAsync(player, filename);
            int width, height; var colors = new HashSet<uint>();
            var signature = File.ReadAllBytes(filename);
            if (signature.Length < 8 || !signature.AsSpan(0, 8).SequenceEqual(new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 })) throw new Exception("Native snapshot is not PNG.");
            using (var stream = File.OpenRead(filename))
            {
                var bitmap = BitmapDecoder.Create(stream, BitmapCreateOptions.None, BitmapCacheOption.OnLoad); width = bitmap.Frames[0].PixelWidth; height = bitmap.Frames[0].PixelHeight;
                if (width < 16 || height < 16) throw new Exception("Native camera snapshot is invalid.");
                var converted = new FormatConvertedBitmap(bitmap.Frames[0], PixelFormats.Bgr32, null, 0); var pixels = new byte[width * height * 4]; converted.CopyPixels(pixels, width * 4, 0);
                for (var i = 0; i + 3 < pixels.Length; i += 256) colors.Add(BitConverter.ToUInt32(pixels, i) & 0x00FFFFFF);
                if (colors.Count < 16) throw new Exception("Native snapshot has no meaningful video image.");
            }
            File.WriteAllText(Path.Combine(AppContext.BaseDirectory, "native-viewer.txt"), $"TERMODOM_WINDOWS_NATIVE_VIEWER_OK\nNative WPF VideoView played local fixture; VLC TakeSnapshot produced verified {width}x{height} PNG with {colors.Count} sampled colors; mute command applied. This verifies native actions, not a user's camera.\n");
        }
        finally { await Task.Run(player.Stop); window.Content = null; window.Close(); }
    }
}
