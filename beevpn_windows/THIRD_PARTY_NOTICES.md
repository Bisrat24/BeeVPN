# Third-party components (Windows)

BeeVPN Windows bundles the following unmodified third-party binaries. They are
installed under `third_party/` and copied next to the application at build time.

## Wintun 0.14.1

- Vendor: WireGuard LLC
- Source: https://www.wintun.net/builds/wintun-0.14.1.zip
- Archive SHA-256: `07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51`
- `wintun.dll` SHA-256: `e5da8447dc2c320edc0fc52fa01885c103de8c118481f683643cacc3220dafce`
- Authenticode: `Valid`, `CN=WireGuard LLC`
- License: GNU GPL v2 (see `third_party/tun2socks/LICENSE-wintun.txt`)

Wintun provides the layer-3 TUN adapter used for full-device tunneling. The
driver is installed on first use by `tun2socks.exe` and requires administrator
rights.

## tun2socks v2.7.0 (windows-amd64)

- Vendor: xjasonlyu (Jason Lyu)
- Source: https://github.com/xjasonlyu/tun2socks/releases/tag/v2.7.0
- Asset: `tun2socks-windows-amd64.zip`
- Archive SHA-256: `c5d46e9452f6c9cc7c15ab9158d6d6a0169ceecd6bca019ce476b49337d2be43`
- `tun2socks.exe` SHA-256: `076b3c3d6a372bae3f49f2b415a4105f70c30a3ed3caaed7979390e649892559`
- License: MIT (see `third_party/tun2socks/LICENSE-tun2socks.txt`)

tun2socks terminates TCP and UDP from the Wintun adapter and forwards TCP
through the local Psiphon SOCKS5 proxy. The Psiphon core does not implement
SOCKS5 UDP ASSOCIATE, so BeeVPN forwards DNS as DNS-over-TCP through the same
SOCKS proxy and drops other UDP rather than leaking it outside the tunnel.

## Full-device mode behavior

- Requires administrator rights; the app requests elevation on connect.
- The core is restricted to the TCP-based OSSH and TLS-OSSH protocols. Psiphon
  server tactics can overwrite that limit, so tactics are disabled while
  full-device mode is active. This also means other datagram transports
  (WebRTC/QUIC) are not used in this mode.
- System DNS is pointed at public resolvers routed into the tunnel, and every
  query is answered over DNS-over-TCP through the core. Non-DNS UDP is dropped;
  applications fall back to TCP.

No changes were made to either binary. Source for the exact releases is
available at the URLs above.
