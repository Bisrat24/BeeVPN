using System;
using System.Collections.Generic;
using System.Diagnostics;

namespace BeeVPN
{
    public static class DnsManager
    {
        private static bool _isDnsOverridden = false;
        private static readonly Dictionary<string, string[]> _originalDns = new();

        public static bool IsDnsActive => _isDnsOverridden;

        public static bool SetCloudflareDns()
        {
            try
            {
                string script = @"
                    $ErrorActionPreference = 'Stop'
                    $routes = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty InterfaceAlias -Unique
                    if (!$routes) {
                        $routes = Get-NetAdapter | Where-Object { $_.Status -eq 'Up' } | Select-Object -ExpandProperty InterfaceAlias
                    }
                    $results = @()
                    foreach ($alias in $routes) {
                        $current = (Get-DnsClientServerAddress -InterfaceAlias $alias -AddressFamily IPv4 -ErrorAction SilentlyContinue).ServerAddresses
                        $currentStr = if ($current) { [string]::Join(',', $current) } else { '' }
                        Set-DnsClientServerAddress -InterfaceAlias $alias -ServerAddresses ('1.1.1.1', '1.0.0.1')
                        $results += ""$alias=$currentStr""
                    }
                    [string]::Join(';', $results)
                ";

                var psi = new ProcessStartInfo
                {
                    FileName = "powershell.exe",
                    Arguments = $"-NoProfile -ExecutionPolicy Bypass -Command \"{script}\"",
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true
                };

                using var proc = Process.Start(psi);
                if (proc == null) return false;

                string stdout = proc.StandardOutput.ReadToEnd().Trim();
                string stderr = proc.StandardError.ReadToEnd().Trim();
                proc.WaitForExit(6000);

                if (proc.ExitCode != 0)
                {
                    Debug.WriteLine($"Failed to set DNS (exit code {proc.ExitCode}): {stderr}");
                    return false;
                }

                _originalDns.Clear();
                if (!string.IsNullOrEmpty(stdout))
                {
                    var pairs = stdout.Split(';', StringSplitOptions.RemoveEmptyEntries);
                    foreach (var pair in pairs)
                    {
                        var parts = pair.Split('=', 2);
                        if (parts.Length == 2)
                        {
                            var servers = parts[1].Split(',', StringSplitOptions.RemoveEmptyEntries);
                            _originalDns[parts[0]] = servers;
                        }
                    }
                }

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
                if (!_isDnsOverridden && _originalDns.Count == 0) return;

                foreach (var (alias, servers) in _originalDns)
                {
                    string script;
                    if (servers != null && servers.Length > 0)
                    {
                        string sList = "'" + string.Join("','", servers) + "'";
                        script = $"Set-DnsClientServerAddress -InterfaceAlias '{alias}' -ServerAddresses ({sList}) -ErrorAction SilentlyContinue";
                    }
                    else
                    {
                        script = $"Set-DnsClientServerAddress -InterfaceAlias '{alias}' -ResetServerAddresses -ErrorAction SilentlyContinue";
                    }
                    RunPowerShell(script);
                }

                _originalDns.Clear();
                _isDnsOverridden = false;
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to reset DNS: {ex.Message}");
            }
        }

        private static void RunPowerShell(string script)
        {
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = "powershell.exe",
                    Arguments = $"-NoProfile -ExecutionPolicy Bypass -Command \"{script}\"",
                    CreateNoWindow = true,
                    UseShellExecute = false
                };
                using var proc = Process.Start(psi);
                proc?.WaitForExit(4000);
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"RunPowerShell error: {ex.Message}");
            }
        }
    }
}
