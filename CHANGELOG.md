# 🐝 BeeVPN Changelog

All notable changes to the BeeVPN project (both Android and Windows platforms) are documented in this file.

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
