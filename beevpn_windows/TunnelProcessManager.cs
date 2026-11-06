using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
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
        public bool IsRunning => _tunnelProcess != null && !_tunnelProcess.HasExited;
        public bool IsTunnelActive { get; private set; } = false;

        public event Action<string, string>? LogMessage;
        public event Action<int, int>? PortsReady;
        public event Action<string, string>? TunnelConnected;
        public event Action<long, long>? BytesTransferred;
        public event Action? TunnelDisconnected;

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

        public void Start()
        {
            if (IsRunning) return;

            if (!File.Exists(_coreExePath))
            {
                Log("ERROR", $"psiphon-tunnel-core.exe not found at {_coreExePath}");
                return;
            }

            EnsureConfigFile();

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
            while (!token.IsCancellationRequested && !reader.EndOfStream)
            {
                string? line = await reader.ReadLineAsync(token);
                if (string.IsNullOrWhiteSpace(line)) continue;

                ProcessJsonNotice(line);
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
                        IsTunnelActive = true;
                        TunnelConnected?.Invoke(crConn, cpConn);
                        break;

                    case "ActiveTunnel":
                    case "Tunnels":
                        Log("STATUS", "Encrypted tunnel online and routing traffic.");
                        IsTunnelActive = true;
                        TunnelConnected?.Invoke("Auto", "Psiphon");
                        break;

                    case "BytesTransferred":
                        long sent = data.TryGetProperty("sent", out var s) ? s.GetInt64() : 0;
                        long received = data.TryGetProperty("received", out var r) ? r.GetInt64() : 0;
                        BytesTransferred?.Invoke(received, sent);
                        break;

                    case "AvailableEgressRegions":
                        Log("REGIONS", "Available exit regions updated.");
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

        private void EnsureConfigFile()
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
                ["EgressRegion"] = "",
                ["EmitDiagnosticNotices"] = true,
                ["EmitDiagnosticNetworkParameters"] = true,
                ["EmitBytesTransferred"] = true,
                ["DisableTimeouts"] = true,
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

            if (!string.IsNullOrEmpty(additionalParams))
            {
                configObj["AdditionalParameters"] = additionalParams;
            }

            var options = new JsonSerializerOptions { WriteIndented = true };
            string json = JsonSerializer.Serialize(configObj, options);
            File.WriteAllText(_configPath, json);
        }
    }
}
