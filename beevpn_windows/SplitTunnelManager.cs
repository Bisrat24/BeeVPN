using System;
using System.Diagnostics;
using System.IO;
using System.Text.Json;

namespace BeeVPN
{
    public class AppSettings
    {
        public bool IsSplitTunnel { get; set; } = false;
        public bool FullTunnel { get; set; } = false;
        public string EgressRegion { get; set; } = "";
        public bool FastProtocols { get; set; } = false;
    }

    public static class SplitTunnelManager
    {
        private static readonly string SettingsFile = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "BeeVPN",
            "settings.json"
        );

        public static AppSettings Settings { get; private set; } = LoadSettings();

        public static AppSettings LoadSettings()
        {
            try
            {
                if (File.Exists(SettingsFile))
                {
                    string json = File.ReadAllText(SettingsFile);
                    var s = JsonSerializer.Deserialize<AppSettings>(json);
                    if (s != null) return s;
                }
            }
            catch {}
            return new AppSettings();
        }

        public static void SaveSettings(bool isSplitTunnel)
        {
            Settings.IsSplitTunnel = isSplitTunnel;
            SaveSettings();
        }

        public static void SaveSettings()
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(SettingsFile)!);
                File.WriteAllText(SettingsFile, JsonSerializer.Serialize(Settings, new JsonSerializerOptions { WriteIndented = true }));
            }
            catch {}
        }

        public static bool LaunchChrome(int socksPort)
        {
            string[] paths = {
                @"C:\Program Files\Google\Chrome\Application\chrome.exe",
                @"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Google\Chrome\Application\chrome.exe")
            };
            return LaunchChromiumApp(paths, "BeeVPN_Chrome", socksPort);
        }

        public static bool LaunchEdge(int socksPort)
        {
            string[] paths = {
                @"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
                @"C:\Program Files\Microsoft\Edge\Application\msedge.exe"
            };
            return LaunchChromiumApp(paths, "BeeVPN_Edge", socksPort);
        }

        public static bool LaunchBrave(int socksPort)
        {
            string[] paths = {
                @"C:\Program Files\BraveSoftware\Brave-Browser\Application\brave.exe",
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"BraveSoftware\Brave-Browser\Application\brave.exe")
            };
            return LaunchChromiumApp(paths, "BeeVPN_Brave", socksPort);
        }

        public static bool LaunchTelegram(int socksPort)
        {
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = $"tg://socks?server=127.0.0.1&port={socksPort}",
                    UseShellExecute = true
                };
                Process.Start(psi);
                return true;
            }
            catch
            {
                return false;
            }
        }

        public static bool LaunchCustomApp(string exePath, int socksPort, int httpPort)
        {
            try
            {
                if (!File.Exists(exePath)) return false;

                var psi = new ProcessStartInfo
                {
                    FileName = exePath,
                    UseShellExecute = false
                };

                psi.EnvironmentVariables["ALL_PROXY"] = $"socks5://127.0.0.1:{socksPort}";
                psi.EnvironmentVariables["HTTP_PROXY"] = $"http://127.0.0.1:{httpPort}";
                psi.EnvironmentVariables["HTTPS_PROXY"] = $"http://127.0.0.1:{httpPort}";
                psi.EnvironmentVariables["NO_PROXY"] = "localhost,127.0.0.1";

                Process.Start(psi);
                return true;
            }
            catch
            {
                return false;
            }
        }

        private static bool LaunchChromiumApp(string[] paths, string profileName, int socksPort)
        {
            foreach (var p in paths)
            {
                if (File.Exists(p))
                {
                    try
                    {
                        string profileDir = Path.Combine(
                            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                            "BeeVPN",
                            "Profiles",
                            profileName
                        );
                        Directory.CreateDirectory(profileDir);

                        var psi = new ProcessStartInfo
                        {
                            FileName = p,
                            Arguments = $"--proxy-server=\"socks5://127.0.0.1:{socksPort}\" --user-data-dir=\"{profileDir}\" --no-first-run",
                            UseShellExecute = true
                        };
                        Process.Start(psi);
                        return true;
                    }
                    catch {}
                }
            }
            return false;
        }
    }
}
