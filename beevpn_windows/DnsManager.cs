using System;
using System.Diagnostics;

namespace BeeVPN
{
    public static class DnsManager
    {
        private static bool _isDnsOverridden = false;

        public static bool IsDnsActive => _isDnsOverridden;

        public static bool SetCloudflareDns()
        {
            try
            {
                string script = @"
                    $adapters = Get-NetAdapter | Where-Object { $_.Status -eq 'Up' }
                    foreach ($a in $adapters) {
                        Set-DnsClientServerAddress -InterfaceAlias $a.InterfaceAlias -ServerAddresses ('1.1.1.1', '1.0.0.1') -ErrorAction SilentlyContinue
                    }
                ";

                var psi = new ProcessStartInfo
                {
                    FileName = "powershell.exe",
                    Arguments = $"-NoProfile -ExecutionPolicy Bypass -Command \"{script}\"",
                    CreateNoWindow = true,
                    UseShellExecute = false
                };

                using var proc = Process.Start(psi);
                proc?.WaitForExit(4000);
                _isDnsOverridden = true;
                return true;
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to set Cloudflare DNS: {ex.Message}");
                return false;
            }
        }

        public static void ResetDns()
        {
            try
            {
                if (!_isDnsOverridden) return;

                string script = @"
                    $adapters = Get-NetAdapter | Where-Object { $_.Status -eq 'Up' }
                    foreach ($a in $adapters) {
                        Set-DnsClientServerAddress -InterfaceAlias $a.InterfaceAlias -ResetServerAddresses -ErrorAction SilentlyContinue
                    }
                ";

                var psi = new ProcessStartInfo
                {
                    FileName = "powershell.exe",
                    Arguments = $"-NoProfile -ExecutionPolicy Bypass -Command \"{script}\"",
                    CreateNoWindow = true,
                    UseShellExecute = false
                };

                using var proc = Process.Start(psi);
                proc?.WaitForExit(4000);
                _isDnsOverridden = false;
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to reset DNS: {ex.Message}");
            }
        }
    }
}
