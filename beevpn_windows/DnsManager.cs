using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
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

        // Restore marker for adapters whose DNS came from DHCP rather than a static
        // override; those must be released with -ResetServerAddresses, not pinned.
        private const string DhcpMarker = "@dhcp";

        public static bool IsDnsActive => _isDnsOverridden;

        public static bool SetCloudflareDns()
        {
            try
            {
                if (!TryCaptureDns(useDefaultRouteAdapters: true, out var captured)) return false;

                SaveBackup(captured);
                _originalDns.Clear();
                foreach (var pair in captured)
                {
                    _originalDns[pair.Key] = pair.Value;
                }

                if (!ApplyDnsServers(new[] { "1.1.1.1", "1.0.0.1" }, useDefaultRouteAdapters: true))
                {
                    ResetDns();
                    return false;
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

        /// <summary>
        /// Points every active adapter (except the tunnel itself) at the tunnel's
        /// resolver. Unlike Cloudflare mode this is the only DNS path, so queries
        /// cannot escape the tunnel through a second adapter's resolvers.
        /// </summary>
        public static bool SetTunnelDns(params string[] servers)
        {
            try
            {
                if (servers == null || servers.Length == 0) return false;
                foreach (string server in servers)
                {
                    if (!IPAddress.TryParse(server, out _)) return false;
                }

                // Snapshot first: if applying fails halfway, the originals are already
                // persisted and ResetDns can put every adapter back.
                if (!TryCaptureDns(useDefaultRouteAdapters: false, out var captured)) return false;

                // Never overwrite an original captured earlier in this session: a
                // retry after a partial apply would otherwise record the fake resolver.
                foreach (var pair in captured)
                {
                    if (!_originalDns.ContainsKey(pair.Key))
                    {
                        _originalDns[pair.Key] = pair.Value;
                    }
                }

                SaveBackup(new Dictionary<string, string[]>(_originalDns));

                if (!ApplyDnsServers(servers, useDefaultRouteAdapters: false))
                {
                    ResetDns();
                    return false;
                }

                _isDnsOverridden = true;
                return true;
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to set tunnel DNS: {ex.Message}");
                return false;
            }
        }

        private static bool TryCaptureDns(bool useDefaultRouteAdapters, out Dictionary<string, string[]> captured)
        {
            captured = new Dictionary<string, string[]>();

            string adapterSelector = useDefaultRouteAdapters
                ? @"
                    $aliases = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty InterfaceAlias -Unique
                    if (!$aliases) {
                        $aliases = Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object { $_.Status -eq 'Up' } | Select-Object -ExpandProperty InterfaceAlias
                    }"
                : @"
                    $aliases = Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object { $_.Status -eq 'Up' } | Select-Object -ExpandProperty InterfaceAlias";

            string script = adapterSelector + @"
                    $results = @()
                    foreach ($alias in $aliases) {
                        if ($alias -eq 'BeeVPN') { continue }
                        $adapter = Get-NetAdapter -InterfaceAlias $alias -ErrorAction SilentlyContinue
                        $static = ''
                        if ($adapter) {
                            $regPath = 'HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces\' + $adapter.InterfaceGuid
                            $static = (Get-ItemProperty -Path $regPath -Name NameServer -ErrorAction SilentlyContinue).NameServer
                        }
                        if (!$static) {
                            $results += ($alias + '=@dhcp')
                            continue
                        }
                        $current = (Get-DnsClientServerAddress -InterfaceAlias $alias -AddressFamily IPv4 -ErrorAction SilentlyContinue).ServerAddresses
                        $currentStr = if ($current) { [string]::Join(',', $current) } else { '' }
                        $results += ($alias + '=' + $currentStr)
                    }
                    [string]::Join(';', $results)
                ";

            var (exitCode, stdout, stderr) = RunPowerShell(script, 8000);
            if (exitCode != 0)
            {
                Debug.WriteLine($"Failed to read current DNS (exit code {exitCode}): {stderr}");
                return false;
            }

            captured = ParseAliasMap(stdout);
            if (captured.Count == 0)
            {
                Debug.WriteLine("No active adapters were found for DNS configuration.");
                return false;
            }
            return true;
        }

        private static bool ApplyDnsServers(string[] servers, bool useDefaultRouteAdapters)
        {
            string serverList = "'" + string.Join("','", servers) + "'";

            string adapterSelector = useDefaultRouteAdapters
                ? @"$aliases = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty InterfaceAlias -Unique"
                : @"$aliases = Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object { $_.Status -eq 'Up' } | Select-Object -ExpandProperty InterfaceAlias";

            string script = $@"
                    {adapterSelector}
                    $count = 0
                    foreach ($alias in $aliases) {{
                        if ($alias -eq 'BeeVPN') {{ continue }}
                        try {{
                            Set-DnsClientServerAddress -InterfaceAlias $alias -ServerAddresses ({serverList}) -ErrorAction Stop
                            $count++
                        }} catch {{ }}
                    }}
                    $count
            ";

            var (exitCode, stdout, stderr) = RunPowerShell(script, 12000);
            bool applied = exitCode == 0 && int.TryParse(stdout.Trim(), out int appliedCount) && appliedCount > 0;
            if (!applied)
            {
                Debug.WriteLine($"Failed to apply DNS servers (exit code {exitCode}): {stderr}");
            }
            return applied;
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

                bool allRestored = true;
                foreach (var (alias, servers) in restoreMap)
                {
                    if (!RestoreInterface(alias, servers)) allRestored = false;
                }

                _isDnsOverridden = false;
                if (!allRestored)
                {
                    // Keep the snapshot so the next attempt or startup can finish.
                    Debug.WriteLine("DNS restore incomplete; keeping the backup for retry.");
                    return;
                }

                _originalDns.Clear();
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

                bool allRestored = true;
                foreach (var (alias, servers) in persisted)
                {
                    if (!RestoreInterface(alias, servers)) allRestored = false;
                }

                if (!allRestored)
                {
                    Debug.WriteLine("DNS restore from backup was incomplete; keeping the backup.");
                    return;
                }

                DeleteBackup();
                Debug.WriteLine("Restored DNS settings from previous session backup.");
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"RestoreBackupIfAny error: {ex.Message}");
            }
        }

        private static bool RestoreInterface(string alias, string[] servers)
        {
            bool releaseToDhcp = servers != null && servers.Length == 1 && servers[0] == DhcpMarker;
            string script;
            if (releaseToDhcp)
            {
                script = $"Set-DnsClientServerAddress -InterfaceAlias '{alias}' -ResetServerAddresses -ErrorAction Stop";
            }
            else if (servers != null && servers.Length > 0)
            {
                string sList = "'" + string.Join("','", servers) + "'";
                script = $"Set-DnsClientServerAddress -InterfaceAlias '{alias}' -ServerAddresses ({sList}) -ErrorAction Stop";
            }
            else
            {
                script = $"Set-DnsClientServerAddress -InterfaceAlias '{alias}' -ResetServerAddresses -ErrorAction Stop";
            }

            var (exitCode, _, stderr) = RunPowerShell(script, 4000);
            if (exitCode != 0)
            {
                Debug.WriteLine($"DNS restore for {alias} failed: {stderr}");
            }
            return exitCode == 0;
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

        internal static (int ExitCode, string Stdout, string Stderr) RunPowerShell(string script, int timeoutMs)
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
