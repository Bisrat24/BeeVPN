using System;
using System.Threading;
using System.Windows;

namespace BeeVPN
{
    public partial class App : System.Windows.Application
    {
        private static Mutex? _instanceMutex;

        protected override void OnStartup(System.Windows.StartupEventArgs e)
        {
            const string mutexName = "Global\\BeeVPN_SingleInstance_Mutex";
            _instanceMutex = new Mutex(true, mutexName, out bool isNewInstance);

            if (!isNewInstance)
            {
                System.Windows.MessageBox.Show("BeeVPN is already running. Check your system tray in the taskbar.", "BeeVPN", MessageBoxButton.OK, MessageBoxImage.Information);
                Shutdown();
                return;
            }

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
            _instanceMutex?.ReleaseMutex();
            _instanceMutex?.Dispose();
            base.OnExit(e);
        }

        private static void CleanUp()
        {
            ProxyManager.DisableSystemProxy();
            DnsManager.ResetDns();
        }
    }
}
