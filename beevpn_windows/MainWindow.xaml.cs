using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Win32;
using Forms = System.Windows.Forms;

namespace BeeVPN
{
    public partial class MainWindow : Window
    {
        private readonly TunnelProcessManager _tunnelManager;
        private readonly WintunTunnelManager _wintunTunnel;
        private readonly DispatcherTimer _statsTimer;
        private readonly DispatcherTimer _connectTimeoutTimer;
        private DateTime _connectTime;
        private int _selectedMode = 0; // 0 = Psiphon, 1 = Cloudflare
        private bool _isConnected = false;
        private bool _isConnecting = false;
        private bool _isSplitTunnel = false;
        private bool _fullTunnel = false;
        private bool _fullTunnelFallback = false;
        private bool _isIntentionalStop = false;
        private bool _suppressOptionSave = false;

        private long _rxBytes = 0;
        private long _txBytes = 0;
        private int _measuredLatency = -1;
        private DateTime _lastLatencyCheck = DateTime.MinValue;

        // System Tray Components
        private readonly Forms.NotifyIcon _notifyIcon;
        private readonly Forms.ContextMenuStrip _trayMenu;
        private readonly Forms.ToolStripMenuItem _menuItemOpen;
        private readonly Forms.ToolStripMenuItem _menuItemToggleConnect;
        private readonly Forms.ToolStripMenuItem _menuItemStatus;
        private readonly Forms.ToolStripMenuItem _menuItemExit;

        public MainWindow()
        {
            InitializeComponent();

            _tunnelManager = new TunnelProcessManager();
            _tunnelManager.LogMessage += OnTunnelLog;
            _tunnelManager.PortsReady += OnPortsReady;
            _tunnelManager.TunnelConnected += OnTunnelConnected;
            _tunnelManager.BytesTransferred += OnBytesTransferred;
            _tunnelManager.TunnelDisconnected += OnTunnelDisconnected;
            _tunnelManager.RegionsAvailable += OnRegionsAvailable;

            _wintunTunnel = new WintunTunnelManager();
            _wintunTunnel.LogMessage += OnTunnelLog;
            _wintunTunnel.Failed += OnWintunFailed;

            _statsTimer = new DispatcherTimer
            {
                Interval = TimeSpan.FromSeconds(1)
            };
            _statsTimer.Tick += StatsTimer_Tick;

            _connectTimeoutTimer = new DispatcherTimer
            {
                Interval = TimeSpan.FromSeconds(90)
            };
            _connectTimeoutTimer.Tick += ConnectTimeoutTimer_Tick;

            _isSplitTunnel = SplitTunnelManager.Settings.IsSplitTunnel;
            _fullTunnel = SplitTunnelManager.Settings.FullTunnel;
            chkFullTunnel.IsChecked = _fullTunnel;
            UpdateSplitTunnelUi();
            UpdateFullTunnelUi();
            InitializeConnectionOptions();

            // Initialize System Tray Menu
            _trayMenu = new Forms.ContextMenuStrip();
            _menuItemOpen = new Forms.ToolStripMenuItem("🐝 Open BeeVPN") { Font = new Font(Forms.Control.DefaultFont, System.Drawing.FontStyle.Bold) };
            _menuItemOpen.Click += (s, e) => RestoreFromTray();

            _menuItemToggleConnect = new Forms.ToolStripMenuItem("Connect");
            _menuItemToggleConnect.Click += (s, e) => Dispatcher.Invoke(() => BtnToggleVpn_Click(this, new RoutedEventArgs()));

            _menuItemStatus = new Forms.ToolStripMenuItem("Status: Disconnected") { Enabled = false };

            _menuItemExit = new Forms.ToolStripMenuItem("Exit");
            _menuItemExit.Click += (s, e) => Dispatcher.Invoke(ExitApplication);

            _trayMenu.Items.Add(_menuItemOpen);
            _trayMenu.Items.Add(new Forms.ToolStripSeparator());
            _trayMenu.Items.Add(_menuItemToggleConnect);
            _trayMenu.Items.Add(_menuItemStatus);
            _trayMenu.Items.Add(new Forms.ToolStripSeparator());
            _trayMenu.Items.Add(_menuItemExit);

            // Initialize System Tray Icon
            _notifyIcon = new Forms.NotifyIcon
            {
                ContextMenuStrip = _trayMenu,
                Visible = true,
                Text = "BeeVPN - Disconnected"
            };

            string icoPath = Path.Combine(AppContext.BaseDirectory, "app_icon.ico");
            if (File.Exists(icoPath))
            {
                try
                {
                    _notifyIcon.Icon = new Icon(icoPath);
                }
                catch
                {
                    _notifyIcon.Icon = SystemIcons.Shield;
                }
            }
            else
            {
                _notifyIcon.Icon = SystemIcons.Shield;
            }

            _notifyIcon.DoubleClick += (s, e) => RestoreFromTray();
            _notifyIcon.MouseClick += (s, e) =>
            {
                if (e.Button == Forms.MouseButtons.Left)
                {
                    RestoreFromTray();
                }
            };

            // Recover Internet Settings captured before a crash or forced termination
            WintunTunnelManager.CleanupStaleState();
            ProxyManager.RestoreBackupIfAny();
            DnsManager.RestoreBackupIfAny();
            ProxyManager.CleanOrphanedProxy();

            UpdateUiState();
            AppendLog("CORE", "BeeVPN Windows Edition initialized.");
            AppendLog("CORE", "Pre-seeded broker tactics & 405 verified servers active.");

            if (HasCommandLineArg("--autoconnect"))
            {
                Dispatcher.BeginInvoke(new Action(() =>
                {
                    AppendLog("UI", "Elevated restart detected. Reconnecting...");
                    ConnectVpn();
                }), DispatcherPriority.Background);
            }
        }

        private static bool HasCommandLineArg(string arg)
        {
            foreach (string value in Environment.GetCommandLineArgs())
            {
                if (string.Equals(value, arg, StringComparison.OrdinalIgnoreCase)) return true;
            }
            return false;
        }

        private void TitleBar_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ChangedButton == MouseButton.Left)
            {
                DragMove();
            }
        }

        private void BtnMinimize_Click(object sender, RoutedEventArgs e)
        {
            MinimizeToTray();
        }

        private void BtnClose_Click(object sender, RoutedEventArgs e)
        {
            MinimizeToTray();
        }

        private void MinimizeToTray()
        {
            Hide();
            _notifyIcon.ShowBalloonTip(
                1200,
                "BeeVPN Docked to Tray",
                "BeeVPN is running in the background. Click the bee icon to reopen.",
                Forms.ToolTipIcon.Info
            );
        }

        private void RestoreFromTray()
        {
            Show();
            WindowState = WindowState.Normal;
            Activate();
            Topmost = true;
            Topmost = false;
            Focus();
        }

        private void ExitApplication()
        {
            if (_isConnected || _isConnecting)
            {
                var result = System.Windows.MessageBox.Show(
                    "A VPN tunnel is currently active. Disconnect and exit BeeVPN?",
                    "BeeVPN Exit",
                    MessageBoxButton.YesNo,
                    MessageBoxImage.Question
                );
                if (result != MessageBoxResult.Yes) return;
            }

            DisconnectVpn();
            _notifyIcon.Visible = false;
            _notifyIcon.Dispose();
            _trayMenu.Dispose();
            System.Windows.Application.Current.Shutdown();
        }

        protected override void OnClosed(EventArgs e)
        {
            _notifyIcon.Visible = false;
            _notifyIcon.Dispose();
            _trayMenu.Dispose();
            base.OnClosed(e);
        }

        private void ComboMode_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (!IsLoaded) return;

            _selectedMode = comboMode.SelectedIndex;
            if (_selectedMode == 0)
            {
                UpdateServerCard();
                cardAppRouting.Visibility = Visibility.Visible;
                AppendLog("UI", "Selected mode: Psiphon Circumvention (Global Bypass)");
            }
            else
            {
                tvServerTitle.Text = "Cloudflare DNS (1.1.1.1)";
                tvServerSub.Text = "Fast public DNS resolver (bypasses DNS filtering)";
                cardAppRouting.Visibility = Visibility.Collapsed;
                AppendLog("UI", "Selected mode: Cloudflare DNS (1.1.1.1 Fast)");
            }
            UpdateOptionAvailability();
        }

        private void InitializeConnectionOptions()
        {
            _suppressOptionSave = true;

            comboRegion.Items.Clear();
            comboRegion.Items.Add(CreateOptionItem("Auto (Fastest)"));
            foreach (string region in TunnelProcessManager.GetEmbeddedRegions())
            {
                comboRegion.Items.Add(CreateOptionItem(region));
            }

            string savedRegion = SplitTunnelManager.Settings.EgressRegion;
            comboRegion.SelectedIndex = 0;
            if (!string.IsNullOrEmpty(savedRegion))
            {
                foreach (var item in comboRegion.Items)
                {
                    if (item is ComboBoxItem option &&
                        string.Equals(option.Content as string, savedRegion, StringComparison.OrdinalIgnoreCase))
                    {
                        comboRegion.SelectedItem = option;
                        break;
                    }
                }
            }

            comboProtocol.SelectedIndex = SplitTunnelManager.Settings.FastProtocols ? 0 : 1;
            _suppressOptionSave = false;

            UpdateServerCard();
            UpdateOptionAvailability();
        }

        private static ComboBoxItem CreateOptionItem(string text)
        {
            return new ComboBoxItem
            {
                Content = text,
                Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0xFF, 0xFF, 0xFF)),
                Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x22, 0x22, 0x22))
            };
        }

        private string GetSelectedRegion()
        {
            if (comboRegion.SelectedItem is ComboBoxItem item &&
                item.Content is string text &&
                !string.Equals(text, "Auto (Fastest)", StringComparison.Ordinal))
            {
                return text;
            }
            return "";
        }

        private bool IsFastProfileSelected()
        {
            return comboProtocol.SelectedIndex == 0;
        }

        private void UpdateServerCard()
        {
            if (_selectedMode != 0) return;

            string region = GetSelectedRegion();
            if (string.IsNullOrEmpty(region))
            {
                tvServerTitle.Text = "Psiphon Fleet (Auto)";
                tvServerSub.Text = "Global obfuscated circumvention network";
            }
            else
            {
                tvServerTitle.Text = $"Psiphon Fleet ({region})";
                tvServerSub.Text = $"Exit region pinned to {region}";
            }
        }

        private void UpdateOptionAvailability()
        {
            bool canEdit = _selectedMode == 0 && !_isConnected && !_isConnecting;
            comboRegion.IsEnabled = canEdit;
            comboProtocol.IsEnabled = canEdit;
            chkFullTunnel.IsEnabled = !_isConnected && !_isConnecting;
        }

        private void ChkFullTunnel_Changed(object sender, RoutedEventArgs e)
        {
            if (!IsLoaded) return;

            _fullTunnel = chkFullTunnel.IsChecked == true;
            SplitTunnelManager.Settings.FullTunnel = _fullTunnel;
            SplitTunnelManager.SaveSettings();
            UpdateFullTunnelUi();

            AppendLog("UI", _fullTunnel
                ? "Full-device tunnel enabled: all apps will route through Wintun (requires Administrator)."
                : "Full-device tunnel disabled: traffic uses the Windows system proxy or split tunnel.");
        }

        private void UpdateFullTunnelUi()
        {
            if (tvFullTunnelHint != null)
            {
                bool admin = ElevationHelper.IsAdministrator;
                if (!_fullTunnel)
                {
                    tvFullTunnelHint.Text = "Routes every app through the encrypted tunnel via Wintun.";
                    tvFullTunnelHint.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x66, 0x66, 0x66));
                }
                else if (admin)
                {
                    tvFullTunnelHint.Text = "All apps tunneled over TCP; DNS is proxied, other UDP is dropped.";
                    tvFullTunnelHint.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x00, 0xE6, 0x76));
                }
                else
                {
                    tvFullTunnelHint.Text = "Administrator approval will be requested on connect.";
                    tvFullTunnelHint.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0xFF, 0xB8, 0x00));
                }
            }

            cardAppRouting.IsEnabled = !_fullTunnel;
            cardAppRouting.Opacity = _fullTunnel ? 0.4 : 1.0;
        }

        private void ComboRegion_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (_suppressOptionSave || !IsLoaded) return;

            string region = GetSelectedRegion();
            SplitTunnelManager.Settings.EgressRegion = region;
            SplitTunnelManager.SaveSettings();

            AppendLog("UI", string.IsNullOrEmpty(region)
                ? "Exit region: Auto (fastest available)"
                : $"Exit region: {region}");
            UpdateServerCard();
        }

        private void ComboProtocol_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (_suppressOptionSave || !IsLoaded) return;

            bool fast = IsFastProfileSelected();
            SplitTunnelManager.Settings.FastProtocols = fast;
            SplitTunnelManager.SaveSettings();

            AppendLog("UI", fast
                ? "Protocol profile: Fast (OSSH + TLS-OSSH, tactics disabled). May fail on heavily filtered networks."
                : "Protocol profile: Compatible (all protocols, slower fallback under filtering).");
        }

        private void OnRegionsAvailable(List<string> regions)
        {
            Dispatcher.Invoke(() =>
            {
                var existing = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                foreach (var item in comboRegion.Items)
                {
                    if (item is ComboBoxItem option &&
                        option.Content is string text &&
                        !string.Equals(text, "Auto (Fastest)", StringComparison.Ordinal))
                    {
                        existing.Add(text);
                    }
                }

                foreach (string region in regions)
                {
                    if (existing.Add(region))
                    {
                        comboRegion.Items.Add(CreateOptionItem(region));
                    }
                }
            });
        }

        private void BtnToggleVpn_Click(object sender, RoutedEventArgs e)
        {
            if (_isConnected || _isConnecting)
            {
                DisconnectVpn();
            }
            else
            {
                ConnectVpn();
            }
        }

        private void ConnectVpn()
        {
            if (_selectedMode == 0 && _fullTunnel && !ElevationHelper.IsAdministrator)
            {
                AppendLog("TUN", "Full-device tunnel needs administrator rights. Requesting elevation...");

                App.ReleaseInstanceMutex();

                if (ElevationHelper.RelaunchElevated(new[] { "--autoconnect" }))
                {
                    _notifyIcon.Visible = false;
                    _notifyIcon.Dispose();
                    _trayMenu.Dispose();
                    System.Windows.Application.Current.Shutdown();
                    return;
                }

                App.ReacquireInstanceMutex();
                AppendLog("ERROR", "Elevation was declined. Disable full-device tunnel or restart BeeVPN as Administrator.");
                _notifyIcon.ShowBalloonTip(
                    3000,
                    "Administrator Required",
                    "Approve the UAC prompt to use the full-device tunnel.",
                    Forms.ToolTipIcon.Warning
                );
                return;
            }

            _isConnecting = true;
            _isConnected = false;
            _isIntentionalStop = false;
            _fullTunnelFallback = false;
            _rxBytes = 0;
            _txBytes = 0;
            _measuredLatency = -1;
            _connectTime = DateTime.MinValue;
            _lastLatencyCheck = DateTime.MinValue;
            _connectTimeoutTimer.Stop();

            UpdateUiState();

            if (_selectedMode == 0)
            {
                string region = GetSelectedRegion();
                bool fast = IsFastProfileSelected();
                AppendLog("CORE", "Starting Psiphon Circumvention Mode (Speed Optimized)...");
                if (!string.IsNullOrEmpty(region))
                {
                    AppendLog("CORE", $"Exit region pinned to {region}.");
                }
                if (fast)
                {
                    AppendLog("CORE", "Protocol profile: Fast (OSSH + TLS-OSSH).");
                }
                if (_fullTunnel)
                {
                    AppendLog("TUN", "Full-device tunnel requested; the Wintun adapter starts once the core is connected.");
                }
                _tunnelManager.Start(region, fast, _fullTunnel);
                _connectTimeoutTimer.Start();
            }
            else
            {
                AppendLog("CORE", "Starting Cloudflare DNS Mode (Fast DNS Resolver)...");
                AppendLog("DNS", "Configuring network DNS resolvers (1.1.1.1, 1.0.0.1)...");

                bool ok = DnsManager.SetCloudflareDns();
                if (ok)
                {
                    _isConnected = true;
                    _isConnecting = false;
                    _connectTime = DateTime.Now;
                    _statsTimer.Start();
                    AppendLog("DNS", "CONNECTED! Fast DNS active via Cloudflare 1.1.1.1 (general traffic direct).");
                    UpdateUiState();
                    _notifyIcon.ShowBalloonTip(
                        3000,
                        "BeeVPN DNS Active",
                        "Fast DNS resolver is active (1.1.1.1). General traffic is direct.",
                        Forms.ToolTipIcon.Info
                    );
                }
                else
                {
                    _isConnecting = false;
                    AppendLog("ERROR", "Failed to configure DNS. Please run BeeVPN as Administrator.");
                    UpdateUiState();
                }
            }
        }

        private void DisconnectVpn()
        {
            bool wasActive = _isConnected || _isConnecting;
            _isIntentionalStop = true;
            _connectTimeoutTimer.Stop();
            AppendLog("CORE", "Disconnecting tunnel...");

            if (_selectedMode == 0)
            {
                if (_wintunTunnel.IsBusy)
                {
                    // Removing hundreds of bypass routes can take seconds; keep the UI
                    // responsive and stop the core only after the TUN is torn down.
                    System.Threading.Tasks.Task.Run(() =>
                    {
                        try { _wintunTunnel.Stop(); } catch { }
                        try { _tunnelManager.Stop(); } catch { }
                    });
                }
                else
                {
                    _tunnelManager.Stop();
                }

                if (ProxyManager.IsProxyActive)
                {
                    ProxyManager.DisableSystemProxy();
                }
            }
            else
            {
                DnsManager.ResetDns();
            }

            _isConnected = false;
            _isConnecting = false;
            _measuredLatency = -1;
            _statsTimer.Stop();

            AppendLog("STATUS", "Tunnel disconnected. Device traffic restored to normal.");
            UpdateUiState();

            if (wasActive)
            {
                _notifyIcon.ShowBalloonTip(
                    3000,
                    "BeeVPN Disconnected",
                    "Tunnel disconnected. Device traffic restored to normal.",
                    Forms.ToolTipIcon.Warning
                );
            }
        }

        private void OnTunnelConnected(string region, string protocol)
        {
            Dispatcher.Invoke(() =>
            {
                if (_isConnected || _isIntentionalStop) return;
                _connectTimeoutTimer.Stop();
                _isConnected = true;
                _isConnecting = false;
                _isIntentionalStop = false;
                _connectTime = DateTime.Now;
                _lastLatencyCheck = DateTime.MinValue;
                _statsTimer.Start();

                if (_fullTunnel)
                {
                    if (ProxyManager.IsProxyActive)
                    {
                        ProxyManager.DisableSystemProxy();
                    }
                    AppendLog("TUN", "Core connected. Starting full-device tunnel...");
                    TryStartFullTunnel();
                }
                else if (!_isSplitTunnel && _tunnelManager.HttpPort > 0)
                {
                    ProxyManager.EnableSystemProxy("127.0.0.1", _tunnelManager.HttpPort);
                    AppendLog("PROXY", $"Windows System Proxy active on 127.0.0.1:{_tunnelManager.HttpPort} (apps that honor system proxy settings)");
                }
                else
                {
                    if (ProxyManager.IsProxyActive)
                    {
                        ProxyManager.DisableSystemProxy();
                    }
                    AppendLog("ROUTING", "Split Tunnel active: general PC traffic bypassed. Selected apps route via SOCKS5.");
                }

                AppendLog("STATUS", $"CONNECTED! Encrypted tunnel online (Region: {region}, Proto: {protocol}).");
                UpdateUiState();

                _notifyIcon.ShowBalloonTip(
                    3000,
                    "BeeVPN Connected",
                    $"Encrypted tunnel online and routing traffic ({region} via {protocol}).",
                    Forms.ToolTipIcon.Info
                );
            });
        }

        private void OnPortsReady(int socksPort, int httpPort)
        {
            Dispatcher.Invoke(() =>
            {
                tvDialogSocksInfo.Text = $"SOCKS5: 127.0.0.1:{socksPort} | HTTP: {httpPort}";

                if (_isConnected && _fullTunnel && !_fullTunnelFallback)
                {
                    TryStartFullTunnel();
                }
            });
        }

        private void TryStartFullTunnel()
        {
            if (!_fullTunnel || _fullTunnelFallback || _wintunTunnel.IsBusy) return;

            int socksPort = _tunnelManager.SocksPort;
            if (socksPort <= 0) return;

            _ = StartFullTunnelAsync(socksPort, GetSelectedRegion(), _tunnelManager.CorePid);
        }

        private async System.Threading.Tasks.Task StartFullTunnelAsync(int socksPort, string region, int corePid)
        {
            AppendLog("TUN", "Starting full-device tunnel (Wintun + tun2socks)...");

            bool started = await System.Threading.Tasks.Task.Run(() => _wintunTunnel.Start(socksPort, region, corePid));

            if (_isIntentionalStop || !_isConnected)
            {
                if (started)
                {
                    try { _wintunTunnel.Stop(); } catch { }
                }
                return;
            }

            if (started)
            {
                if (ProxyManager.IsProxyActive)
                {
                    ProxyManager.DisableSystemProxy();
                }
                AppendLog("TUN", "Full-device tunnel active: all apps are routed through BeeVPN.");
                UpdateUiState();
                return;
            }

            _fullTunnelFallback = true;
            AppendLog("WARN", "Full-device tunnel could not start. Falling back to Windows system proxy mode.");

            if (_tunnelManager.HttpPort > 0)
            {
                ProxyManager.EnableSystemProxy("127.0.0.1", _tunnelManager.HttpPort);
                AppendLog("PROXY", $"System proxy enabled on 127.0.0.1:{_tunnelManager.HttpPort} as a fallback.");
            }

            UpdateUiState();
            _notifyIcon.ShowBalloonTip(
                4000,
                "Full Tunnel Unavailable",
                "BeeVPN fell back to system proxy mode. Run BeeVPN as Administrator for full-device tunneling.",
                Forms.ToolTipIcon.Warning
            );
        }

        private void OnWintunFailed(string reason)
        {
            Dispatcher.Invoke(() =>
            {
                if (_isIntentionalStop || !_isConnected) return;

                AppendLog("TUN", $"ERROR: {reason}. Disconnecting so traffic is not left stranded.");
                DisconnectVpn();
                _notifyIcon.ShowBalloonTip(
                    3000,
                    "BeeVPN Tunnel Lost",
                    "The full-device tunnel stopped. Network settings were restored.",
                    Forms.ToolTipIcon.Error
                );
            });
        }

        private void OnBytesTransferred(long received, long sent)
        {
            Dispatcher.Invoke(() =>
            {
                _rxBytes += received;
                _txBytes += sent;
                tvDownload.Text = FormatTraffic(_rxBytes);
                tvUpload.Text = FormatTraffic(_txBytes);
            });
        }

        private static string FormatTraffic(long bytes)
        {
            if (bytes <= 0) return "0.0 MB";
            if (bytes < 1024 * 1024)
                return $"{bytes / 1024.0:F1} KB";
            if (bytes < 1024 * 1024 * 1024)
                return $"{bytes / (1024.0 * 1024.0):F1} MB";
            return $"{bytes / (1024.0 * 1024.0 * 1024.0):F2} GB";
        }

        private void OnTunnelDisconnected()
        {
            Dispatcher.Invoke(() =>
            {
                if (!_isIntentionalStop && (_isConnected || _isConnecting))
                {
                    DisconnectVpn();
                }
            });
        }

        private void OnTunnelLog(string tag, string message)
        {
            Dispatcher.Invoke(() =>
            {
                AppendLog(tag, message);
            });
        }

        private void StatsTimer_Tick(object? sender, EventArgs e)
        {
            if (_connectTime != DateTime.MinValue)
            {
                var elapsed = DateTime.Now - _connectTime;
                tvDuration.Text = string.Format("{0:D2}:{1:D2}:{2:D2}", (int)elapsed.TotalHours, elapsed.Minutes, elapsed.Seconds);
            }

            if ((DateTime.Now - _lastLatencyCheck).TotalSeconds >= 5)
            {
                _lastLatencyCheck = DateTime.Now;
                int mode = _selectedMode;
                int socksPort = _tunnelManager.SocksPort;
                System.Threading.Tasks.Task.Run(() =>
                {
                    int ms = mode == 0
                        ? (socksPort > 0 ? ProbeLatencyViaSocks(socksPort) : -1)
                        : ProbeDirectLatency();

                    Dispatcher.Invoke(() =>
                    {
                        _measuredLatency = ms;
                        if (_isConnected)
                        {
                            tvLatency.Text = ms > 0 ? $"● {ms} ms" : "○ Measuring";
                        }
                    });
                });
            }
        }

        private void ConnectTimeoutTimer_Tick(object? sender, EventArgs e)
        {
            _connectTimeoutTimer.Stop();
            if (!_isConnecting) return;

            AppendLog("ERROR", "Connection timed out after 90 seconds. Check your network and try again.");
            DisconnectVpn();
            _notifyIcon.ShowBalloonTip(
                3000,
                "BeeVPN Connection Failed",
                _fullTunnel
                    ? "Full-device mode uses TCP-only protocols, which some networks block. Try again or disable Full-device tunnel."
                    : "Could not reach the Psiphon network. Try again or switch to Cloudflare DNS mode.",
                Forms.ToolTipIcon.Warning
            );
        }

        // BeeVPN itself is not inside the tunnel, so a direct socket would report
        // pre-tunnel latency; measure through the local SOCKS proxy instead.
        private static int ProbeLatencyViaSocks(int socksPort)
        {
            try
            {
                var sw = Stopwatch.StartNew();
                using var tcp = new System.Net.Sockets.TcpClient();
                if (!tcp.ConnectAsync("127.0.0.1", socksPort).Wait(3000)) return -1;

                using var stream = tcp.GetStream();
                stream.ReadTimeout = 3000;
                stream.WriteTimeout = 3000;

                stream.Write(new byte[] { 0x05, 0x01, 0x00 }, 0, 3);
                var greeting = ReadExactly(stream, 2);
                if (greeting[0] != 0x05 || greeting[1] != 0x00) return -1;

                // CONNECT 1.1.1.1:443
                stream.Write(new byte[] { 0x05, 0x01, 0x00, 0x01, 1, 1, 1, 1, 0x01, 0xBB }, 0, 10);
                var reply = ReadExactly(stream, 4);
                if (reply[0] != 0x05 || reply[1] != 0x00) return -1;

                int skip;
                if (reply[3] == 0x01)
                {
                    skip = 6;
                }
                else if (reply[3] == 0x04)
                {
                    skip = 18;
                }
                else if (reply[3] == 0x03)
                {
                    var len = ReadExactly(stream, 1);
                    skip = len[0] + 2;
                }
                else
                {
                    return -1;
                }

                ReadExactly(stream, skip);
                sw.Stop();
                return (int)sw.ElapsedMilliseconds;
            }
            catch
            {
                return -1;
            }
        }

        private static int ProbeDirectLatency()
        {
            try
            {
                var sw = Stopwatch.StartNew();
                using var tcp = new System.Net.Sockets.TcpClient();
                if (!tcp.ConnectAsync("1.1.1.1", 53).Wait(1500)) return -1;
                sw.Stop();
                return (int)sw.ElapsedMilliseconds;
            }
            catch
            {
                return -1;
            }
        }

        private static byte[] ReadExactly(Stream stream, int count)
        {
            var buffer = new byte[count];
            int read = 0;
            while (read < count)
            {
                int n = stream.Read(buffer, read, count - read);
                if (n <= 0) throw new IOException("Unexpected end of stream");
                read += n;
            }
            return buffer;
        }

        #region Split Tunneling & App Routing

        private void CardAppRouting_Click(object sender, MouseButtonEventArgs e)
        {
            if (rbSplitTunnel != null && rbFullTunnel != null)
            {
                if (_isSplitTunnel) rbSplitTunnel.IsChecked = true;
                else rbFullTunnel.IsChecked = true;
            }
            dialogSplitTunnel.Visibility = Visibility.Visible;
        }

        private void BtnCloseSplitDialog_Click(object sender, RoutedEventArgs e)
        {
            dialogSplitTunnel.Visibility = Visibility.Collapsed;
        }

        private void RbTunnelMode_Changed(object sender, RoutedEventArgs e)
        {
            if (!IsLoaded) return;
            panelSplitApps.IsEnabled = rbSplitTunnel.IsChecked == true;
        }

        private void BtnSaveSplitDialog_Click(object sender, RoutedEventArgs e)
        {
            _isSplitTunnel = rbSplitTunnel.IsChecked == true;
            SplitTunnelManager.SaveSettings(_isSplitTunnel);
            UpdateSplitTunnelUi();
            dialogSplitTunnel.Visibility = Visibility.Collapsed;

            AppendLog("UI", $"App routing updated: {(_isSplitTunnel ? "Split Tunnel (Specific apps only)" : "Full Tunnel (all apps that use the system proxy)")}");

            if (_isConnected && _selectedMode == 0 && !_fullTunnel)
            {
                if (_isSplitTunnel)
                {
                    if (ProxyManager.IsProxyActive)
                    {
                        ProxyManager.DisableSystemProxy();
                    }
                    AppendLog("PROXY", "System proxy disabled. Specific apps only route through tunnel.");
                }
                else if (_tunnelManager.HttpPort > 0)
                {
                    ProxyManager.EnableSystemProxy("127.0.0.1", _tunnelManager.HttpPort);
                    AppendLog("PROXY", $"Windows System Proxy re-enabled on 127.0.0.1:{_tunnelManager.HttpPort}");
                }
            }
        }

        private void UpdateSplitTunnelUi()
        {
            if (tvAppRoutingSub != null)
            {
                if (_isSplitTunnel)
                {
                    tvAppRoutingSub.Text = "Split Tunnel: Specific apps only";
                    tvAppRoutingSub.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x00, 0xE6, 0x76));
                }
                else
                {
                    tvAppRoutingSub.Text = "All apps using system proxy (click to customize)";
                    tvAppRoutingSub.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0xFF, 0xB8, 0x00));
                }
            }
        }

        private void BtnRouteTelegram_Click(object sender, RoutedEventArgs e)
        {
            if (_tunnelManager.SocksPort <= 0)
            {
                System.Windows.MessageBox.Show("Please connect BeeVPN first so the local SOCKS proxy is active.", "BeeVPN", MessageBoxButton.OK, MessageBoxImage.Information);
                return;
            }

            bool ok = SplitTunnelManager.LaunchTelegram(_tunnelManager.SocksPort);
            if (ok)
            {
                AppendLog("ROUTING", $"Telegram proxy link sent (127.0.0.1:{_tunnelManager.SocksPort}). Click Enable in Telegram.");
            }
            else
            {
                System.Windows.MessageBox.Show($"Could not open Telegram URI. You can manually enter SOCKS5 proxy in Telegram: 127.0.0.1 port {_tunnelManager.SocksPort}", "BeeVPN", MessageBoxButton.OK, MessageBoxImage.Information);
            }
        }

        private void BtnLaunchChrome_Click(object sender, RoutedEventArgs e)
        {
            if (_tunnelManager.SocksPort <= 0)
            {
                System.Windows.MessageBox.Show("Please connect BeeVPN first so the local proxy is active.", "BeeVPN", MessageBoxButton.OK, MessageBoxImage.Information);
                return;
            }

            bool ok = SplitTunnelManager.LaunchChrome(_tunnelManager.SocksPort);
            if (ok)
            {
                AppendLog("ROUTING", $"Google Chrome launched with isolated tunnel profile (SOCKS5 127.0.0.1:{_tunnelManager.SocksPort}).");
            }
            else
            {
                System.Windows.MessageBox.Show("Google Chrome was not found at default installation paths.", "BeeVPN", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        }

        private void BtnLaunchEdge_Click(object sender, RoutedEventArgs e)
        {
            if (_tunnelManager.SocksPort <= 0)
            {
                System.Windows.MessageBox.Show("Please connect BeeVPN first so the local proxy is active.", "BeeVPN", MessageBoxButton.OK, MessageBoxImage.Information);
                return;
            }

            bool ok = SplitTunnelManager.LaunchEdge(_tunnelManager.SocksPort);
            if (ok)
            {
                AppendLog("ROUTING", $"Microsoft Edge launched with isolated tunnel profile (SOCKS5 127.0.0.1:{_tunnelManager.SocksPort}).");
            }
            else
            {
                System.Windows.MessageBox.Show("Microsoft Edge was not found at default paths.", "BeeVPN", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        }

        private void BtnLaunchBrave_Click(object sender, RoutedEventArgs e)
        {
            if (_tunnelManager.SocksPort <= 0)
            {
                System.Windows.MessageBox.Show("Please connect BeeVPN first so the local proxy is active.", "BeeVPN", MessageBoxButton.OK, MessageBoxImage.Information);
                return;
            }

            bool ok = SplitTunnelManager.LaunchBrave(_tunnelManager.SocksPort);
            if (ok)
            {
                AppendLog("ROUTING", $"Brave Browser launched with isolated tunnel profile (SOCKS5 127.0.0.1:{_tunnelManager.SocksPort}).");
            }
            else
            {
                System.Windows.MessageBox.Show("Brave Browser was not found at default paths.", "BeeVPN", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        }

        private void BtnBrowseCustomApp_Click(object sender, RoutedEventArgs e)
        {
            if (_tunnelManager.SocksPort <= 0)
            {
                System.Windows.MessageBox.Show("Please connect BeeVPN first so the local proxy is active.", "BeeVPN", MessageBoxButton.OK, MessageBoxImage.Information);
                return;
            }

            var dlg = new Microsoft.Win32.OpenFileDialog
            {
                Title = "Select Application (.exe) to Route via BeeVPN",
                Filter = "Executables (*.exe)|*.exe|All Files (*.*)|*.*"
            };

            if (dlg.ShowDialog() == true)
            {
                bool ok = SplitTunnelManager.LaunchCustomApp(dlg.FileName, _tunnelManager.SocksPort, _tunnelManager.HttpPort);
                if (ok)
                {
                    AppendLog("ROUTING", $"Launched {Path.GetFileName(dlg.FileName)} with tunnel environment variables.");
                }
                else
                {
                    System.Windows.MessageBox.Show("Failed to launch selected application.", "BeeVPN", MessageBoxButton.OK, MessageBoxImage.Error);
                }
            }
        }

        private void BtnCopySocks_Click(object sender, RoutedEventArgs e)
        {
            if (_tunnelManager.SocksPort > 0)
            {
                System.Windows.Clipboard.SetText($"127.0.0.1:{_tunnelManager.SocksPort}");
                AppendLog("UI", $"Copied SOCKS5 endpoint (127.0.0.1:{_tunnelManager.SocksPort}) to clipboard.");
            }
            else
            {
                System.Windows.MessageBox.Show("Connect to Psiphon to obtain an active local SOCKS5 proxy port.", "BeeVPN", MessageBoxButton.OK, MessageBoxImage.Information);
            }
        }

        #endregion

        private void BtnCopyLogs_Click(object sender, RoutedEventArgs e)
        {
            System.Windows.Clipboard.SetText(txtLogs.Text);
            AppendLog("UI", "Logs copied to clipboard.");
        }

        private void BtnClearLogs_Click(object sender, RoutedEventArgs e)
        {
            txtLogs.Clear();
            AppendLog("SYS", "Logs cleared.");
        }

        private void AppendLog(string tag, string msg)
        {
            string time = DateTime.Now.ToString("HH:mm:ss");
            string line = $"[{time}] {tag,-7} {msg}";
            txtLogs.AppendText(line + Environment.NewLine);
            if (txtLogs.LineCount > 1000)
            {
                int firstLineEnd = txtLogs.Text.IndexOf(Environment.NewLine);
                if (firstLineEnd >= 0)
                {
                    txtLogs.Text = txtLogs.Text.Substring(firstLineEnd + Environment.NewLine.Length);
                }
            }
            txtLogs.ScrollToEnd();
        }

        private void UpdateUiState()
        {
            comboMode.IsEnabled = !_isConnected && !_isConnecting;
            UpdateOptionAvailability();

            if (_isConnected)
            {
                borderStatusBadge.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x0E, 0x2E, 0x1A));
                borderStatusBadge.BorderBrush = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x1B, 0x5E, 0x36));
                tvStatusBadge.Text = _selectedMode == 0 ? "●  CONNECTED" : "●  DNS ACTIVE";
                tvStatusBadge.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x00, 0xE6, 0x76));

                tvConnectionState.Text = _selectedMode == 0 ? "PSIPHON ACTIVE" : "CLOUDFLARE DNS ACTIVE";
                tvConnectionState.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x00, 0xE6, 0x76));

                bool fullTunnelActive = _selectedMode == 0 && _wintunTunnel.IsActive;

                tvHint.Text = _selectedMode == 0
                    ? (fullTunnelActive
                        ? "All device traffic is encrypted and routed through the tunnel"
                        : _isSplitTunnel ? "Split tunnel active: selected apps routed" : "Traffic is encrypted and securely routed")
                    : "DNS queries routed via 1.1.1.1 (general traffic direct)";
                tvLatency.Text = _measuredLatency > 0 ? $"● {_measuredLatency} ms" : "○ Measuring";
                tvLatency.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x00, 0xE6, 0x76));

                UpdatePowerButton(true, false);

                // Update System Tray
                string connectedLabel = _selectedMode == 0
                    ? (fullTunnelActive ? "Connected (Full Tunnel)" : "Connected (Psiphon)")
                    : "DNS Active (1.1.1.1)";
                _notifyIcon.Text = $"BeeVPN: {connectedLabel}";
                _menuItemToggleConnect.Text = "Disconnect";
                _menuItemStatus.Text = $"Status: {connectedLabel}";
            }
            else if (_isConnecting)
            {
                borderStatusBadge.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x2E, 0x25, 0x0E));
                borderStatusBadge.BorderBrush = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x5A, 0x48, 0x1A));
                tvStatusBadge.Text = "⏳  CONNECTING...";
                tvStatusBadge.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0xFF, 0xB8, 0x00));

                tvConnectionState.Text = _selectedMode == 0 ? "SECURING TUNNEL..." : "CONFIGURING DNS...";
                tvConnectionState.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0xFF, 0xB8, 0x00));

                tvHint.Text = _selectedMode == 0 ? "Negotiating handshake with server fleet..." : "Applying Cloudflare 1.1.1.1 DNS resolvers...";
                tvLatency.Text = "○ Probing";
                tvLatency.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0xFF, 0xB8, 0x00));

                UpdatePowerButton(false, true);

                // Update System Tray
                _notifyIcon.Text = "BeeVPN: Connecting...";
                _menuItemToggleConnect.Text = "Cancel";
                _menuItemStatus.Text = "Status: Connecting...";
            }
            else
            {
                borderStatusBadge.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x26, 0x15, 0x15));
                borderStatusBadge.BorderBrush = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x4A, 0x1E, 0x1E));
                tvStatusBadge.Text = "○  DISCONNECTED";
                tvStatusBadge.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0xFF, 0x52, 0x52));

                tvConnectionState.Text = "DISCONNECTED";
                tvConnectionState.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x77, 0x77, 0x77));

                tvHint.Text = "Select network mode and click Connect";
                tvLatency.Text = "○ Idle";
                tvLatency.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x66, 0x66, 0x66));

                tvDuration.Text = "00:00:00";
                tvDownload.Text = "0.0 MB";
                tvUpload.Text = "0.0 MB";

                UpdatePowerButton(false, false);

                // Update System Tray
                _notifyIcon.Text = "BeeVPN - Disconnected";
                _menuItemToggleConnect.Text = "Connect";
                _menuItemStatus.Text = "Status: Disconnected";
            }
        }

        private void UpdatePowerButton(bool connected, bool connecting)
        {
            var template = btnToggleVpn.Template;
            if (template == null) return;

            var btnBody = template.FindName("btnBody", btnToggleVpn) as System.Windows.Shapes.Ellipse;
            var glowRing = template.FindName("glowRing", btnToggleVpn) as System.Windows.Shapes.Ellipse;
            var btnText = template.FindName("btnText", btnToggleVpn) as TextBlock;
            var btnIcon = template.FindName("btnIcon", btnToggleVpn) as TextBlock;

            if (btnBody == null || btnText == null || btnIcon == null) return;

            if (connected)
            {
                btnText.Text = "DISCONNECT";
                btnIcon.Text = "🛡️";

                btnBody.Fill = new LinearGradientBrush(
                    System.Windows.Media.Color.FromRgb(0x00, 0xC8, 0x53),
                    System.Windows.Media.Color.FromRgb(0x00, 0x96, 0x24),
                    new System.Windows.Point(0, 0),
                    new System.Windows.Point(1, 1)
                );

                btnBody.Effect = new System.Windows.Media.Effects.DropShadowEffect
                {
                    Color = System.Windows.Media.Color.FromRgb(0x00, 0xE6, 0x76),
                    Opacity = 0.5,
                    BlurRadius = 18,
                    ShadowDepth = 2
                };

                if (glowRing != null)
                {
                    glowRing.Fill = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x0A, 0x21, 0x14));
                    glowRing.Stroke = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x13, 0x48, 0x2B));
                }
            }
            else if (connecting)
            {
                btnText.Text = "CANCEL";
                btnIcon.Text = "⏳";

                btnBody.Fill = new LinearGradientBrush(
                    System.Windows.Media.Color.FromRgb(0xFF, 0xB8, 0x00),
                    System.Windows.Media.Color.FromRgb(0xCC, 0x85, 0x00),
                    new System.Windows.Point(0, 0),
                    new System.Windows.Point(1, 1)
                );

                btnBody.Effect = new System.Windows.Media.Effects.DropShadowEffect
                {
                    Color = System.Windows.Media.Color.FromRgb(0xFF, 0xB8, 0x00),
                    Opacity = 0.5,
                    BlurRadius = 18,
                    ShadowDepth = 2
                };

                if (glowRing != null)
                {
                    glowRing.Fill = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x21, 0x1B, 0x05));
                    glowRing.Stroke = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x4A, 0x3B, 0x0B));
                }
            }
            else
            {
                btnText.Text = "CONNECT";
                btnIcon.Text = "⚡";

                btnBody.Fill = new LinearGradientBrush(
                    System.Windows.Media.Color.FromRgb(0xFF, 0xA7, 0x00),
                    System.Windows.Media.Color.FromRgb(0xE6, 0x7E, 0x00),
                    new System.Windows.Point(0, 0),
                    new System.Windows.Point(1, 1)
                );

                btnBody.Effect = new System.Windows.Media.Effects.DropShadowEffect
                {
                    Color = System.Windows.Media.Color.FromRgb(0xFF, 0xA7, 0x00),
                    Opacity = 0.4,
                    BlurRadius = 16,
                    ShadowDepth = 2
                };

                if (glowRing != null)
                {
                    glowRing.Fill = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x1A, 0x15, 0x05));
                    glowRing.Stroke = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x33, 0x27, 0x0A));
                }
            }
        }
    }
}
