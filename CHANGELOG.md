# 🐝 BeeVPN Changelog

All notable changes to the BeeVPN project (both Android and Windows platforms) are documented in this file.

---

## [v1.7.1] - 2026-11-18

### 🎨 UI Polish (Android & Windows)
- **Consistent mode colors:** DNS-only mode now uses a dedicated amber badge (new `badge_dns_bg.xml` and `status_dns` colors on Android; matching badge background on Windows) instead of the green "protected" treatment.
- **Better hierarchy & touch targets:** the Android connection-state label now sits below the power button (matching Windows), log action buttons grew from 26 dp to 32 dp, and the app-routing card has a native ripple.
- **Accessibility:** content descriptions on Android logo images and `AutomationProperties.Name` on the Windows icon-only title-bar and power buttons.
- **Localized labels:** hardcoded Android layout strings moved into `strings.xml`.

### ✨ Small Features
- **Live exit regions:** regions discovered from the Psiphon core are now broadcast to the UI and merged into the exit-region picker, instead of being log-only.
- **Mode-aware Quick Settings tile:** the Android tile reads `BeeVPN: DNS only / Traffic direct` when DNS-only mode is selected.
- **Launcher shortcuts:** long-press the BeeVPN icon to launch straight into *Connect (Psiphon)* or *DNS only*.
- **Copy connection summary:** one-tap clipboard copy of mode, region, protocol profile, duration, traffic, latency and routing scope on both platforms.
- **Protection scope line:** an explicit summary under the connect button — *all apps routed* / *N apps tunneled* / *N apps bypassed* / *DNS only* / *system proxy fallback*.
- **Fast→Compatible fallback notice:** the UI now explains when the fast protocol profile was unavailable.
- **Version display, battery and always-on tools:** the Android header shows the installed version; new buttons open battery-optimization exemption and system VPN settings.
- **Adaptive launcher icon:** themed/monochrome icon for Android 13+, with the original vector retained as the pre-26 fallback; portrait lock removed so landscape works.

### 🔁 Lifecycle
- Android reconnects only after a genuine network loss→return transition when a connection was requested; boot restore uses the same persisted flag.

---

## [v1.7.0] - 2026-11-18

### 🛡️ Correctness & Privacy
- **Version metadata synchronized:** Android manifest (`versionCode 6`, `versionName 1.7.0`) and Windows assembly version now match the release notes. The Android build script reads the version directly from the manifest, so it can no longer drift.
- **DNS mode now captures hardcoded resolvers (Android):** routes for `1.1.1.1`, `1.0.0.1`, `8.8.8.8`, `8.8.4.4` (and their IPv6 equivalents) are added to the DNS-mode TUN. Apps that embed their own resolver can no longer silently bypass DNS protection. Exempted DoT/UDP sockets are `protect()`-ed **before** connecting so they cannot be pulled back into the tunnel.
- **DNS cache is TTL-aware and question-keyed:** cached responses now honour the answer record TTL (clamped to 30–180 s) instead of a fixed 180 s, and keys use only QNAME/QTYPE/QCLASS so EDNS0 padding no longer defeats caching.
- **Safe service restart (Android):** a sticky restart with a null intent now reconnects only when a persisted "should connect" flag is set. An explicit disconnect stays disconnected across process death.
- **Adapter-name validation (Windows):** physical adapter names are validated before being written into `netsh` route scripts, closing an injection path from unusual adapter names.

### ⚡ Connection Reliability
- **One-shot Fast → Compatible fallback (both platforms):** a connection that fails to establish within **45 s** (down from 75 s Android / 90 s Windows) automatically retries once with all protocols enabled. The choice is logged and persisted, so there is no retry loop and no silent failure.
- **Lower probe overhead:** the latency probe now runs every 10 s instead of 5 s; each probe opens a tunneled TCP connection, so the old cadence distorted the speedometer under load.
- **Healthier Quick Settings tile (Android):** the tile now listens for status broadcasts while visible, so it no longer shows a stale state right after a tap.

### 🎨 Honest Mode Labeling
- **"Cloudflare DNS" is now clearly labeled DNS-only** on both platforms, with amber status badges and explicit wording that IP traffic is **not** tunneled. Previously the green "DNS ACTIVE" badge and power button implied full VPN protection.

### 🔨 Build & Tooling
- `build_beevpn.ps1` is now location-independent (derived from `$PSScriptRoot`), prints the version it is building, and supports release signing via `-Release` with `-Keystore` / `-StorePass` / `-KeyAlias` (or `BEEVPN_KEYSTORE`, `BEEVPN_STOREPASS`, `BEEVPN_KEYALIAS` environment variables). Debug signing remains the default for local development.

---

## [v1.6.0] - 2026-11-17

### ⚡ Connection Speed & Performance
- **Fast Protocol Profile by Default:** Changed default protocol profile to `Fast (OSSH + TLS-OSSH)` with server tactics disabled on initial connection. Bypasses 25–40s WebRTC STUN UDP timeouts on restrictive networks, dropping average connection time to **1–2 seconds**.
- **Multi-Tunnel Pooling (`TunnelPoolSize: 2`):** Configured Psiphon circumvention engine to multiplex traffic across 2 parallel encrypted tunnels, preventing Head-of-Line (HoL) blocking and maximizing aggregate download/streaming throughput.
- **In-Memory DNS Caching (LRU):** Implemented sub-millisecond in-memory DNS caching for both Windows (`TunSocksBridge.cs`) and Android (`BeeVpnService.java`). Repeat lookups resolve locally in `<0.5 ms` with automatic TTL expiration.
- **Optimized Bridge Buffering:** Increased Windows SOCKS bridge buffer from 32 KB to **64 KB** (`ArrayPool<byte>.Shared.Rent(65536)`), reducing CPU context switches by ~40% on high-speed broadband.
- **TCP MSS Clamping (1360):** Added `--tcp-mss-clamp 1360` to `tun2socks` on Windows to eliminate packet fragmentation on mobile hotspots and carrier LTE/5G networks.

### 🎨 UI & User Experience
- **Live Speedometer:** Both Android and Windows now display instantaneous download and upload transfer rates (`↓ 2.4 MB/s`, `↑ 180 KB/s`) alongside cumulative session bandwidth.
- **Color-Graded Latency Pill:** Real-time round-trip latency now features semantic color coding:
  - Green (`● <90ms (Fast)`)
  - Gold (`● 90–180ms (Good)`)
  - Red (`● >180ms (Slow)`)
- **Connect Button Breathing Glow:** Added smooth hardware-accelerated pulsing glow animation on the central power button during connection negotiation.
- **Collapsible Log Console:** Added `[HIDE]` / `[SHOW]` toggle buttons to both Android and Windows log cards to allow keeping the main dashboard clean and uncluttered.
- **Windows System Tray Menu:** Added quick toggle for `⚡ Fast Protocols (OSSH+TLS)` directly from the taskbar notification area context menu.
- **Android Quick Settings Tile:** Added native `BeeVpnTileService` enabling 1-tap connect and disconnect directly from the Android system notification drawer.

---

## [v1.5.0] - 2026-11-12

### 📱 Android Application
- **HibernatorX-Style Split Tunneling:** Added full 3-mode split tunneling (*Route All Applications*, *Tunnel Only Selected*, *Bypass Selected*).
- **Categorical App Tabs:** Filter installed apps across `User`, `System`, `Selected`, and `All` tabs.
- **Batch Selection Controls:** Added `Select All`, `Invert Selection`, and `Clear` actions with live search filtering.
- **Package Visibility Fix:** Added `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />` and launcher intent queries to resolve Android 11+ third-party app visibility restrictions.

### 💻 Windows Client
- **IPv6 Bypass Leak Prevention:** Added default route blackholing (`::/1`, `8000::/1`) to Wintun tunnel manager.
- **NDIS Adapter Traffic Accounting:** Polled kernel adapter `GetIPStatistics` to accurately track full-device traffic volume.

---

## [v1.4.0] - 2026-11-11

- Added live Download/Upload metrics and 1-second interval ticker.
- Added persistent connection state notifications for Android and Windows system tray.
- Safe proxy and DNS restoration on unexpected application exits.

---

## [v1.3.0] - 2026-11-06

- Added real-time latency measurement via SOCKS loopback probes.
- Integrated Cloudflare encrypted DNS-over-TLS (DoT) resolver mode.
- Added candidate server fleet discovery and region picker.

---

## [v1.0.0] - 2026-11-04

- Initial baseline release of BeeVPN for Android and Windows.
- Integrated Psiphon Circumvention Core v495.
