using System;
using System.Buffers.Binary;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace BeeVPN
{
    /// <summary>
    /// Local SOCKS5 endpoint for tun2socks. TCP CONNECT requests are chained to
    /// the Psiphon core's SOCKS proxy. The core rejects SOCKS5 UDP ASSOCIATE, so
    /// UDP is accepted only for DNS: queries are replayed as DNS-over-TCP through
    /// the same proxy. All other UDP is dropped instead of leaking outside the
    /// tunnel.
    /// </summary>
    public sealed class TunSocksBridge : IDisposable
    {
        private static readonly string[] Resolvers = { "1.1.1.1", "1.0.0.1", "8.8.8.8" };
        private static readonly TimeSpan UpstreamConnectTimeout = TimeSpan.FromSeconds(5);
        private static readonly TimeSpan DnsTimeout = TimeSpan.FromSeconds(4);

        private readonly int _upstreamSocksPort;
        private readonly SemaphoreSlim _dnsConcurrency = new(64, 64);

        private TcpListener? _listener;
        private CancellationTokenSource? _cts;
        private Task? _acceptTask;
        private int _preferredResolver;
        private int _droppedUdp;
        private bool _disposed;

        public int Port { get; private set; }
        public event Action<string>? Log;

        public TunSocksBridge(int upstreamSocksPort)
        {
            _upstreamSocksPort = upstreamSocksPort;
        }

        public void Start()
        {
            if (_listener != null) return;

            _cts = new CancellationTokenSource();
            _listener = new TcpListener(IPAddress.Loopback, 0);
            _listener.Start();
            Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
            _acceptTask = Task.Run(() => AcceptLoopAsync(_cts.Token));
            Log?.Invoke($"SOCKS/DNS bridge listening on 127.0.0.1:{Port}");
        }

        public void Stop()
        {
            try { _cts?.Cancel(); } catch { }
            try { _listener?.Stop(); } catch { }

            try { _acceptTask?.Wait(1500); } catch { }
            _listener = null;
            _acceptTask = null;

            try { _cts?.Dispose(); } catch { }
            _cts = null;
            Port = 0;
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            Stop();
            _dnsConcurrency.Dispose();
        }

        private async Task AcceptLoopAsync(CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                TcpClient client;
                try
                {
                    client = await _listener!.AcceptTcpClientAsync(token);
                }
                catch (OperationCanceledException)
                {
                    return;
                }
                catch (ObjectDisposedException)
                {
                    return;
                }
                catch (SocketException)
                {
                    if (token.IsCancellationRequested) return;
                    continue;
                }

                _ = Task.Run(() => HandleClientAsync(client, token));
            }
        }

        private async Task HandleClientAsync(TcpClient client, CancellationToken token)
        {
            using (client)
            {
                client.NoDelay = true;
                try
                {
                    NetworkStream stream = client.GetStream();
                    var greeting = await ReadExactAsync(stream, 2, token);
                    if (greeting[0] != 0x05) return;

                    int methodCount = greeting[1];
                    var methods = await ReadExactAsync(stream, methodCount, token);
                    bool noAuth = false;
                    foreach (byte method in methods)
                    {
                        if (method == 0x00) { noAuth = true; break; }
                    }

                    if (!noAuth)
                    {
                        await stream.WriteAsync(new byte[] { 0x05, 0xFF }, token);
                        return;
                    }

                    await stream.WriteAsync(new byte[] { 0x05, 0x00 }, token);

                    var header = await ReadExactAsync(stream, 4, token);
                    if (header[0] != 0x05) return;

                    byte command = header[1];
                    SocksTarget? target;
                    try
                    {
                        target = await ReadTargetAsync(stream, header[3], token);
                    }
                    catch
                    {
                        await WriteReplyAsync(stream, 0x08, token);
                        return;
                    }

                    if (target == null)
                    {
                        await WriteReplyAsync(stream, 0x08, token);
                        return;
                    }

                    switch (command)
                    {
                        case 0x01:
                            await HandleConnectAsync(client, stream, target, token);
                            break;
                        case 0x03:
                            await HandleUdpAssociateAsync(client, stream, token);
                            break;
                        default:
                            await WriteReplyAsync(stream, 0x07, token);
                            break;
                    }
                }
                catch (OperationCanceledException)
                {
                }
                catch (IOException)
                {
                }
                catch (SocketException)
                {
                }
                catch (Exception ex)
                {
                    Debug.WriteLine($"TunSocksBridge client error: {ex.Message}");
                }
            }
        }

        private async Task HandleConnectAsync(TcpClient client, NetworkStream clientStream, SocksTarget target, CancellationToken token)
        {
            TcpClient? upstream = null;
            try
            {
                upstream = await OpenUpstreamAsync(target, token);
                await WriteReplyAsync(clientStream, 0x00, token);

                NetworkStream upstreamStream = upstream.GetStream();
                var toUpstream = CopyAsync(clientStream, upstreamStream, token);
                var toClient = CopyAsync(upstreamStream, clientStream, token);
                await Task.WhenAny(toUpstream, toClient);
            }
            catch (OperationCanceledException)
            {
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"TunSocksBridge CONNECT failed: {ex.Message}");
                try { await WriteReplyAsync(clientStream, 0x01, token); } catch { }
            }
            finally
            {
                try { upstream?.Dispose(); } catch { }
            }
        }

        private async Task HandleUdpAssociateAsync(TcpClient client, NetworkStream clientStream, CancellationToken token)
        {
            using var udp = new UdpClient(AddressFamily.InterNetwork);
            udp.Client.Bind(new IPEndPoint(IPAddress.Loopback, 0));
            int udpPort = ((IPEndPoint)udp.Client.LocalEndPoint!).Port;

            var reply = new byte[] { 0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, (byte)(udpPort >> 8), (byte)udpPort };
            await clientStream.WriteAsync(reply, token);

            using var linked = CancellationTokenSource.CreateLinkedTokenSource(token);
            Task controlWatch = WatchControlConnectionAsync(clientStream, linked);

            try
            {
                while (!linked.IsCancellationRequested)
                {
                    UdpReceiveResult result;
                    try
                    {
                        result = await udp.ReceiveAsync(linked.Token);
                    }
                    catch (OperationCanceledException)
                    {
                        break;
                    }
                    catch (ObjectDisposedException)
                    {
                        break;
                    }
                    catch (SocketException)
                    {
                        if (linked.IsCancellationRequested) break;
                        continue;
                    }

                    if (!TryParseUdpDatagram(result.Buffer, out int headerLength, out int destinationPort, out int payloadOffset))
                    {
                        continue;
                    }

                    if (destinationPort == 53)
                    {
                        _ = ForwardDnsAsync(udp, result.RemoteEndPoint, result.Buffer, headerLength, payloadOffset, linked.Token);
                    }
                    else if (Interlocked.Increment(ref _droppedUdp) == 1)
                    {
                        Log?.Invoke("Non-DNS UDP is not supported by the Psiphon tunnel and will be dropped; apps fall back to TCP.");
                    }
                }
            }
            finally
            {
                linked.Cancel();
                try { await controlWatch; } catch { }
            }
        }

        private async Task WatchControlConnectionAsync(NetworkStream stream, CancellationTokenSource linked)
        {
            var buffer = new byte[256];
            try
            {
                while (!linked.IsCancellationRequested)
                {
                    int read = await stream.ReadAsync(buffer, linked.Token);
                    if (read <= 0) break;
                }
            }
            catch
            {
            }
            finally
            {
                try { linked.Cancel(); } catch { }
            }
        }

        private async Task ForwardDnsAsync(UdpClient udp, IPEndPoint clientEndpoint, byte[] datagram, int headerLength, int payloadOffset, CancellationToken token)
        {
            byte[] query = new byte[datagram.Length - payloadOffset];
            Buffer.BlockCopy(datagram, payloadOffset, query, 0, query.Length);

            bool acquired;
            try
            {
                acquired = await _dnsConcurrency.WaitAsync(TimeSpan.FromSeconds(2), token);
            }
            catch (OperationCanceledException)
            {
                return;
            }

            if (!acquired)
            {
                // Never leave a query unanswered: a SERVFAIL makes clients retry
                // immediately instead of waiting for their own DNS timeout.
                try
                {
                    byte[] failResponse = BuildServFail(query);
                    var failReply = new byte[headerLength + failResponse.Length];
                    Buffer.BlockCopy(datagram, 0, failReply, 0, headerLength);
                    Buffer.BlockCopy(failResponse, 0, failReply, headerLength, failResponse.Length);
                    await udp.SendAsync(failReply, clientEndpoint, token);
                }
                catch (OperationCanceledException)
                {
                }
                catch (Exception ex)
                {
                    Debug.WriteLine($"TunSocksBridge DNS saturation reply failed: {ex.Message}");
                }
                return;
            }

            try
            {
                byte[]? response = await ResolveOverTcpAsync(query, token);
                response ??= BuildServFail(query);

                var reply = new byte[headerLength + response.Length];
                Buffer.BlockCopy(datagram, 0, reply, 0, headerLength);
                Buffer.BlockCopy(response, 0, reply, headerLength, response.Length);

                await udp.SendAsync(reply, clientEndpoint, token);
            }
            catch (OperationCanceledException)
            {
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"TunSocksBridge DNS forward failed: {ex.Message}");
            }
            finally
            {
                _dnsConcurrency.Release();
            }
        }

        private async Task<byte[]?> ResolveOverTcpAsync(byte[] query, CancellationToken token)
        {
            if (query.Length < 12) return null;

            int start = Volatile.Read(ref _preferredResolver);
            for (int i = 0; i < Resolvers.Length; i++)
            {
                int index = (start + i) % Resolvers.Length;
                TcpClient? upstream = null;
                try
                {
                    using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
                    timeout.CancelAfter(DnsTimeout);

                    var target = SocksTarget.FromIPv4(IPAddress.Parse(Resolvers[index]), 53);
                    upstream = await OpenUpstreamAsync(target, timeout.Token);
                    NetworkStream stream = upstream.GetStream();

                    var framedLength = new byte[2];
                    BinaryPrimitives.WriteUInt16BigEndian(framedLength, (ushort)query.Length);
                    await stream.WriteAsync(framedLength, timeout.Token);
                    await stream.WriteAsync(query, timeout.Token);

                    var lengthBuffer = await ReadExactAsync(stream, 2, timeout.Token);
                    int length = BinaryPrimitives.ReadUInt16BigEndian(lengthBuffer);
                    if (length <= 0) continue;

                    byte[] response = await ReadExactAsync(stream, length, timeout.Token);
                    Volatile.Write(ref _preferredResolver, index);
                    return response;
                }
                catch (Exception ex)
                {
                    Debug.WriteLine($"DNS-over-TCP via {Resolvers[index]} failed: {ex.Message}");
                }
                finally
                {
                    try { upstream?.Dispose(); } catch { }
                }
            }

            return null;
        }

        private async Task<TcpClient> OpenUpstreamAsync(SocksTarget target, CancellationToken token)
        {
            var upstream = new TcpClient
            {
                NoDelay = true
            };

            try
            {
                using (var timeout = CancellationTokenSource.CreateLinkedTokenSource(token))
                {
                    timeout.CancelAfter(UpstreamConnectTimeout);
                    await upstream.ConnectAsync(IPAddress.Loopback, _upstreamSocksPort, timeout.Token);
                }

                NetworkStream stream = upstream.GetStream();
                await stream.WriteAsync(new byte[] { 0x05, 0x01, 0x00 }, token);
                var greeting = await ReadExactAsync(stream, 2, token);
                if (greeting[0] != 0x05 || greeting[1] != 0x00)
                {
                    throw new IOException("Upstream SOCKS proxy rejected authentication.");
                }

                var request = new byte[3 + target.Raw.Length];
                request[0] = 0x05;
                request[1] = 0x01;
                request[2] = 0x00;
                Buffer.BlockCopy(target.Raw, 0, request, 3, target.Raw.Length);
                await stream.WriteAsync(request, token);

                var reply = await ReadExactAsync(stream, 4, token);
                if (reply[0] != 0x05 || reply[1] != 0x00)
                {
                    throw new IOException($"Upstream SOCKS CONNECT failed with code 0x{reply[1]:X2}.");
                }

                int skip;
                if (reply[3] == 0x01) skip = 6;
                else if (reply[3] == 0x04) skip = 18;
                else if (reply[3] == 0x03)
                {
                    var length = await ReadExactAsync(stream, 1, token);
                    skip = length[0] + 2;
                }
                else
                {
                    throw new IOException("Upstream SOCKS reply used an unknown address type.");
                }

                await ReadExactAsync(stream, skip, token);
                return upstream;
            }
            catch
            {
                upstream.Dispose();
                throw;
            }
        }

        private static async Task<SocksTarget?> ReadTargetAsync(NetworkStream stream, byte addressType, CancellationToken token)
        {
            switch (addressType)
            {
                case 0x01:
                {
                    var address = await ReadExactAsync(stream, 4, token);
                    var port = await ReadExactAsync(stream, 2, token);
                    var raw = new byte[7];
                    raw[0] = 0x01;
                    Buffer.BlockCopy(address, 0, raw, 1, 4);
                    Buffer.BlockCopy(port, 0, raw, 5, 2);
                    return new SocksTarget(new IPAddress(address), BinaryPrimitives.ReadUInt16BigEndian(port), raw);
                }
                case 0x04:
                {
                    var address = await ReadExactAsync(stream, 16, token);
                    var port = await ReadExactAsync(stream, 2, token);
                    var raw = new byte[19];
                    raw[0] = 0x04;
                    Buffer.BlockCopy(address, 0, raw, 1, 16);
                    Buffer.BlockCopy(port, 0, raw, 17, 2);
                    return new SocksTarget(new IPAddress(address), BinaryPrimitives.ReadUInt16BigEndian(port), raw);
                }
                case 0x03:
                {
                    var length = await ReadExactAsync(stream, 1, token);
                    int nameLength = length[0];
                    var name = await ReadExactAsync(stream, nameLength, token);
                    var port = await ReadExactAsync(stream, 2, token);
                    var raw = new byte[1 + 1 + nameLength + 2];
                    raw[0] = 0x03;
                    raw[1] = (byte)nameLength;
                    Buffer.BlockCopy(name, 0, raw, 2, nameLength);
                    Buffer.BlockCopy(port, 0, raw, 2 + nameLength, 2);
                    return new SocksTarget(null, BinaryPrimitives.ReadUInt16BigEndian(port), raw, Encoding.ASCII.GetString(name));
                }
                default:
                    return null;
            }
        }

        private static async Task WriteReplyAsync(NetworkStream stream, byte replyCode, CancellationToken token)
        {
            await stream.WriteAsync(new byte[] { 0x05, replyCode, 0x00, 0x01, 0, 0, 0, 0, 0, 0 }, token);
        }

        private static bool TryParseUdpDatagram(byte[] datagram, out int headerLength, out int destinationPort, out int payloadOffset)
        {
            headerLength = 0;
            destinationPort = 0;
            payloadOffset = 0;

            if (datagram.Length < 10) return false;
            if (datagram[0] != 0x00 || datagram[1] != 0x00) return false;
            if (datagram[2] != 0x00) return false;

            byte addressType = datagram[3];
            switch (addressType)
            {
                case 0x01:
                    if (datagram.Length < 10) return false;
                    destinationPort = BinaryPrimitives.ReadUInt16BigEndian(datagram.AsSpan(8, 2));
                    headerLength = 10;
                    payloadOffset = 10;
                    return true;
                case 0x04:
                    if (datagram.Length < 22) return false;
                    destinationPort = BinaryPrimitives.ReadUInt16BigEndian(datagram.AsSpan(20, 2));
                    headerLength = 22;
                    payloadOffset = 22;
                    return true;
                case 0x03:
                {
                    if (datagram.Length < 5) return false;
                    int nameLength = datagram[4];
                    if (datagram.Length < 5 + nameLength + 2) return false;
                    destinationPort = BinaryPrimitives.ReadUInt16BigEndian(datagram.AsSpan(5 + nameLength, 2));
                    headerLength = 5 + nameLength + 2;
                    payloadOffset = headerLength;
                    return true;
                }
                default:
                    return false;
            }
        }

        private static byte[] BuildServFail(byte[] query)
        {
            if (query.Length < 12) return query;

            var response = new byte[query.Length];
            Buffer.BlockCopy(query, 0, response, 0, query.Length);
            response[2] = (byte)((query[2] | 0x80) & 0xFB);
            response[3] = (byte)((query[3] & 0xF0) | 0x02);
            response[6] = 0;
            response[7] = 0;
            response[8] = 0;
            response[9] = 0;
            response[10] = 0;
            response[11] = 0;
            return response;
        }

        private static async Task CopyAsync(Stream from, Stream to, CancellationToken token)
        {
            try
            {
                await from.CopyToAsync(to, token);
            }
            catch
            {
            }
        }

        private static async Task<byte[]> ReadExactAsync(Stream stream, int count, CancellationToken token)
        {
            var buffer = new byte[count];
            int offset = 0;
            while (offset < count)
            {
                int read = await stream.ReadAsync(buffer.AsMemory(offset, count - offset), token);
                if (read <= 0) throw new IOException("Unexpected end of stream.");
                offset += read;
            }
            return buffer;
        }

        private sealed class SocksTarget
        {
            public IPAddress? Address { get; }
            public int Port { get; }
            public byte[] Raw { get; }
            public string? Domain { get; }

            public SocksTarget(IPAddress? address, int port, byte[] raw, string? domain = null)
            {
                Address = address;
                Port = port;
                Raw = raw;
                Domain = domain;
            }

            public static SocksTarget FromIPv4(IPAddress address, int port)
            {
                var raw = new byte[7];
                raw[0] = 0x01;
                var bytes = address.GetAddressBytes();
                Buffer.BlockCopy(bytes, 0, raw, 1, 4);
                raw[5] = (byte)(port >> 8);
                raw[6] = (byte)port;
                return new SocksTarget(address, port, raw);
            }
        }
    }
}
