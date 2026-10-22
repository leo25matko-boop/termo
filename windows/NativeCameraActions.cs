using LibVLCSharp.Shared;
using System.IO;
using MediaPlayer = LibVLCSharp.Shared.MediaPlayer;

namespace TermoDom;

public static class NativeCameraActions
{
    /// <summary>Saves a frame from the actual VLC output, never the UI placeholder.</summary>
    public static async Task SaveSnapshotAsync(MediaPlayer player, string filename, CancellationToken cancellation = default)
    {
        if (!player.IsPlaying || player.VideoTrack < 0) throw new ControllerException("Snimka je dostupna tijekom stvarnog video prijenosa.");
        var expected = Path.GetFullPath(filename);
        var completed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        void OnSnapshot(object? _, MediaPlayerSnapshotTakenEventArgs e)
        {
            if (Path.GetFullPath(e.Filename).Equals(expected, StringComparison.OrdinalIgnoreCase)) completed.TrySetResult();
        }
        player.SnapshotTaken += OnSnapshot;
        try
        {
            var accepted = await Task.Run(() => player.TakeSnapshot(0, expected, 0, 0), cancellation).WaitAsync(TimeSpan.FromSeconds(12), cancellation);
            if (!accepted) throw new ControllerException("Sliku video prijenosa nije moguće spremiti.");
            await completed.Task.WaitAsync(TimeSpan.FromSeconds(10), cancellation);
            if (!File.Exists(expected) || new FileInfo(expected).Length == 0) throw new ControllerException("Kamera nije potvrdila spremljenu sliku.");
        }
        finally { player.SnapshotTaken -= OnSnapshot; }
    }
}
