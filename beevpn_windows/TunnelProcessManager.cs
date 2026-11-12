using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace BeeVPN
{
    public class TunnelProcessManager
    {
        private Process? _tunnelProcess;
        private CancellationTokenSource? _cts;
        private readonly string _appDataDir;
        private readonly string _configPath;
        private readonly string _dataDir;
        private readonly string _coreExePath;
        private readonly string _serversPath;

        public int SocksPort { get; private set; } = 0;
        public int HttpPort { get; private set; } = 0;
        public int CorePid => _tunnelProcess?.Id ?? 0;
        public bool IsRunning => _tunnelProcess != null && !_tunnelProcess.HasExited;
        public bool IsTunnelActive { get; private set; } = false;

        public event Action<string, string>? LogMessage;
        public event Action<int, int>? PortsReady;
        public event Action<string, string>? TunnelConnected;
        public event Action<long, long>? BytesTransferred;
        public event Action? TunnelDisconnected;
        public event Action<List<string>>? RegionsAvailable;

        public TunnelProcessManager()
        {
            string baseDir = AppContext.BaseDirectory;
            _coreExePath = Path.Combine(baseDir, "psiphon-tunnel-core.exe");
            _serversPath = Path.Combine(baseDir, "servers.txt");

            _appDataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BeeVPN");
            _dataDir = Path.Combine(_appDataDir, "data");
            _configPath = Path.Combine(_appDataDir, "psiphon.config");

            Directory.CreateDirectory(_appDataDir);
            Directory.CreateDirectory(_dataDir);
        }

        public void Start(string egressRegion = "", bool fastProtocols = false, bool tcpOnly = false)
        {
            if (IsRunning) return;

            if (!File.Exists(_coreExePath))
            {
                Log("ERROR", $"psiphon-tunnel-core.exe not found at {_coreExePath}");
                return;
            }

            // Full-device capture drops all non-DNS UDP, which would sever a
            // WebRTC/QUIC transport, so only TCP-based protocols are permitted.
            // Server tactics can override LimitTunnelProtocols, so tactics are
            // disabled for this mode; otherwise the core re-selects WebRTC.
            if (fastProtocols || tcpOnly)
            {
                Log("CORE", tcpOnly
                    ? "Full-device tunnel: using TCP-only protocols (OSSH, TLS-OSSH) with server tactics disabled."
                    : "Fast profile: using OSSH/TLS-OSSH only with server tactics disabled.");
            }

            EnsureConfigFile(egressRegion, fastProtocols || tcpOnly, fastProtocols || tcpOnly);

            _cts = new CancellationTokenSource();
            SocksPort = 0;
            HttpPort = 0;
            IsTunnelActive = false;

            var psi = new ProcessStartInfo
            {
                FileName = _coreExePath,
                Arguments = $"-config \"{_configPath}\" -serverList \"{_serversPath}\" -dataRootDirectory \"{_dataDir}\"",
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
                WorkingDirectory = _appDataDir
            };

            try
            {
                _tunnelProcess = new Process { StartInfo = psi, EnableRaisingEvents = true };
                _tunnelProcess.Exited += (s, e) =>
                {
                    Log("CORE", "Psiphon tunnel core process exited.");
                    IsTunnelActive = false;
                    TunnelDisconnected?.Invoke();
                };

                _tunnelProcess.Start();
                Log("CORE", "Starting Psiphon Circumvention Core v495 (Speed Optimized)...");

                Task.Run(() => ReadStreamAsync(_tunnelProcess.StandardError, _cts.Token));
                Task.Run(() => ReadStreamAsync(_tunnelProcess.StandardOutput, _cts.Token));
            }
            catch (Exception ex)
            {
                Log("ERROR", $"Failed to launch core: {ex.Message}");
                Stop();
            }
        }

        public void Stop()
        {
            try
            {
                _cts?.Cancel();
                if (_tunnelProcess != null && !_tunnelProcess.HasExited)
                {
                    _tunnelProcess.Kill(true);
                    _tunnelProcess.WaitForExit(2000);
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Error stopping tunnel process: {ex.Message}");
            }
            finally
            {
                _tunnelProcess = null;
                IsTunnelActive = false;
                SocksPort = 0;
                HttpPort = 0;
            }
        }

        private async Task ReadStreamAsync(StreamReader reader, CancellationToken token)
        {
            try
            {
                while (!token.IsCancellationRequested && !reader.EndOfStream)
                {
                    string? line = await reader.ReadLineAsync(token);
                    if (string.IsNullOrWhiteSpace(line)) continue;

                    ProcessJsonNotice(line);
                }
            }
            catch (OperationCanceledException)
            {
                // Cancellation requested on Stop
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"ReadStreamAsync exception: {ex.Message}");
            }
        }

        private void ProcessJsonNotice(string jsonLine)
        {
            try
            {
                using var doc = JsonDocument.Parse(jsonLine);
                var root = doc.RootElement;

                string noticeType = root.TryGetProperty("noticeType", out var nt) ? nt.GetString() ?? "" : "";
                if (!root.TryGetProperty("data", out var data)) return;

                switch (noticeType)
                {
                    case "ListeningSocksProxyPort":
                        if (data.TryGetProperty("port", out var sp))
                        {
                            SocksPort = sp.GetInt32();
                            Log("SOCKS", $"Psiphon SOCKS proxy ready on port {SocksPort}");
                            CheckPortsReady();
                        }
                        break;

                    case "ListeningHttpProxyPort":
                        if (data.TryGetProperty("port", out var hp))
                        {
                            HttpPort = hp.GetInt32();
                            Log("HTTP", $"Psiphon HTTP proxy ready on port {HttpPort}");
                            CheckPortsReady();
                        }
                        break;

                    case "CandidateServers":
                        int count = data.TryGetProperty("count", out var c) ? c.GetInt32() : 0;
                        Log("FLEET", $"Candidate servers loaded: {count}");
                        break;

                    case "ConnectingServer":
                        string cr = data.TryGetProperty("region", out var reg) ? reg.GetString() ?? "Auto" : "Auto";
                        string cp = data.TryGetProperty("protocol", out var proto) ? proto.GetString() ?? "OSSH" : "OSSH";
                        Log("PROBE", $"Connecting to server (region={cr}, proto={cp})...");
                        break;

                    case "ConnectedServer":
                        string crConn = data.TryGetProperty("region", out var rConn) ? rConn.GetString() ?? "Global" : "Global";
                        string cpConn = data.TryGetProperty("protocol", out var pConn) ? pConn.GetString() ?? "OSSH" : "OSSH";
                        Log("ACTIVE", $"Connected to {crConn} server via {cpConn}");
                        if (!IsTunnelActive)
                        {
                            IsTunnelActive = true;
                            TunnelConnected?.Invoke(crConn, cpConn);
                        }
                        break;

                    case "ActiveTunnel":
                    case "Tunnels":
                        Log("STATUS", "Encrypted tunnel online and routing traffic.");
                        if (!IsTunnelActive)
                        {
                            IsTunnelActive = true;
                            TunnelConnected?.Invoke("Auto", "Psiphon");
                        }
                        break;

                    case "BytesTransferred":
                        long sent = data.TryGetProperty("sent", out var s) ? s.GetInt64() : 0;
                        long received = data.TryGetProperty("received", out var r) ? r.GetInt64() : 0;
                        BytesTransferred?.Invoke(received, sent);
                        break;

                    case "AvailableEgressRegions":
                        var regions = new List<string>();
                        if (data.TryGetProperty("regions", out var regionsEl) && regionsEl.ValueKind == JsonValueKind.Array)
                        {
                            foreach (var regionEl in regionsEl.EnumerateArray())
                            {
                                string? region = regionEl.GetString();
                                if (!string.IsNullOrEmpty(region)) regions.Add(region);
                            }
                        }
                        Log("REGIONS", $"Available exit regions: {regions.Count}.");
                        if (regions.Count > 0) RegionsAvailable?.Invoke(regions);
                        break;

                    case "Alert":
                    case "Warning":
                        string msg = data.TryGetProperty("message", out var m) ? m.GetString() ?? "" : "";
                        if (!string.IsNullOrEmpty(msg) && !msg.Contains("UDPMuxDefault"))
                        {
                            Log("WARN", msg);
                        }
                        break;

                    case "Error":
                        string errMsg = data.TryGetProperty("message", out var em) ? em.GetString() ?? "" : "";
                        if (!string.IsNullOrEmpty(errMsg))
                        {
                            Log("WARN", errMsg);
                        }
                        break;
                }
            }
            catch
            {
                // Raw non-JSON line
                if (jsonLine.Length < 120)
                {
                    Log("DIAG", jsonLine);
                }
            }
        }

        private void CheckPortsReady()
        {
            if (SocksPort > 0 && HttpPort > 0)
            {
                PortsReady?.Invoke(SocksPort, HttpPort);
            }
        }

        private void Log(string tag, string msg)
        {
            LogMessage?.Invoke(tag, msg);
        }

        /// <summary>
        /// Reads the regions present in the embedded server list, so the picker can be
        /// populated before the first connection (live notices refine it later).
        /// </summary>
        public static List<string> GetEmbeddedRegions()
        {
            var regions = new SortedSet<string>(StringComparer.Ordinal);
            try
            {
                string path = Path.Combine(AppContext.BaseDirectory, "servers.txt");
                if (!File.Exists(path)) return new List<string>();

                foreach (string line in File.ReadLines(path))
                {
                    string hex = line.Trim();
                    if (hex.Length == 0) continue;

                    try
                    {
                        string entry = Encoding.UTF8.GetString(Convert.FromHexString(hex));
                        int jsonStart = entry.IndexOf('{');
                        if (jsonStart < 0) continue;

                        using var doc = JsonDocument.Parse(entry.Substring(jsonStart));
                        if (doc.RootElement.TryGetProperty("region", out var regionEl))
                        {
                            string? region = regionEl.GetString();
                            if (!string.IsNullOrEmpty(region)) regions.Add(region);
                        }
                    }
                    catch
                    {
                        // Skip malformed entries
                    }
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to read embedded regions: {ex.Message}");
            }
            return new List<string>(regions);
        }

        private void EnsureConfigFile(string egressRegion, bool fastProtocols, bool disableTactics = false)
        {
            string baseDir = AppContext.BaseDirectory;
            string addParamsPath = Path.Combine(baseDir, "additional_parameters.txt");
            string additionalParams = File.Exists(addParamsPath) ? File.ReadAllText(addParamsPath).Trim() : "";

            var configObj = new Dictionary<string, object>
            {
                ["ClientVersion"] = "495",
                ["PropagationChannelId"] = "EE0B7486ACAE75AA",
                ["SponsorId"] = "67DDC3614847FCEA",
                ["DataRootDirectory"] = _dataDir,
                ["TunnelWholeDevice"] = 0,
                ["EgressRegion"] = string.IsNullOrWhiteSpace(egressRegion) ? "" : egressRegion.Trim().ToUpperInvariant(),
                ["EmitDiagnosticNotices"] = true,
                ["EmitDiagnosticNetworkParameters"] = true,
                ["EmitBytesTransferred"] = true,
                ["NetworkLatencyMultiplierLambda"] = 0.05,
                ["RemoteServerListURLs"] = new object[]
                {
                    new { URL = "aHR0cHM6Ly9zMy5hbWF6b25hd3MuY29tL3BzaXBob24vd2ViL2lvaHEtd2FhNC1xNGR0L3NlcnZlcl9saXN0X2NvbXByZXNzZWQ=", OnlyAfterAttempts = 0, SkipVerify = false },
                    new { URL = "aHR0cHM6Ly93d3cuY29ycG9yYXRlaGlyZXByZXNzdGguY29tL3dlYi9pb2hxLXdhYTQtcTRkdC9zZXJ2ZXJfbGlzdF9jb21wcmVzc2Vk", OnlyAfterAttempts = 2, SkipVerify = true },
                    new { URL = "aHR0cHM6Ly93d3cuYnJhbmRpbmd1c2FnYW1lcmVwLmNvbS93ZWIvaW9ocS13YWE0LXE0ZHQvc2VydmVyX2xpc3RfY29tcHJlc3NlZA==", OnlyAfterAttempts = 2, SkipVerify = true },
                    new { URL = "aHR0cHM6Ly93d3cuaGVyYm14ZGlpbmNvcnBvcmF0ZWQuY29tL3dlYi9pb2hxLXdhYTQtcTRkdC9zZXJ2ZXJfbGlzdF9jb21wcmVzc2Vk", OnlyAfterAttempts = 2, SkipVerify = true }
                },
                ["ObfuscatedServerListRootURLs"] = new object[]
                {
                    new { URL = "aHR0cHM6Ly9zMy5hbWF6b25hd3MuY29tL3BzaXBob24vd2ViL2lvaHEtd2FhNC1xNGR0L29zbA==", OnlyAfterAttempts = 0, SkipVerify = false },
                    new { URL = "aHR0cHM6Ly93d3cuY29ycG9yYXRlaGlyZXByZXNzdGguY29tL3dlYi9pb2hxLXdhYTQtcTRkdC9vc2w=", OnlyAfterAttempts = 2, SkipVerify = true },
                    new { URL = "aHR0cHM6Ly93d3cuYnJhbmRpbmd1c2FnYW1lcmVwLmNvbS93ZWIvaW9ocS13YWE0LXE0ZHQvb3Ns", OnlyAfterAttempts = 2, SkipVerify = true },
                    new { URL = "aHR0cHM6Ly93d3cuaGVyYm14ZGlpbmNvcnBvcmF0ZWQuY29tL3dlYi9pb2hxLXdhYTQtcTRkdC9vc2w=", OnlyAfterAttempts = 2, SkipVerify = true }
                },
                ["RemoteServerListSignaturePublicKey"] = "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM=",
                ["ServerEntrySignaturePublicKey"] = "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA=",
                ["ExchangeObfuscationKey"] = "DpXzloJk1Hw6aSzmKKky0xcahsEHubch81Mi6K0XMlU=",
                ["DNSResolverAlternateServers"] = new[] { "1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4" }
            };

            if (fastProtocols)
            {
                configObj["LimitTunnelProtocols"] = new[] { "OSSH", "TLS-OSSH" };
            }

            if (disableTactics)
            {
                configObj["DisableTactics"] = true;
            }

            if (!string.IsNullOrEmpty(additionalParams) && IsEncryptedParametersBlob(additionalParams))
            {
                configObj["AdditionalParameters"] = additionalParams;
            }
            else if (!string.IsNullOrEmpty(additionalParams))
            {
                Debug.WriteLine("additional_parameters.txt is not a valid encrypted parameters blob; ignoring it.");
            }

            var options = new JsonSerializerOptions { WriteIndented = true };
            string json = JsonSerializer.Serialize(configObj, options);
            File.WriteAllText(_configPath, json);
        }

        // The core treats this field as base64 (32-byte key + NaCl secretbox ciphertext
        // of a Config overlay), not JSON. Injecting anything else makes Config.Commit()
        // fail and the core exits before connecting.
        private static bool IsEncryptedParametersBlob(string value)
        {
            try
            {
                return Convert.FromBase64String(value.Trim()).Length >= 32;
            }
            catch (FormatException)
            {
                return false;
            }
        }
    }
}
