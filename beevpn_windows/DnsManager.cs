using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Threading.Tasks;

namespace BeeVPN
{
    public static class DnsManager
    {
        private static bool _isDnsOverridden = false;
        private static readonly Dictionary<string, string[]> _originalDns = new();

        private static readonly string BackupDir =
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BeeVPN");
        private static readonly string BackupPath = Path.Combine(BackupDir, "dns_backup.json");

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

                var (exitCode, stdout, stderr) = RunPowerShell(script, 8000);

                if (exitCode != 0)
                {
                    Debug.WriteLine($"Failed to set DNS (exit code {exitCode}): {stderr}");
                    return false;
                }

                var captured = ParseAliasMap(stdout);
                if (captured.Count == 0) return false;

                SaveBackup(captured);

                _originalDns.Clear();
                foreach (var pair in captured)
                {
                    _originalDns[pair.Key] = pair.Value;
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
                var restoreMap = new Dictionary<string, string[]>(_originalDns);
                if (restoreMap.Count == 0)
                {
                    if (!TryLoadBackup(out var persisted)) return;
                    foreach (var pair in persisted)
                    {
                        restoreMap[pair.Key] = pair.Value;
                    }
                }

                foreach (var (alias, servers) in restoreMap)
                {
                    RestoreInterface(alias, servers);
                }

                _originalDns.Clear();
                _isDnsOverridden = false;
                DeleteBackup();
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to reset DNS: {ex.Message}");
            }
        }

        /// <summary>
        /// Restores DNS servers captured before a crash or forced termination.
        /// </summary>
        public static void RestoreBackupIfAny()
        {
            try
            {
                if (!TryLoadBackup(out var persisted)) return;

                foreach (var (alias, servers) in persisted)
                {
                    RestoreInterface(alias, servers);
                }

                DeleteBackup();
                Debug.WriteLine("Restored DNS settings from previous session backup.");
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"RestoreBackupIfAny error: {ex.Message}");
            }
        }

        private static void RestoreInterface(string alias, string[] servers)
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
            RunPowerShell(script, 4000);
        }

        private static Dictionary<string, string[]> ParseAliasMap(string output)
        {
            var map = new Dictionary<string, string[]>();
            if (string.IsNullOrWhiteSpace(output)) return map;

            foreach (var pair in output.Split(';', StringSplitOptions.RemoveEmptyEntries))
            {
                var parts = pair.Split('=', 2);
                if (parts.Length != 2 || string.IsNullOrWhiteSpace(parts[0])) continue;

                var servers = string.IsNullOrWhiteSpace(parts[1])
                    ? Array.Empty<string>()
                    : parts[1].Split(',', StringSplitOptions.RemoveEmptyEntries);
                map[parts[0].Trim()] = servers;
            }
            return map;
        }

        private static (int ExitCode, string Stdout, string Stderr) RunPowerShell(string script, int timeoutMs)
        {
            try
            {
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
                if (proc == null) return (-1, "", "Failed to start powershell.exe");

                // Read both streams concurrently; sequential reads can deadlock when
                // one pipe buffer fills while the other is still being awaited.
                var stderrTask = Task.Run(() => proc.StandardError.ReadToEnd());
                string stdout = proc.StandardOutput.ReadToEnd();
                if (!proc.WaitForExit(timeoutMs))
                {
                    try { proc.Kill(true); } catch { }
                    return (-1, stdout, "PowerShell timed out");
                }

                return (proc.ExitCode, stdout.Trim(), stderrTask.GetAwaiter().GetResult().Trim());
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"RunPowerShell error: {ex.Message}");
                return (-1, "", ex.Message);
            }
        }

        private static bool TryLoadBackup(out Dictionary<string, string[]> map)
        {
            map = new Dictionary<string, string[]>();
            try
            {
                if (!File.Exists(BackupPath)) return false;

                var loaded = JsonSerializer.Deserialize<Dictionary<string, string[]>>(File.ReadAllText(BackupPath));
                if (loaded == null || loaded.Count == 0) return false;
                map = loaded;
                return true;
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to read DNS backup: {ex.Message}");
                return false;
            }
        }

        private static void SaveBackup(Dictionary<string, string[]> map)
        {
            try
            {
                Directory.CreateDirectory(BackupDir);
                File.WriteAllText(BackupPath, JsonSerializer.Serialize(map, new JsonSerializerOptions { WriteIndented = true }));
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to persist DNS backup: {ex.Message}");
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
                Debug.WriteLine($"Failed to delete DNS backup: {ex.Message}");
            }
        }
    }
}
