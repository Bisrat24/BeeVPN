using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace BeeVPN
{
    public static class ProxyManager
    {
        [DllImport("wininet.dll", SetLastError = true)]
        private static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength);

        private const int INTERNET_OPTION_SETTINGS_CHANGED = 39;
        private const int INTERNET_OPTION_REFRESH = 37;

        private const string RegKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";

        private static bool _originalProxyEnable = false;
        private static string _originalProxyServer = "";
        private static bool _isBackedUp = false;
        private static bool _isCurrentlyEnabled = false;

        public static bool IsProxyActive => _isCurrentlyEnabled;

        public static void EnableSystemProxy(string host, int port)
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RegKeyPath, true);
                if (key == null) return;

                if (!_isBackedUp)
                {
                    _originalProxyEnable = ((int?)key.GetValue("ProxyEnable") ?? 0) == 1;
                    _originalProxyServer = (string?)key.GetValue("ProxyServer") ?? "";
                    _isBackedUp = true;
                }

                key.SetValue("ProxyEnable", 1, RegistryValueKind.DWord);
                key.SetValue("ProxyServer", $"{host}:{port}", RegistryValueKind.String);
                key.SetValue("ProxyOverride", "<local>;localhost;127.0.0.1;*.local", RegistryValueKind.String);

                RefreshWinINet();
                _isCurrentlyEnabled = true;
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to enable Windows proxy: {ex.Message}");
            }
        }

        public static void DisableSystemProxy()
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RegKeyPath, true);
                if (key != null)
                {
                    if (_isBackedUp)
                    {
                        key.SetValue("ProxyEnable", _originalProxyEnable ? 1 : 0, RegistryValueKind.DWord);
                        if (!string.IsNullOrEmpty(_originalProxyServer))
                        {
                            key.SetValue("ProxyServer", _originalProxyServer, RegistryValueKind.String);
                        }
                        else
                        {
                            key.DeleteValue("ProxyServer", false);
                        }
                    }
                    else
                    {
                        key.SetValue("ProxyEnable", 0, RegistryValueKind.DWord);
                        key.DeleteValue("ProxyServer", false);
                    }
                    RefreshWinINet();
                }
                _isCurrentlyEnabled = false;
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to disable Windows proxy: {ex.Message}");
            }
        }

        public static void CleanOrphanedProxy()
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RegKeyPath, true);
                if (key != null)
                {
                    string server = (string?)key.GetValue("ProxyServer") ?? "";
                    int enabled = (int?)key.GetValue("ProxyEnable") ?? 0;
                    if (enabled == 1 && server.StartsWith("127.0.0.1:", StringComparison.OrdinalIgnoreCase))
                    {
                        key.SetValue("ProxyEnable", 0, RegistryValueKind.DWord);
                        key.DeleteValue("ProxyServer", false);
                        RefreshWinINet();
                        Debug.WriteLine("Cleaned orphaned loopback proxy from registry.");
                    }
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"CleanOrphanedProxy error: {ex.Message}");
            }
        }

        private static void RefreshWinINet()
        {
            try
            {
                InternetSetOption(IntPtr.Zero, INTERNET_OPTION_SETTINGS_CHANGED, IntPtr.Zero, 0);
                InternetSetOption(IntPtr.Zero, INTERNET_OPTION_REFRESH, IntPtr.Zero, 0);
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"WinINet refresh error: {ex.Message}");
            }
        }
    }
}
