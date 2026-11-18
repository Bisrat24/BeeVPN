# Security Policy — BeeVPN

## Trust model

BeeVPN is an **anti-censorship / circumvention client**, not an anonymity tool like Tor. It hides your traffic from a network observer on the local path by encrypting it to a Psiphon server, but the tunnel exit operator can observe destination traffic. Do not treat a BeeVPN connection as protection against a determined adversary who can identify you out of band.

Credentials, Psiphon keys and server lists are embedded in the client by design (this is how Psiphon's propagation model works). They are not secrets belonging to an individual user.

## Supported versions

| Version | Supported |
| ------- | --------- |
| 1.7.x   | ✅ |
| 1.6.x   | ⚠️ security fixes only |
| < 1.6   | ❌ |

## Reporting a vulnerability

Please report suspected vulnerabilities privately to the maintainer rather than opening a public issue. Include the affected platform (Android / Windows), version, reproduction steps and impact. Allow a reasonable window for a fix before public disclosure.

## Release integrity

- The default Android build (`build_beevpn.ps1`) uses the local **debug** key and is for development only. Distribution builds must use `-Release` with a dedicated keystore supplied through `-Keystore` / `-StorePass` / `-KeyAlias` (or the corresponding `BEEVPN_*` environment variables).
- Windows releases should be Authenticode-signed, and the bundled native binaries (`psiphon-tunnel-core.exe`, `third_party/tun2socks/tun2socks.exe`, `third_party/tun2socks/wintun.dll`) should be hash-verified as part of the release pipeline. Unsigned development copies of these files are not trustworthy.
- Keep secrets (keystores, passwords) outside the repository. `.gitignore` excludes build outputs but not arbitrary keystore paths.

## Known limitations

1. **Cloudflare DNS mode is not a VPN.** It only encrypts DNS (DoT with UDP fallback) and leaves general traffic direct. v1.7.0 routes well-known hardcoded resolver IPs into the TUN so apps cannot bypass DNS protection, but it cannot force apps that use DoH/DoT to a specific endpoint. The UI labels this mode `DNS ONLY`.
2. **No full kill switch.** If a tunnel drops in proxy/full-device fallback mode, traffic may return to direct until the app restores settings or reconnects. On Windows, stale proxy/DNS settings are restored from disk at startup; on Android, revoking VPN permission stops the tunnel.
3. **UDP restrictions.** The full-device Windows path and the Android Psiphon path forward DNS via TCP/DoT and drop other UDP to prevent leaks. QUIC/WebRTC-heavy apps may need to fall back to TCP.
4. **Windows full-device tunnel needs Administrator.** Otherwise the app falls back to the WinINet system proxy, which only covers applications that honour it.
5. **Windows "Split Tunnel" is launch-level.** It launches browsers with `--proxy-server` or sets `ALL_PROXY`/`HTTP_PROXY` for a custom executable; it is not enforced per installed application at the kernel level.
6. **Local logs are sensitive.** Debug consoles on both platforms include server regions, protocols and diagnostic notices. They are kept in memory and never uploaded by BeeVPN, but review before sharing.
