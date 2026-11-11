using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;
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

        private static readonly string BackupDir =
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BeeVPN");
        private static readonly string BackupPath = Path.Combine(BackupDir, "proxy_backup.json");

        private static bool _originalProxyEnable = false;
        private static string _originalProxyServer = "";
        private static string _originalProxyOverride = "";
        private static bool _isBackedUp = false;
        private static bool _isCurrentlyEnabled = false;

        public static bool IsProxyActive => _isCurrentlyEnabled;

        private sealed class ProxyBackup
        {
            public bool Enabled { get; set; }
            public string Server { get; set; } = "";
            public string Override { get; set; } = "";
        }

        public static void EnableSystemProxy(string host, int port)
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RegKeyPath, true);
                if (key == null) return;

                if (!_isBackedUp)
                {
                    // Reuse the on-disk backup if a previous run crashed, so the tunnel's
                    // loopback proxy is never captured as the "original" setting.
                    if (!TryLoadBackup(out var persisted))
                    {
                        persisted = new ProxyBackup
                        {
                            Enabled = ((int?)key.GetValue("ProxyEnable") ?? 0) == 1,
                            Server = (string?)key.GetValue("ProxyServer") ?? "",
                            Override = (string?)key.GetValue("ProxyOverride") ?? ""
                        };
                        SaveBackup(persisted);
                    }

                    _originalProxyEnable = persisted.Enabled;
                    _originalProxyServer = persisted.Server;
                    _originalProxyOverride = persisted.Override;
                    _isBackedUp = true;
                }
                else if (!File.Exists(BackupPath))
                {
                    // Originals still in memory but the file was consumed by a clean
                    // restore; re-persist so a crash in this session stays recoverable.
                    SaveBackup(new ProxyBackup
                    {
                        Enabled = _originalProxyEnable,
                        Server = _originalProxyServer,
                        Override = _originalProxyOverride
                    });
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
                    if (!_isBackedUp && TryLoadBackup(out var persisted))
                    {
                        _originalProxyEnable = persisted.Enabled;
                        _originalProxyServer = persisted.Server;
                        _originalProxyOverride = persisted.Override;
                        _isBackedUp = true;
                    }

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

                        if (!string.IsNullOrEmpty(_originalProxyOverride))
                        {
                            key.SetValue("ProxyOverride", _originalProxyOverride, RegistryValueKind.String);
                        }
                        else
                        {
                            key.DeleteValue("ProxyOverride", false);
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
                DeleteBackup();
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to disable Windows proxy: {ex.Message}");
            }
        }

        /// <summary>
        /// Restores Internet Settings captured before a crash or forced termination.
        /// </summary>
        public static void RestoreBackupIfAny()
        {
            try
            {
                if (!TryLoadBackup(out var backup)) return;

                using var key = Registry.CurrentUser.OpenSubKey(RegKeyPath, true);
                if (key == null) return;

                key.SetValue("ProxyEnable", backup.Enabled ? 1 : 0, RegistryValueKind.DWord);
                if (!string.IsNullOrEmpty(backup.Server))
                {
                    key.SetValue("ProxyServer", backup.Server, RegistryValueKind.String);
                }
                else
                {
                    key.DeleteValue("ProxyServer", false);
                }

                if (!string.IsNullOrEmpty(backup.Override))
                {
                    key.SetValue("ProxyOverride", backup.Override, RegistryValueKind.String);
                }
                else
                {
                    key.DeleteValue("ProxyOverride", false);
                }

                RefreshWinINet();
                DeleteBackup();
                Debug.WriteLine("Restored Internet Settings from previous session backup.");
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"RestoreBackupIfAny error: {ex.Message}");
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

        private static bool TryLoadBackup(out ProxyBackup backup)
        {
            backup = new ProxyBackup();
            try
            {
                if (!File.Exists(BackupPath)) return false;
                var loaded = JsonSerializer.Deserialize<ProxyBackup>(File.ReadAllText(BackupPath));
                if (loaded == null) return false;
                backup = loaded;
                return true;
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to read proxy backup: {ex.Message}");
                return false;
            }
        }

        private static void SaveBackup(ProxyBackup backup)
        {
            try
            {
                Directory.CreateDirectory(BackupDir);
                File.WriteAllText(BackupPath, JsonSerializer.Serialize(backup, new JsonSerializerOptions { WriteIndented = true }));
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to persist proxy backup: {ex.Message}");
            }
        }

        private static void DeleteBackup()
        {
            try
            {
                if (File.Exists(BackupPath)) File.Delete(BackupPath);
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to delete proxy backup: {ex.Message}");
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
