using LibVLCSharp.Shared;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using MediaPlayer = LibVLCSharp.Shared.MediaPlayer;

namespace TermoDom;

public static class NativeDecoderVerification
{
    public static async Task Run(string localVideo)
    {
        if (!File.Exists(localVideo)) throw new Exception("Native decoder fixture file is missing.");
        const int width = 320, height = 180, stride = width * 4;
        var allocation = Marshal.AllocHGlobal(stride * height + 31);
        var aligned = new IntPtr((allocation.ToInt64() + 31) & ~31L);
        var decoded = 0; byte[]? captured = null;
        Core.Initialize();
        using var vlc = new LibVLC("--no-audio", "--no-video-title-show", "--quiet", "--avcodec-hw=none");
        using var player = new MediaPlayer(vlc);
        try
        {
            player.SetVideoFormat("RV32", width, height, stride);
            player.SetVideoCallbacks((_, planes) => { Marshal.WriteIntPtr(planes, aligned); return IntPtr.Zero; },
                null, (_, _) =>
                {
                    var frame = new byte[stride * height]; Marshal.Copy(aligned, frame, 0, frame.Length);
                    var samples = new HashSet<uint>();
                    for (var i = 0; i + 3 < frame.Length; i += 256) samples.Add(BitConverter.ToUInt32(frame, i) & 0x00FFFFFF);
                    if (samples.Count >= 16) captured = frame;
                    Interlocked.Increment(ref decoded);
                });
            using var media = new Media(vlc, Path.GetFullPath(localVideo), FromType.FromPath);
            if (!player.Play(media)) throw new Exception("Native LibVLC playback did not start.");
            await Task.Delay(500); player.Position = 0.25f;
            for (var attempt = 0; attempt < 200 && (Volatile.Read(ref decoded) < 12 || captured == null); attempt++) await Task.Delay(50);
            player.Stop();
            if (decoded < 12 || captured == null) throw new Exception("Native video decoder produced no verified frames.");
            var colors = new HashSet<uint>();
            for (var i = 0; i + 3 < captured.Length; i += 64) colors.Add(BitConverter.ToUInt32(captured, i) & 0x00FFFFFF);
            if (colors.Count < 16) throw new Exception("Decoded video did not contain a meaningful image.");
            var bitmap = BitmapSource.Create(width, height, 96, 96, PixelFormats.Bgr32, null, captured, stride); bitmap.Freeze();
            var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using (var output = File.Create(Path.Combine(AppContext.BaseDirectory, "native-decoder-frame.png"))) encoder.Save(output);
            var hash = Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(localVideo))).ToLowerInvariant();
            File.WriteAllText(Path.Combine(AppContext.BaseDirectory, "native-decoder.txt"), $"TERMODOM_WINDOWS_NATIVE_DECODER_OK\nLibVLC native video decoder; {decoded} frames, {width}x{height} RV32, {colors.Count} sampled colors; local fixture SHA256 {hash}.\nThis verifies the decoder, not a user's live camera.\n");
        }
        finally { player.Stop(); Marshal.FreeHGlobal(allocation); }
    }
}
