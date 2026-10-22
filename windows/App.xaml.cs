using System.IO;
using System.Windows;

namespace TermoDom;
public partial class App : Application
{
    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        try
        {
            var sourceDir = e.Args.FirstOrDefault(x => x.StartsWith("--verify-source="))?.Split('=', 2)[1];
            if (sourceDir != null)
            {
                NativeVerification.TestOriginalSource(sourceDir);
                Shutdown(0);
                return;
            }
            if (e.Args.Contains("--self-test"))
            {
                await NativeVerification.TestCore();
                Shutdown(0);
                return;
            }
            var screenDir = e.Args.FirstOrDefault(x => x.StartsWith("--verify-native="))?.Split('=', 2)[1];
            if (screenDir == null && e.Args.Any(x => x.StartsWith("--"))) throw new ArgumentException("Nepoznata opcija pokretanja.");
            var window = new MainWindow(screenDir != null);
            MainWindow = window;
            window.Show();
            if (screenDir != null)
            {
                await window.VerifyNative(screenDir);
                Shutdown(0);
            }
        }
        catch (Exception error)
        {
            var path = Path.Combine(AppContext.BaseDirectory, "native-verification-error.txt");
            try { File.WriteAllText(path, error.ToString()); } catch { }
            if (!e.Args.Any(a => a.StartsWith("--"))) MessageBox.Show(error.Message, "TermoDom", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(1);
        }
    }
}
