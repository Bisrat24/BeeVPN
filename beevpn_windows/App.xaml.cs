using System;
using System.Threading;
using System.Windows;

namespace BeeVPN
{
    public partial class App : System.Windows.Application
    {
        private const string MutexName = @"Local\BeeVPN_SingleInstance_Mutex";
        private static Mutex? _instanceMutex;

        protected override void OnStartup(System.Windows.StartupEventArgs e)
        {
            try
            {
                _instanceMutex = new Mutex(true, MutexName, out bool isNewInstance);
                if (!isNewInstance)
                {
                    System.Windows.MessageBox.Show("BeeVPN is already running. Check your system tray in the taskbar.", "BeeVPN", MessageBoxButton.OK, MessageBoxImage.Information);
                    Shutdown();
                    return;
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Single-instance mutex unavailable: {ex.Message}");
                _instanceMutex = null;
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
            try
            {
                _instanceMutex?.ReleaseMutex();
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Mutex release skipped: {ex.Message}");
            }
            _instanceMutex?.Dispose();
            base.OnExit(e);
        }

        /// <summary>
        /// Drops the single-instance lock so an elevated replacement process can
        /// start before this instance exits.
        /// </summary>
        public static void ReleaseInstanceMutex()
        {
            try
            {
                _instanceMutex?.ReleaseMutex();
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Mutex release skipped: {ex.Message}");
            }

            try
            {
                _instanceMutex?.Dispose();
            }
            catch
            {
            }

            _instanceMutex = null;
        }

        public static bool ReacquireInstanceMutex()
        {
            if (_instanceMutex != null) return true;

            try
            {
                _instanceMutex = new Mutex(true, MutexName, out bool isNewInstance);
                if (isNewInstance) return true;

                _instanceMutex.Dispose();
                _instanceMutex = null;
                return false;
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Mutex reacquire failed: {ex.Message}");
                _instanceMutex = null;
                return false;
            }
        }

        private static void CleanUp()
        {
            if (ProxyManager.IsProxyActive)
            {
                ProxyManager.DisableSystemProxy();
            }
            else
            {
                ProxyManager.RestoreBackupIfAny();
            }

            DnsManager.ResetDns();
            WintunTunnelManager.CleanupStaleState();
        }
    }
}
