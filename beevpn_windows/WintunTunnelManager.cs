using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace BeeVPN
{
    /// <summary>
    /// Full-device IPv4 tunneling through a Wintun adapter. tun2socks terminates
    /// IP traffic and forwards TCP to a local <see cref="TunSocksBridge"/>, which
    /// chains to the Psiphon core's SOCKS proxy. TCP servers used by the core are
    /// pinned to the physical gateway so the core's own tunnel connection cannot
    /// be routed back into the TUN.
    /// </summary>
    public sealed class WintunTunnelManager : IDisposable
    {
        public const string AdapterName = "BeeVPN";
        public const string TunAddress = "10.111.222.1";
        private const int TunPrefixLength = 24;
        private const int TunMtu = 1400;

        // Windows' resolver ignores a DNS server that only exists on the tunnel
        // adapter, so system DNS is pointed at public resolvers. Their UDP queries
        // are routed into the TUN and answered by the bridge over DNS-over-TCP.
        private static readonly string[] SystemDnsServers = { "1.1.1.1", "1.0.0.1" };

        private const int AF_INET = 2;
        private const int TCP_TABLE_OWNER_PID_ALL = 5;
        private const uint ERROR_INSUFFICIENT_BUFFER = 122;

        [DllImport("iphlpapi.dll", SetLastError = true)]
        private static extern uint GetExtendedTcpTable(IntPtr pTcpTable, ref int pdwSize, bool bOrder, int ulAf, int tableClass, uint reserved);

        [StructLayout(LayoutKind.Sequential)]
        private struct MIB_TCPROW_OWNER_PID
        {
            public uint State;
            public uint LocalAddr;
            public uint LocalPort;
            public uint RemoteAddr;
            public uint RemotePort;
            public uint OwningPid;
        }

        private readonly string _baseDir;
        private readonly string _tun2socksDir;
        private readonly string _tun2socksPath;
        private readonly string _statePath;
        private readonly object _sync = new();
        private readonly HashSet<string> _routedIps = new(StringComparer.Ordinal);

        private TunSocksBridge? _bridge;
        private Process? _tunProcess;
        private CancellationTokenSource? _startCts;
        private CancellationTokenSource? _monitorCts;
        private Task? _monitorTask;
        private int _corePid;
        private string _physicalAlias = "";
        private string _physicalGateway = "";
        private bool _active;
        private bool _starting;
        private bool _stopping;
        private bool _disposed;

        public bool IsActive
        {
            get { lock (_sync) return _active; }
        }

        public bool IsBusy
        {
            get { lock (_sync) return _active || _starting; }
        }

        public event Action<string, string>? LogMessage;
        public event Action<string>? Failed;

        public WintunTunnelManager(string? baseDir = null)
        {
            _baseDir = baseDir ?? AppContext.BaseDirectory;
            _tun2socksDir = Path.Combine(_baseDir, "third_party", "tun2socks");
            _tun2socksPath = Path.Combine(_tun2socksDir, "tun2socks.exe");
            _statePath = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "BeeVPN",
                "tun_state.json");
        }

        public bool Start(int coreSocksPort, string egressRegion, int corePid)
        {
            CancellationToken token;
            lock (_sync)
            {
                if (_active) return true;
                if (_starting) return false;
                _starting = true;
                _stopping = false;
                _startCts?.Dispose();
                _startCts = new CancellationTokenSource();
                token = _startCts.Token;
            }

            try
            {
                if (!ElevationHelper.IsAdministrator)
                {
                    Log("TUN", "ERROR: Full-device tunnel requires administrator rights.");
                    return false;
                }

                if (!File.Exists(_tun2socksPath))
                {
                    Log("TUN", $"ERROR: tun2socks.exe not found at {_tun2socksPath}");
                    return false;
                }

                if (!File.Exists(Path.Combine(_tun2socksDir, "wintun.dll")))
                {
                    Log("TUN", "ERROR: wintun.dll is missing next to tun2socks.exe.");
                    return false;
                }

                _corePid = corePid;
                _routedIps.Clear();

                RemoveDefaultRoutes();
                RemoveTunAddress();

                _bridge = new TunSocksBridge(coreSocksPort);
                _bridge.Log += message => Log("TUN", message);
                _bridge.Start();

                if (!StartTun2Socks())
                {
                    return FailStart();
                }

                if (!WaitForAdapter(TimeSpan.FromSeconds(15)) || token.IsCancellationRequested)
                {
                    Log("TUN", "ERROR: Wintun adapter did not come up. " + GetTunLogTail());
                    return FailStart();
                }

                if (!ConfigureAdapter() || !WaitForTunAddress(TimeSpan.FromSeconds(5)))
                {
                    Log("TUN", "ERROR: Could not assign the tunnel address to the Wintun adapter.");
                    return FailStart();
                }

                if (token.IsCancellationRequested) return FailStart();

                if (!DiscoverPhysicalRoute())
                {
                    Log("TUN", "ERROR: Could not determine the physical default gateway.");
                    return FailStart();
                }

                var routeIps = new HashSet<string>(StringComparer.Ordinal);
                foreach (string ip in GetServerIps(egressRegion))
                {
                    routeIps.Add(ip);
                }
                foreach (string ip in GetCoreEndpoints(corePid))
                {
                    routeIps.Add(ip);
                }

                AddBypassRoutes(routeIps);
                WriteState();

                if (token.IsCancellationRequested) return FailStart();

                if (!AddDefaultRoutes())
                {
                    Log("TUN", "ERROR: Could not install the default routes through the Wintun adapter.");
                    return FailStart();
                }

                WriteState();

                if (token.IsCancellationRequested) return FailStart();

                if (!DnsManager.SetTunnelDns(SystemDnsServers))
                {
                    Log("TUN", "ERROR: Could not redirect system DNS into the tunnel.");
                    return FailStart();
                }

                StartMonitor();

                lock (_sync) { _active = true; }

                Log("TUN", $"Full-device tunnel active. {_routedIps.Count} tunnel endpoints pinned to the physical gateway.");
                _ = ProbeTunnelAsync();
                return true;
            }
            catch (Exception ex)
            {
                Log("TUN", $"ERROR: Full-device tunnel failed: {ex.Message}");
                return FailStart();
            }
            finally
            {
                lock (_sync) { _starting = false; }
            }
        }

        private bool FailStart()
        {
            try { _startCts?.Cancel(); } catch { }
            CleanUpTunnel();
            lock (_sync)
            {
                _active = false;
                _stopping = false;
            }
            return false;
        }

        public void Stop()
        {
            lock (_sync)
            {
                if (_starting)
                {
                    // The start path is still configuring routes; signal it and let
                    // it unwind so cleanup never races a half-applied network state.
                    _stopping = true;
                    try { _startCts?.Cancel(); } catch { }
                    return;
                }

                if (_stopping) return;
                if (!_active && _bridge == null && _tunProcess == null) return;

                _stopping = true;
            }

            CleanUpTunnel();

            lock (_sync)
            {
                _active = false;
                _stopping = false;
            }
        }

        private void CleanUpTunnel()
        {
            Log("TUN", "Stopping full-device tunnel and restoring network settings...");

            try
            {
                _monitorCts?.Cancel();
                try { _monitorTask?.Wait(2000); } catch { }
            }
            catch { }

            try { RemoveDefaultRoutes(); } catch { }

            List<string> routes;
            lock (_sync) { routes = _routedIps.ToList(); }
            try { RemoveBypassRoutes(routes); } catch { }

            try { StopTun2Socks(); } catch { }
            try { DnsManager.ResetDns(); } catch { }

            try { _bridge?.Dispose(); } catch { }
            _bridge = null;

            try { RemoveTunAddress(); } catch { }

            DeleteState();

            _monitorCts?.Dispose();
            _monitorCts = null;
            _monitorTask = null;

            try { _startCts?.Dispose(); } catch { }
            _startCts = null;

            Log("TUN", "Full-device tunnel stopped. Routes and DNS restored.");
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            Stop();
        }

        private bool StartTun2Socks()
        {
            var psi = new ProcessStartInfo
            {
                FileName = _tun2socksPath,
                Arguments = $"--device \"tun://{AdapterName}\" --proxy \"socks5://127.0.0.1:{_bridge!.Port}\" --mtu {TunMtu} --loglevel error",
                WorkingDirectory = _tun2socksDir,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };

            var process = new Process { StartInfo = psi, EnableRaisingEvents = true };
            process.OutputDataReceived += (s, e) => AppendTunLog(e.Data);
            process.ErrorDataReceived += (s, e) => AppendTunLog(e.Data);
            process.Exited += (s, e) =>
            {
                bool unexpected;
                lock (_sync) { unexpected = !_stopping && _active; }
                if (!unexpected) return;

                Log("TUN", "ERROR: tun2socks terminated unexpectedly." + GetTunLogTail());
                Failed?.Invoke("tun2socks terminated unexpectedly");
            };

            try
            {
                process.Start();
                process.BeginOutputReadLine();
                process.BeginErrorReadLine();
                _tunProcess = process;
                WriteState();

                Thread.Sleep(400);
                if (process.HasExited)
                {
                    Log("TUN", "ERROR: tun2socks exited during startup. " + GetTunLogTail());
                    return false;
                }
                return true;
            }
            catch (Exception ex)
            {
                Log("TUN", $"ERROR: Failed to start tun2socks: {ex.Message}");
                try { process.Dispose(); } catch { }
                return false;
            }
        }

        private void StopTun2Socks()
        {
            Process? process = _tunProcess;
            _tunProcess = null;
            if (process == null) return;

            try
            {
                if (!process.HasExited)
                {
                    process.Kill(true);
                    process.WaitForExit(3000);
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to stop tun2socks: {ex.Message}");
            }
            finally
            {
                try { process.Dispose(); } catch { }
            }
        }

        private bool WaitForAdapter(TimeSpan timeout)
        {
            DateTime deadline = DateTime.UtcNow + timeout;
            while (DateTime.UtcNow < deadline)
            {
                if (_tunProcess == null || _tunProcess.HasExited) return false;

                try
                {
                    foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
                    {
                        if (string.Equals(nic.Name, AdapterName, StringComparison.OrdinalIgnoreCase) &&
                            nic.OperationalStatus == OperationalStatus.Up)
                        {
                            return true;
                        }
                    }
                }
                catch
                {
                }

                Thread.Sleep(200);
            }
            return false;
        }

        private bool WaitForTunAddress(TimeSpan timeout)
        {
            DateTime deadline = DateTime.UtcNow + timeout;
            while (DateTime.UtcNow < deadline)
            {
                try
                {
                    foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
                    {
                        if (!string.Equals(nic.Name, AdapterName, StringComparison.OrdinalIgnoreCase)) continue;

                        foreach (var unicast in nic.GetIPProperties().UnicastAddresses)
                        {
                            if (unicast.Address.AddressFamily == AddressFamily.InterNetwork &&
                                string.Equals(unicast.Address.ToString(), TunAddress, StringComparison.Ordinal))
                            {
                                return true;
                            }
                        }
                    }
                }
                catch
                {
                }

                Thread.Sleep(200);
            }
            return false;
        }

        private bool ConfigureAdapter()
        {
            string script = $@"
                $ErrorActionPreference = 'Stop'
                New-NetIPAddress -InterfaceAlias '{AdapterName}' -IPAddress '{TunAddress}' -PrefixLength {TunPrefixLength} -ErrorAction SilentlyContinue | Out-Null
                Set-NetIPInterface -InterfaceAlias '{AdapterName}' -InterfaceMetric 1 -ErrorAction SilentlyContinue | Out-Null
                'configured'
            ";

            var (exitCode, stdout, stderr) = DnsManager.RunPowerShell(script, 8000);
            if (exitCode != 0 || !stdout.Contains("configured", StringComparison.OrdinalIgnoreCase))
            {
                Debug.WriteLine($"ConfigureAdapter failed: {stderr}");
                return false;
            }
            return true;
        }

        private void RemoveTunAddress()
        {
            DnsManager.RunPowerShell(
                $"Remove-NetIPAddress -InterfaceAlias '{AdapterName}' -IPAddress '{TunAddress}' -Confirm:$false -ErrorAction SilentlyContinue",
                5000);
        }

        private bool DiscoverPhysicalRoute()
        {
            string script = @"
                $ErrorActionPreference = 'SilentlyContinue'
                $virtualPattern = 'vEthernet|WSL|Hyper-V|VMware|VirtualBox|Docker|Loopback|Teredo|isatap|Bluetooth|Npcap'
                function Get-BestRoute($excludeVirtual) {
                    Get-NetRoute -DestinationPrefix '0.0.0.0/0' -AddressFamily IPv4 |
                        Where-Object {
                            $_.InterfaceAlias -ne 'BeeVPN' -and
                            (-not $excludeVirtual -or $_.InterfaceAlias -notmatch $virtualPattern)
                        } |
                        ForEach-Object {
                            $ip = Get-NetIPInterface -InterfaceAlias $_.InterfaceAlias -AddressFamily IPv4 |
                                Select-Object -First 1
                            [pscustomobject]@{
                                Alias = $_.InterfaceAlias
                                NextHop = $_.NextHop
                                Cost = $_.RouteMetric + $(if ($ip) { $ip.InterfaceMetric } else { 9999 })
                            }
                        } |
                        Sort-Object Cost |
                        Select-Object -First 1
                }
                $route = Get-BestRoute $true
                if (-not $route) { $route = Get-BestRoute $false }
                if ($route) { $route.Alias + '|' + $route.NextHop } else { '' }
            ";

            var (exitCode, stdout, stderr) = DnsManager.RunPowerShell(script, 12000);
            if (exitCode != 0)
            {
                Debug.WriteLine($"DiscoverPhysicalRoute failed: {stderr}");
                return false;
            }

            string line = stdout.Trim();
            int separator = line.IndexOf('|');
            if (separator <= 0)
            {
                return false;
            }

            _physicalAlias = line.Substring(0, separator).Trim();
            _physicalGateway = line.Substring(separator + 1).Trim();
            if (string.Equals(_physicalGateway, "0.0.0.0", StringComparison.Ordinal))
            {
                _physicalGateway = "";
            }

            Log("TUN", $"Physical egress: {_physicalAlias}" +
                (string.IsNullOrEmpty(_physicalGateway) ? " (on-link)" : $" via {_physicalGateway}"));
            return !string.IsNullOrWhiteSpace(_physicalAlias);
        }

        private void AddBypassRoutes(IEnumerable<string> ips)
        {
            var lines = new List<string>();
            foreach (string ip in ips)
            {
                if (!IsPublicIPv4(ip)) continue;
                if (!_routedIps.Add(ip)) continue;
                lines.Add(BuildRouteCommand("add", ip));
            }

            if (lines.Count == 0) return;

            Log("TUN", $"Pinning {lines.Count} Psiphon server endpoints to the physical gateway...");
            RunNetshScript(lines, 30000);
        }

        private bool AddSingleBypassRoute(string ip)
        {
            if (!IsPublicIPv4(ip)) return false;

            var (exitCode, stdout, stderr) = RunProcess("netsh.exe", BuildRouteCommand("add", ip), 8000);
            if (exitCode == 0)
            {
                lock (_sync) { _routedIps.Add(ip); }
                WriteState();
                return true;
            }

            Debug.WriteLine($"Bypass route for {ip} failed: {stdout} {stderr}");
            return false;
        }

        private string BuildRouteCommand(string action, string ip)
        {
            string command = $"interface ipv4 {action} route {ip}/32 \"{_physicalAlias}\"";
            if (!string.IsNullOrEmpty(_physicalGateway))
            {
                command += $" {_physicalGateway}";
            }
            command += " metric=1 store=active";
            return command;
        }

        private bool AddDefaultRoutes()
        {
            string script = $@"
                $ErrorActionPreference = 'Stop'
                Remove-NetRoute -DestinationPrefix '0.0.0.0/1' -InterfaceAlias '{AdapterName}' -Confirm:$false -ErrorAction SilentlyContinue
                Remove-NetRoute -DestinationPrefix '128.0.0.0/1' -InterfaceAlias '{AdapterName}' -Confirm:$false -ErrorAction SilentlyContinue
                New-NetRoute -DestinationPrefix '0.0.0.0/1' -InterfaceAlias '{AdapterName}' -NextHop '0.0.0.0' -RouteMetric 1 -PolicyStore ActiveStore -ErrorAction Stop | Out-Null
                New-NetRoute -DestinationPrefix '128.0.0.0/1' -InterfaceAlias '{AdapterName}' -NextHop '0.0.0.0' -RouteMetric 1 -PolicyStore ActiveStore -ErrorAction Stop | Out-Null
                'ok'
            ";

            var (exitCode, stdout, stderr) = DnsManager.RunPowerShell(script, 10000);
            if (exitCode != 0)
            {
                Debug.WriteLine($"AddDefaultRoutes failed: {stderr}");
                return false;
            }
            return stdout.Contains("ok", StringComparison.OrdinalIgnoreCase);
        }

        private void RemoveDefaultRoutes()
        {
            DnsManager.RunPowerShell($@"
                Remove-NetRoute -DestinationPrefix '0.0.0.0/1' -InterfaceAlias '{AdapterName}' -Confirm:$false -ErrorAction SilentlyContinue
                Remove-NetRoute -DestinationPrefix '128.0.0.0/1' -InterfaceAlias '{AdapterName}' -Confirm:$false -ErrorAction SilentlyContinue
            ", 8000);
        }

        private void RemoveBypassRoutes(IEnumerable<string> ips)
        {
            var list = ips.Distinct(StringComparer.Ordinal).ToList();
            if (list.Count == 0 || string.IsNullOrEmpty(_physicalAlias)) return;

            var lines = new List<string>();
            foreach (string ip in list)
            {
                lines.Add($"interface ipv4 delete route {ip}/32 \"{_physicalAlias}\" store=active");
            }

            RunNetshScript(lines, 30000);
        }

        private void StartMonitor()
        {
            _monitorCts = new CancellationTokenSource();
            int pid = _corePid;
            _monitorTask = Task.Run(() => MonitorLoopAsync(pid, _monitorCts.Token));
        }

        private async Task MonitorLoopAsync(int corePid, CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(1000, token);
                }
                catch (OperationCanceledException)
                {
                    return;
                }

                if (_stopping) return;

                Process? process = _tunProcess;
                if (process == null || process.HasExited)
                {
                    return;
                }

                if (corePid <= 0) continue;

                List<string> endpoints;
                try
                {
                    endpoints = GetCoreEndpoints(corePid);
                }
                catch (Exception ex)
                {
                    Debug.WriteLine($"Endpoint monitor failed: {ex.Message}");
                    continue;
                }

                foreach (string ip in endpoints)
                {
                    bool known;
                    lock (_sync) { known = _routedIps.Contains(ip); }
                    if (known) continue;

                    if (AddSingleBypassRoute(ip))
                    {
                        Log("TUN", $"Pinned new tunnel endpoint {ip} to the physical gateway.");
                    }
                }
            }
        }

        private async Task ProbeTunnelAsync()
        {
            try
            {
                using var tcp = new TcpClient();
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
                await tcp.ConnectAsync("1.1.1.1", 443, timeout.Token);
                Log("TUN", "Tunnel data path verified (TCP 1.1.1.1:443).");
            }
            catch (Exception ex)
            {
                Log("TUN", $"Data path probe did not complete: {ex.Message}");
            }
        }

        public List<string> GetServerIps(string egressRegion)
        {
            var ips = new HashSet<string>(StringComparer.Ordinal);
            try
            {
                string path = Path.Combine(_baseDir, "servers.txt");
                if (!File.Exists(path)) return new List<string>();

                foreach (string rawLine in File.ReadLines(path))
                {
                    string hex = rawLine.Trim();
                    if (hex.Length == 0) continue;

                    try
                    {
                        string entry = Encoding.UTF8.GetString(Convert.FromHexString(hex));
                        int jsonStart = entry.IndexOf('{');
                        if (jsonStart < 0) continue;

                        using var doc = JsonDocument.Parse(entry.Substring(jsonStart));
                        var root = doc.RootElement;

                        if (!string.IsNullOrWhiteSpace(egressRegion) &&
                            root.TryGetProperty("region", out var regionEl) &&
                            !string.Equals(regionEl.GetString(), egressRegion, StringComparison.OrdinalIgnoreCase))
                        {
                            continue;
                        }

                        if (root.TryGetProperty("ipAddress", out var addressEl))
                        {
                            string? address = addressEl.GetString();
                            if (!string.IsNullOrWhiteSpace(address) && IsPublicIPv4(address))
                            {
                                ips.Add(address);
                            }
                        }
                    }
                    catch
                    {
                    }
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"GetServerIps failed: {ex.Message}");
            }

            return new List<string>(ips);
        }

        private static List<string> GetCoreEndpoints(int pid)
        {
            var result = new List<string>();
            try
            {
                int size = 0;
                uint status = GetExtendedTcpTable(IntPtr.Zero, ref size, false, AF_INET, TCP_TABLE_OWNER_PID_ALL, 0);
                if (status != ERROR_INSUFFICIENT_BUFFER || size <= 0) return result;

                IntPtr buffer = Marshal.AllocHGlobal(size);
                try
                {
                    status = GetExtendedTcpTable(buffer, ref size, false, AF_INET, TCP_TABLE_OWNER_PID_ALL, 0);
                    if (status != 0) return result;

                    int count = Marshal.ReadInt32(buffer);
                    IntPtr rowPointer = IntPtr.Add(buffer, 4);
                    int rowSize = Marshal.SizeOf<MIB_TCPROW_OWNER_PID>();

                    for (int i = 0; i < count; i++)
                    {
                        var row = Marshal.PtrToStructure<MIB_TCPROW_OWNER_PID>(rowPointer);
                        rowPointer = IntPtr.Add(rowPointer, rowSize);

                        if (row.OwningPid != (uint)pid) continue;
                        if (row.RemoteAddr == 0) continue;

                        var address = new IPAddress(new[]
                        {
                            (byte)row.RemoteAddr,
                            (byte)(row.RemoteAddr >> 8),
                            (byte)(row.RemoteAddr >> 16),
                            (byte)(row.RemoteAddr >> 24)
                        });

                        string text = address.ToString();
                        if (IsPublicIPv4(text)) result.Add(text);
                    }
                }
                finally
                {
                    Marshal.FreeHGlobal(buffer);
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"GetCoreEndpoints failed: {ex.Message}");
            }

            return result;
        }

        private static bool IsPublicIPv4(string address)
        {
            if (!IPAddress.TryParse(address, out var ip)) return false;
            if (ip.AddressFamily != AddressFamily.InterNetwork) return false;
            return IsPublicIPv4(ip);
        }

        private static bool IsPublicIPv4(IPAddress ip)
        {
            byte[] bytes = ip.GetAddressBytes();
            if (bytes[0] == 0 || bytes[0] == 10 || bytes[0] == 127) return false;
            if (bytes[0] == 169 && bytes[1] == 254) return false;
            if (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31) return false;
            if (bytes[0] == 192 && bytes[1] == 168) return false;
            if (bytes[0] == 100 && bytes[1] >= 64 && bytes[1] <= 127) return false;
            if (bytes[0] >= 224) return false;
            return true;
        }

        private (int ExitCode, string Stdout, string Stderr) RunProcess(string fileName, string arguments, int timeoutMs)
        {
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = fileName,
                    Arguments = arguments,
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true
                };

                using var process = Process.Start(psi);
                if (process == null) return (-1, "", "Failed to start process.");

                var stderrTask = Task.Run(() => process.StandardError.ReadToEnd());
                string stdout = process.StandardOutput.ReadToEnd();
                if (!process.WaitForExit(timeoutMs))
                {
                    try { process.Kill(true); } catch { }
                    return (-1, stdout, "Process timed out.");
                }

                return (process.ExitCode, stdout.Trim(), stderrTask.GetAwaiter().GetResult().Trim());
            }
            catch (Exception ex)
            {
                return (-1, "", ex.Message);
            }
        }

        private void RunNetshScript(IEnumerable<string> commands, int timeoutMs)
        {
            string path = Path.Combine(Path.GetTempPath(), $"beevpn_tun_{Guid.NewGuid():N}.txt");
            try
            {
                File.WriteAllLines(path, commands, Encoding.ASCII);
                var (exitCode, stdout, stderr) = RunProcess("netsh.exe", $"-f \"{path}\"", timeoutMs);
                if (exitCode != 0)
                {
                    Debug.WriteLine($"netsh script exited {exitCode}: {stdout} {stderr}");
                    Log("TUN", "Some route changes were rejected by Windows; continuing with the rest.");
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"netsh script failed: {ex.Message}");
            }
            finally
            {
                try { if (File.Exists(path)) File.Delete(path); } catch { }
            }
        }

        private void WriteState()
        {
            try
            {
                var dto = new
                {
                    tun2SocksPid = _tunProcess?.Id ?? 0,
                    physicalAlias = _physicalAlias,
                    physicalGateway = _physicalGateway,
                    bypassRoutes = _routedIps.ToArray()
                };

                Directory.CreateDirectory(Path.GetDirectoryName(_statePath)!);
                File.WriteAllText(_statePath, JsonSerializer.Serialize(dto, new JsonSerializerOptions { WriteIndented = false }));
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to persist TUN state: {ex.Message}");
            }
        }

        private void DeleteState()
        {
            try
            {
                if (File.Exists(_statePath)) File.Delete(_statePath);
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to delete TUN state: {ex.Message}");
            }
        }

        private void AppendTunLog(string? line)
        {
            if (string.IsNullOrWhiteSpace(line)) return;

            Log("TUN", line.Trim());

            lock (_tunLog)
            {
                _tunLog.Enqueue(line.Trim());
                while (_tunLog.Count > 12) _tunLog.Dequeue();
            }
        }

        private readonly Queue<string> _tunLog = new();

        private string GetTunLogTail()
        {
            lock (_tunLog)
            {
                if (_tunLog.Count == 0) return "";
                return " Last output: " + string.Join(" | ", _tunLog);
            }
        }

        private void Log(string tag, string message)
        {
            LogMessage?.Invoke(tag, message);
        }

        /// <summary>
        /// Removes routes, the adapter address, and a leftover tun2socks process
        /// after a crash or forced termination.
        /// </summary>
        public static void CleanupStaleState()
        {
            string statePath = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "BeeVPN",
                "tun_state.json");

            try
            {
                if (!File.Exists(statePath)) return;

                string json = File.ReadAllText(statePath);
                using var doc = JsonDocument.Parse(json);
                var root = doc.RootElement;

                int pid = root.TryGetProperty("tun2SocksPid", out var pidEl) ? pidEl.GetInt32() : 0;
                string alias = root.TryGetProperty("physicalAlias", out var aliasEl) ? aliasEl.GetString() ?? "" : "";
                var routes = new List<string>();
                if (root.TryGetProperty("bypassRoutes", out var routesEl) && routesEl.ValueKind == JsonValueKind.Array)
                {
                    foreach (var routeEl in routesEl.EnumerateArray())
                    {
                        string? route = routeEl.GetString();
                        if (!string.IsNullOrWhiteSpace(route)) routes.Add(route);
                    }
                }

                if (pid > 0)
                {
                    try
                    {
                        using var process = Process.GetProcessById(pid);
                        string expectedDir = Path.Combine(AppContext.BaseDirectory, "third_party", "tun2socks");
                        string? exePath = process.MainModule?.FileName;
                        if (process.ProcessName.Equals("tun2socks", StringComparison.OrdinalIgnoreCase) &&
                            exePath != null &&
                            exePath.StartsWith(expectedDir, StringComparison.OrdinalIgnoreCase))
                        {
                            process.Kill(true);
                            process.WaitForExit(2000);
                        }
                    }
                    catch
                    {
                    }
                }

                DnsManager.RunPowerShell($@"
                    Remove-NetRoute -DestinationPrefix '0.0.0.0/1' -InterfaceAlias '{AdapterName}' -Confirm:$false -ErrorAction SilentlyContinue
                    Remove-NetRoute -DestinationPrefix '128.0.0.0/1' -InterfaceAlias '{AdapterName}' -Confirm:$false -ErrorAction SilentlyContinue
                    Remove-NetIPAddress -InterfaceAlias '{AdapterName}' -IPAddress '{TunAddress}' -Confirm:$false -ErrorAction SilentlyContinue
                ", 8000);

                if (routes.Count > 0 && !string.IsNullOrEmpty(alias))
                {
                    var lines = routes
                        .Where(IsPublicIPv4)
                        .Select(ip => $"interface ipv4 delete route {ip}/32 \"{alias}\" store=active")
                        .ToList();

                    if (lines.Count > 0)
                    {
                        string scriptPath = Path.Combine(Path.GetTempPath(), $"beevpn_tun_cleanup_{Guid.NewGuid():N}.txt");
                        try
                        {
                            File.WriteAllLines(scriptPath, lines, Encoding.ASCII);
                            using var netsh = Process.Start(new ProcessStartInfo
                            {
                                FileName = "netsh.exe",
                                Arguments = $"-f \"{scriptPath}\"",
                                UseShellExecute = false,
                                CreateNoWindow = true,
                                RedirectStandardOutput = true,
                                RedirectStandardError = true
                            });
                            netsh?.WaitForExit(30000);
                        }
                        finally
                        {
                            try { if (File.Exists(scriptPath)) File.Delete(scriptPath); } catch { }
                        }
                    }
                }

                try { File.Delete(statePath); } catch { }
                Debug.WriteLine("Cleaned stale Wintun tunnel state.");
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"CleanupStaleState failed: {ex.Message}");
            }
        }
    }
}
