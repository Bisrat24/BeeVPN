# 🐝 BeeVPN

**BeeVPN** is a high-performance, anti-censorship, cross-platform VPN client for **Android** and **Windows**, powered by the Psiphon circumvention engine and Cloudflare encrypted DNS (DoT).

Designed for speed, resilience against deep-packet inspection (DPI), and modern user ergonomics, BeeVPN provides both full-device tunneling and granular split-tunnel application routing.

> **Current release: v1.7.1** — see [What's new in v1.7.1](#-whats-new-in-v171) and the [CHANGELOG](CHANGELOG.md).

---

## 🆕 What's new in v1.7.1

- **UI polish on both platforms:** amber DNS-only badges, clearer layout hierarchy, larger touch targets, accessibility names and localized labels.
- **Small but useful features:** live exit-region picker, mode-aware Quick Settings tile, launcher shortcuts, one-tap connection-summary copy, explicit protection-scope line, version display, and battery/always-on helpers.
- **Adaptive Android launcher icon** (Android 13+ themed icons) and landscape support.

### What's new in v1.7.0

- **Privacy fix:** DNS-only mode now captures resolvers hardcoded inside apps (1.1.1.1, 8.8.8.8 and IPv6 equivalents) instead of letting those queries go direct.
- **Reliability:** failed connections now fall back from the Fast (OSSH/TLS-OSSH) profile to Compatible protocols automatically after 45 s, once per attempt.
- **Honest labeling:** Cloudflare DNS mode is clearly shown as **DNS only** — it does not hide your IP or tunnel general traffic.
- **Sturdier service lifecycle:** explicit disconnects survive Android service restarts; the Quick Settings tile reflects live state.
- **Build tooling:** location-independent Android build script with optional release signing and version read from the manifest.

---

## ⚡ Key Highlights & Speed Optimizations

- **Instantaneous Handshake (~1–2s TTR):** Uses the *Fast Protocol Profile* (`OSSH` + `TLS-OSSH`) by default, bypassing lengthy WebRTC STUN UDP timeouts on restrictive networks and cellular carriers.
- **Dual-Tunnel Pooling (`TunnelPoolSize: 2`):** Multiplexes high-concurrency TCP streams across parallel encrypted tunnels, eliminating Head-of-Line (HoL) blocking during video streaming and downloads.
- **Sub-Millisecond In-Memory DNS Caching (LRU):** Answers repeat domain lookups locally in `<0.5 ms`, slashing web browsing latency.
- **TCP MSS Clamping (1360):** Enforces optimal MTU/MSS sizes on Wintun and tun2socks to eliminate IP packet fragmentation on cellular (4G/5G) and broadband networks.
- **Live Speedometer:** Displays real-time download and upload transfer rates (`↓ MB/s`, `↑ KB/s`) alongside cumulative session bandwidth and duration.
- **Adaptive Protocol Fallback:** A stalled Fast-profile connection automatically retries once with Compatible protocols after 45 s; the change is logged and persisted.
- **TTL-Aware DNS Caching:** Cached answers honour the record's TTL (30–180 s window) and are keyed by question only, so EDNS0 variation does not defeat the cache.
- **Color-Graded Latency Monitor:** Dynamic live ping indicator (`● Fast <90ms`, `● Good <190ms`, `● Slow`).
- **HibernatorX-Style Split Tunneling (Android):** 3-mode kernel packet filter (*Route All*, *Tunnel Only Selected*, *Bypass Selected*) with categorical app tabs (*User*, *System*, *Selected*, *All*).
- **Windows Wintun Kernel Driver:** Clean, full-device L3 TAP tunneling with automatic fallback to Windows system proxy.
- **Android Quick Settings Tile:** Connect and disconnect with a single tap from the Android notification shade.
- **Ergonomic UI:** Kinetic breathing glow animations on the central power button and collapsible live log consoles.

---

## 📂 Project Structure

```
Psiphon/
├── beevpn_app/                 # Android Native Application
│   ├── AndroidManifest.xml     # Permissions, activities, and Quick Settings tile
│   ├── res/                    # Dark theme layouts, vector drawables, strings
│   └── src/com/beevpn/
│       ├── BeeVpnService.java      # VpnService, Psiphon core bindings, DoT worker
│       ├── BeeVpnTileService.java  # Android Quick Settings Tile integration
│       ├── MainActivity.java       # UI Controller, app picker dialog, speedometer
│       └── LogManager.java         # Thread-safe cyclic logging facility
├── beevpn_windows/             # Windows WPF Desktop Application (.NET 9)
│   ├── BeeVpnWindows.csproj    # C# Project configuration
│   ├── MainWindow.xaml         # Frameless dark XAML UI with custom chrome
│   ├── MainWindow.xaml.cs      # UI controller, tray icon, live ticker
│   ├── TunSocksBridge.cs       # Local SOCKS5 bridge with LRU DNS cache
│   ├── TunnelProcessManager.cs # Psiphon core process lifecycle manager
│   ├── WintunTunnelManager.cs  # Wintun adapter driver & routing controller
│   └── SplitTunnelManager.cs   # App proxy routing & persistence
├── BeeVPN-Windows/             # Standalone Windows Release Distribution
├── build_beevpn.ps1            # Android build, zipalign & signing (debug or release)
├── CHANGELOG.md                # Detailed release notes and version history
├── SECURITY.md                 # Trust model, release signing, known limitations
└── README.md                   # This documentation
```

---

## 🛠️ Building from Source

### Android (`BeeVpn.apk`)

**Prerequisites:** PowerShell on Windows with bundled local tools in `tools/` (AAPT2, ECJ, D8, Uber-APK-Signer, Java JRE).

Run the automated build script (works from any checkout location — paths are derived from the script, and the version is read from `AndroidManifest.xml`):
```powershell
powershell -ExecutionPolicy Bypass -File ".\build_beevpn.ps1"
```
The output signed APK will be generated at `./BeeVpn.apk`. By default it is signed with the local Android **debug** key — suitable for development only.

For a distributable build, supply a release keystore (arguments or environment variables):
```powershell
powershell -ExecutionPolicy Bypass -File ".\build_beevpn.ps1" -Release `
    -Keystore "C:\secure\beevpn.jks" -StorePass "<password>" -KeyAlias "beevpn"
```
Equivalent environment variables: `BEEVPN_KEYSTORE`, `BEEVPN_STOREPASS`, `BEEVPN_KEYALIAS`. Keep these secrets out of the repository.

### Windows (`BeeVPN.exe`)

**Prerequisites:** .NET 9.0 SDK or newer.

Build the Release binary:
```powershell
dotnet build beevpn_windows/BeeVpnWindows.csproj -c Release
```
The compiled single-executable and dependencies will be built to `beevpn_windows/bin/Release/net9.0-windows/`. The `Version` property in `BeeVpnWindows.csproj` is the single source of truth for the Windows build and matches the Android manifest.

---

## 🔐 Security Notes & Limitations

BeeVPN is a circumvention client, not an anonymity tool. Please read [SECURITY.md](SECURITY.md) before relying on it in a high-risk environment. In particular:

- **Cloudflare DNS mode is DNS-only.** It encrypts DNS queries but leaves your IP address and all other traffic direct. The UI labels this mode `DNS ONLY` deliberately. Use Psiphon mode for actual tunneling.
- **Full tunneling on Windows requires Administrator.** Without elevation the app falls back to the Windows system proxy, and apps that ignore proxy settings are not tunneled.
- **UDP is limited.** The tunneled path supports DNS over TCP/DoT; other UDP (QUIC/WebRTC) is dropped so it cannot leak outside the tunnel. Some apps fall back to TCP more slowly than others.
- **Split tunneling is best-effort on Windows.** The desktop "split tunnel" launches selected apps with proxy settings or environment variables; it is not kernel-level per-app enforcement.
- **Logs stay local.** The in-app consoles are in-memory only and are never uploaded by BeeVPN itself. Be careful when copying them into third-party tools.

---

## 📄 License & Third Party Notices

This project incorporates components from:
- **Psiphon Inc.** (`psiphon-tunnel-core`, `libgojni`, `libtun2socks`) under the terms of the GPLv3.
- **Wintun** (`wintun.dll`) by Jason A. Donenfeld under the terms of the MIT License.
See [`beevpn_windows/THIRD_PARTY_NOTICES.md`](file:///c:/Users/Bisrat/Documents/APPS/Psiphon/beevpn_windows/THIRD_PARTY_NOTICES.md) for full licensing information.
