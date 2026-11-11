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
        private readonly DispatcherTimer _statsTimer;
        private DateTime _connectTime;
        private int _selectedMode = 0; // 0 = Psiphon, 1 = Cloudflare
        private bool _isConnected = false;
        private bool _isConnecting = false;
        private bool _isSplitTunnel = false;
        private bool _isIntentionalStop = false;

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

            _statsTimer = new DispatcherTimer
            {
                Interval = TimeSpan.FromSeconds(1)
            };
            _statsTimer.Tick += StatsTimer_Tick;

            _isSplitTunnel = SplitTunnelManager.Settings.IsSplitTunnel;
            UpdateSplitTunnelUi();

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

            // Clean up any orphaned system proxy or DNS from a previous crash
            ProxyManager.CleanOrphanedProxy();
            DnsManager.ResetDns();

            UpdateUiState();
            AppendLog("CORE", "BeeVPN Windows Edition initialized.");
            AppendLog("CORE", "Pre-seeded broker tactics & 405 verified servers active.");
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
                tvServerTitle.Text = "Psiphon Fleet (Auto)";
                tvServerSub.Text = "Global obfuscated circumvention network";
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
            _isConnecting = true;
            _isConnected = false;
            _isIntentionalStop = false;
            _rxBytes = 0;
            _txBytes = 0;
            _measuredLatency = -1;
            _connectTime = DateTime.Now;

            UpdateUiState();

            if (_selectedMode == 0)
            {
                AppendLog("CORE", "Starting Psiphon Circumvention Mode (Speed Optimized)...");
                _tunnelManager.Start();
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
            AppendLog("CORE", "Disconnecting tunnel...");

            if (_selectedMode == 0)
            {
                _tunnelManager.Stop();
                ProxyManager.DisableSystemProxy();
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
                if (_isConnected) return;
                _isConnected = true;
                _isConnecting = false;
                _isIntentionalStop = false;
                _statsTimer.Start();

                if (!_isSplitTunnel && _tunnelManager.HttpPort > 0)
                {
                    ProxyManager.EnableSystemProxy("127.0.0.1", _tunnelManager.HttpPort);
                    AppendLog("PROXY", $"Windows System Proxy active on 127.0.0.1:{_tunnelManager.HttpPort} (All PC Apps)");
                }
                else
                {
                    ProxyManager.DisableSystemProxy();
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
            var elapsed = DateTime.Now - _connectTime;
            tvDuration.Text = string.Format("{0:D2}:{1:D2}:{2:D2}", (int)elapsed.TotalHours, elapsed.Minutes, elapsed.Seconds);

            if ((DateTime.Now - _lastLatencyCheck).TotalSeconds >= 5)
            {
                _lastLatencyCheck = DateTime.Now;
                System.Threading.Tasks.Task.Run(() =>
                {
                    try
                    {
                        var sw = Stopwatch.StartNew();
                        using var tcp = new System.Net.Sockets.TcpClient();
                        var ar = tcp.BeginConnect("1.1.1.1", 53, null, null);
                        if (ar.AsyncWaitHandle.WaitOne(1500))
                        {
                            tcp.EndConnect(ar);
                            sw.Stop();
                            int ms = (int)sw.ElapsedMilliseconds;
                            Dispatcher.Invoke(() =>
                            {
                                _measuredLatency = ms;
                                if (_isConnected)
                                {
                                    tvLatency.Text = $"● {_measuredLatency} ms";
                                }
                            });
                        }
                    }
                    catch
                    {
                        // Ignore latency probe failures
                    }
                });
            }
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

            AppendLog("UI", $"App routing updated: {(_isSplitTunnel ? "Split Tunnel (Specific apps only)" : "Full Tunnel (All PC apps)")}");

            if (_isConnected && _selectedMode == 0)
            {
                if (_isSplitTunnel)
                {
                    ProxyManager.DisableSystemProxy();
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
                    tvAppRoutingSub.Text = "All PC apps routed (click to customize)";
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

            if (_isConnected)
            {
                borderStatusBadge.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x0E, 0x2E, 0x1A));
                borderStatusBadge.BorderBrush = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x1B, 0x5E, 0x36));
                tvStatusBadge.Text = _selectedMode == 0 ? "●  CONNECTED" : "●  DNS ACTIVE";
                tvStatusBadge.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x00, 0xE6, 0x76));

                tvConnectionState.Text = _selectedMode == 0 ? "PSIPHON ACTIVE" : "CLOUDFLARE DNS ACTIVE";
                tvConnectionState.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x00, 0xE6, 0x76));

                tvHint.Text = _selectedMode == 0 
                    ? (_isSplitTunnel ? "Split tunnel active: selected apps routed" : "Traffic is encrypted and securely routed")
                    : "DNS queries routed via 1.1.1.1 (general traffic direct)";
                tvLatency.Text = _measuredLatency > 0 ? $"● {_measuredLatency} ms" : "● Measuring...";
                tvLatency.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x00, 0xE6, 0x76));

                UpdatePowerButton(true, false);

                // Update System Tray
                _notifyIcon.Text = $"BeeVPN: {(_selectedMode == 0 ? "Connected (Psiphon)" : "DNS Active (1.1.1.1)")}";
                _menuItemToggleConnect.Text = "Disconnect";
                _menuItemStatus.Text = $"Status: {(_selectedMode == 0 ? "Connected (Psiphon)" : "DNS Active (1.1.1.1)")}";
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
