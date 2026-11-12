using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Security.Principal;

namespace BeeVPN
{
    public static class ElevationHelper
    {
        public static bool IsAdministrator
        {
            get
            {
                try
                {
                    using var identity = WindowsIdentity.GetCurrent();
                    var principal = new WindowsPrincipal(identity);
                    return principal.IsInRole(WindowsBuiltInRole.Administrator);
                }
                catch
                {
                    return false;
                }
            }
        }

        /// <summary>
        /// Starts a second copy of BeeVPN with a UAC prompt. Returns false when the
        /// user declines or the process cannot be started.
        /// </summary>
        public static bool RelaunchElevated(IEnumerable<string>? extraArgs = null)
        {
            try
            {
                string? exePath = Environment.ProcessPath;
                if (string.IsNullOrEmpty(exePath)) return false;

                var psi = new ProcessStartInfo
                {
                    FileName = exePath,
                    UseShellExecute = true,
                    Verb = "runas",
                    WorkingDirectory = AppContext.BaseDirectory
                };

                // When launched through `dotnet run`, ProcessPath is dotnet.exe and the
                // managed entry assembly has to be passed explicitly.
                if (string.Equals(Path.GetFileNameWithoutExtension(exePath), "dotnet", StringComparison.OrdinalIgnoreCase))
                {
                    string? dll = Assembly.GetEntryAssembly()?.Location;
                    if (string.IsNullOrEmpty(dll)) return false;
                    psi.ArgumentList.Add(dll);
                }

                if (extraArgs != null)
                {
                    foreach (string arg in extraArgs)
                    {
                        psi.ArgumentList.Add(arg);
                    }
                }

                Process.Start(psi);
                return true;
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Elevated relaunch failed: {ex.Message}");
                return false;
            }
        }
    }
}
