using System;
using System.Windows;

namespace BeeVPN
{
    public partial class App : System.Windows.Application
    {
        protected override void OnStartup(System.Windows.StartupEventArgs e)
        {
            base.OnStartup(e);

            AppDomain.CurrentDomain.ProcessExit += (s, ev) => CleanUp();
            DispatcherUnhandledException += (s, ev) =>
            {
                CleanUp();
                System.Diagnostics.Debug.WriteLine($"[CRASH]: {ev.Exception}");
                System.Windows.MessageBox.Show($"BeeVPN encountered an issue:\n\n{ev.Exception.Message}", "BeeVPN", MessageBoxButton.OK, MessageBoxImage.Warning);
                ev.Handled = true;
            };
        }

        protected override void OnExit(System.Windows.ExitEventArgs e)
        {
            CleanUp();
            base.OnExit(e);
        }

        private static void CleanUp()
        {
            ProxyManager.DisableSystemProxy();
            DnsManager.ResetDns();
        }
    }
}
