package com.beevpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import ca.psiphon.PsiphonTunnel;
import ca.psiphon.Tun2SocksJniLoader;

public class BeeVpnService extends VpnService {

    public static final String ACTION_CONNECT = "com.beevpn.ACTION_CONNECT";
    public static final String ACTION_DISCONNECT = "com.beevpn.ACTION_DISCONNECT";
    public static final String ACTION_STATUS_UPDATE = "com.beevpn.ACTION_STATUS_UPDATE";
    public static final String ACTION_REGIONS_UPDATE = "com.beevpn.ACTION_REGIONS_UPDATE";

    public static final String EXTRA_MODE = "extra_mode";
    public static final int MODE_PSIPHON = 0;
    public static final int MODE_CLOUDFLARE = 1;

    public static final String EXTRA_IS_CONNECTED = "extra_is_connected";
    public static final String EXTRA_IS_CONNECTING = "extra_is_connecting";
    public static final String EXTRA_STATUS_MSG = "extra_status_msg";
    public static final String EXTRA_DURATION = "extra_duration";
    public static final String EXTRA_RX_BYTES = "extra_rx_bytes";
    public static final String EXTRA_TX_BYTES = "extra_tx_bytes";
    public static final String EXTRA_RX_SPEED = "extra_rx_speed";
    public static final String EXTRA_TX_SPEED = "extra_tx_speed";
    public static final String EXTRA_LATENCY = "extra_latency";
    public static final String EXTRA_ACTIVE_MODE = "extra_active_mode";
    public static final String EXTRA_REGIONS = "extra_regions";
    public static final String EXTRA_NOTICE = "extra_notice";

    private static final String CHANNEL_ID = "beevpn_channel";
    private static final String CHANNEL_ALERTS_ID = "beevpn_alerts";
    private static final int NOTIFICATION_ID = 7701;
    private static final int NOTIFICATION_DISCONNECTED_ID = 7702;

    private static final String PREFS_NAME = "beevpn_prefs";
    private static final String PREF_EGRESS_REGION = "egress_region";
    private static final String PREF_FAST_PROTOCOLS = "fast_protocols";
    private static final String PREF_MODE = "selected_mode";
    // Persisted so a service restart (START_STICKY) never reconnects after the
    // user explicitly disconnected, but survives process death while connected.
    private static final String PREF_SHOULD_CONNECT = "should_connect";

    private static volatile boolean sIsRunning = false;
    private static volatile boolean sIsConnecting = false;
    private static volatile int sCurrentMode = MODE_PSIPHON;

    private ParcelFileDescriptor mVpnInterface = null;
    private Thread mWorkerThread = null;
    private Thread mTun2SocksThread = null;
    private volatile boolean mShouldStop = false;

    private PsiphonTunnel mPsiphonTunnel = null;
    private volatile int mSocksPort = 0;
    private volatile boolean mIsTunnelConnected = false;
    private Runnable mConnectTimeoutRunnable = null;
    private java.util.concurrent.ExecutorService mDnsExecutor = null;
    // Guards the one-time Fast -> Compatible retry on connection timeout.
    private boolean mCompatibilityFallbackUsed = false;

    private static final String DOT_HOST = "cloudflare-dns.com";
    private static final String DOT_SERVER = "1.1.1.1";
    private static final int DOT_PORT = 853;
    private static final int DOT_CONNECT_TIMEOUT_MS = 2500;
    private static final int DOT_READ_TIMEOUT_MS = 2500;
    // After a failed handshake, skip DoT entirely for this long. Without the
    // backoff every query paid the full connect timeout on filtered networks.
    private static final long DOT_RETRY_INTERVAL_MS = 60000;
    private static final int UDP_TIMEOUT_MS = 1500;
    private static final String[] UDP_FALLBACK_SERVERS = {"1.1.1.1", "1.0.0.1"};
    private static final int DNS_WORKERS = 8;
    private static final int DNS_QUEUE_CAPACITY = 512;
    private final ThreadLocal<DnsOverTls> mDotResolvers = new ThreadLocal<>();
    private final Set<DnsOverTls> mOpenDotResolvers =
            Collections.newSetFromMap(new ConcurrentHashMap<DnsOverTls, Boolean>());
    private volatile boolean mDotHealthy = false;
    private volatile long mDotRetryAfterMs = 0;
    private volatile boolean mDnsQueueWarned = false;

    private long mStartTime = 0;
    private final java.util.concurrent.atomic.AtomicLong mRxBytes = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong mTxBytes = new java.util.concurrent.atomic.AtomicLong(0);
    private long mLastTickerRx = 0;
    private long mLastTickerTx = 0;
    private long mLastTickerTime = 0;
    private volatile long mRxSpeedBps = 0;
    private volatile long mTxSpeedBps = 0;
    private long mUidRxBaseline = -1;
    private long mUidTxBaseline = -1;
    private volatile int mMeasuredLatencyMs = -1;
    private long mLastLatencyCheck = 0;
    private String mLastStatusMsg = "Ready";

    private static class DnsCacheEntry {
        final byte[] response;
        final long expiresAt;
        DnsCacheEntry(byte[] response, long expiresAt) {
            this.response = response;
            this.expiresAt = expiresAt;
        }
    }
    private final ConcurrentHashMap<String, DnsCacheEntry> mDnsCache = new ConcurrentHashMap<>();

    private Handler mHandler;
    // Transient user-facing note (e.g. "Compatible profile in use") carried in
    // status broadcasts so the UI can explain a non-obvious state change.
    private volatile String mStatusNotice = null;
    private volatile boolean mNetworkLost = false;
    private BroadcastReceiver mNetworkReceiver = null;

    public static boolean isRunning() {
        return sIsRunning;
    }

    public static boolean isConnecting() {
        return sIsConnecting;
    }

    public static int getCurrentMode() {
        return sCurrentMode;
    }

    // Callback invoked directly from native tun2socks code
    public static void logTun2Socks(String level, String channel, String msg) {
        LogManager.log("TUN2S", level + " (" + channel + "): " + msg);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper());
        LogManager.init(this);
        LogManager.log("CORE", "BeeVPN Service initialized.");

        try {
            Tun2SocksJniLoader.initializeLogger(BeeVpnService.class.getName(), "logTun2Socks");
            LogManager.log("CORE", "tun2socks logger bound successfully.");
        } catch (Throwable t) {
            LogManager.log("WARN", "Native tun2socks logger init: " + t.getMessage());
        }

        // Only reconnect on a genuine loss->return transition, so a normal
        // onAvailable at startup never triggers a duplicate connection.
        mNetworkReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                ConnectivityManager cm =
                        (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) return;
                NetworkInfo info = cm.getActiveNetworkInfo();
                boolean online = info != null && info.isConnected();
                if (!online) {
                    mNetworkLost = true;
                    return;
                }
                if (!mNetworkLost) return;
                mNetworkLost = false;

                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                if (!prefs.getBoolean(PREF_SHOULD_CONNECT, false)) return;
                if (sIsRunning || sIsConnecting) return;
                LogManager.log("NET", "Network returned; restoring the BeeVPN tunnel.");
                startVpn(prefs.getInt(PREF_MODE, MODE_PSIPHON));
            }
        };
        try {
            registerReceiver(mNetworkReceiver,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        } catch (Exception e) {
            LogManager.log("WARN", "Network change monitoring unavailable: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            LogManager.log("CORE", "Disconnect command received from user.");
            mStatusNotice = null;
            prefs.edit().putBoolean(PREF_SHOULD_CONNECT, false).apply();
            stopVpn();
            return START_NOT_STICKY;
        }

        // START_STICKY restarts can deliver a null intent. Only reconnect when a
        // connection was explicitly requested; otherwise a user disconnect would
        // silently come back after the OS recycled the service.
        if (intent == null) {
            if (!prefs.getBoolean(PREF_SHOULD_CONNECT, false)) {
                LogManager.log("CORE", "Restarted without a pending connection request; staying stopped.");
                stopSelf();
                return START_NOT_STICKY;
            }
            LogManager.log("CORE", "Restoring connection after service restart.");
            startVpn(prefs.getInt(PREF_MODE, MODE_PSIPHON));
            return START_STICKY;
        }

        int mode = intent.getIntExtra(EXTRA_MODE, MODE_PSIPHON);
        sCurrentMode = mode;

        startVpn(mode);
        return START_STICKY;
    }

    private synchronized void startVpn(int mode) {
        if (sIsRunning || sIsConnecting) return;

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_SHOULD_CONNECT, true)
                .putInt(PREF_MODE, mode)
                .apply();
        mCompatibilityFallbackUsed = false;

        createNotificationChannel();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_DISCONNECTED_ID);
        }
        startForeground(NOTIFICATION_ID, buildNotification("BeeVPN Tunnel", "Securing connection...", false));

        mStartTime = 0;
        mRxBytes.set(0);
        mTxBytes.set(0);
        mUidRxBaseline = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
        mUidTxBaseline = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid());
        mMeasuredLatencyMs = -1;
        mLastLatencyCheck = 0;
        mShouldStop = false;

        if (mode == MODE_PSIPHON) {
            LogManager.log("CORE", "Starting Psiphon Circumvention Mode (Global Bypass)...");
            startPsiphonMode();
        } else {
            LogManager.log("CORE", "Starting Cloudflare DNS Mode (Fast Public DNS)...");
            startCloudflareDnsMode();
        }
    }

    private void startCloudflareDnsMode() {
        sCurrentMode = MODE_CLOUDFLARE;
        try {
            LogManager.log("DNS", "Building DNS resolver VPN interface (10.8.0.2)...");
            Builder builder = new Builder();
            builder.setSession("BeeVPN Cloudflare DNS");
            builder.addAddress("10.8.0.2", 24);
            // Resolver address inside the tunnel subnet: the on-link route implied
            // by addAddress captures it, so naming 1.1.1.1 as the DNS server is no
            // longer needed (and 1.1.1.1 stays reachable for apps that use it).
            builder.addDnsServer("10.8.0.3");
            builder.addRoute("10.8.0.3", 32);

            // Capture resolvers hardcoded inside apps. Without these routes, an app
            // that talks directly to 1.1.1.1 or 8.8.8.8 completely bypasses DNS
            // protection. The DoT/UDP sockets are protect()-ed, so they still exit
            // directly and cannot loop back into the TUN.
            String[] publicResolversV4 = {"1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4"};
            for (String resolver : publicResolversV4) {
                try { builder.addRoute(resolver, 32); } catch (Exception ignored) {}
            }
            String[] publicResolversV6 = {
                    "2606:4700:4700::1111", "2606:4700:4700::1001",
                    "2001:4860:4860::8888", "2001:4860:4860::8844"
            };
            for (String resolver : publicResolversV6) {
                try { builder.addRoute(resolver, 128); } catch (Exception ignored) {}
            }

            // Prevent IPv6 DNS bypass on dual-stack carrier networks
            try {
                builder.addAddress("fd00:bee::2", 64);
                builder.addDnsServer("fd00:bee::3");
                builder.addRoute("fd00:bee::3", 128);
            } catch (Exception ignored) {}

            // DNS mode must cover every app's resolver traffic; split-tunnel selections do not apply.
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (Exception ignored) {}
            builder.setMtu(1500);

            mVpnInterface = builder.establish();
            if (mVpnInterface == null) {
                LogManager.log("ERROR", "VpnService.Builder.establish() returned null!");
                stopSelf();
                return;
            }

            sIsRunning = true;
            sIsConnecting = false;
            mStartTime = System.currentTimeMillis();
            mLastStatusMsg = "Cloudflare DNS Active";
            mDotHealthy = false;
            mDotRetryAfterMs = 0;
            mDnsQueueWarned = false;
            LogManager.log("DNS", "ACTIVE! Encrypted DNS resolver online (DoT with UDP/53 fallback). General device traffic is direct.");
            updateNotification("BeeVPN DNS Only", "DNS encrypted - IP traffic direct");
            broadcastStatus(true, false, mLastStatusMsg);

            startDnsWorker();
            startStatsTicker();
        } catch (Exception e) {
            LogManager.log("ERROR", "Failed to start Cloudflare DNS mode: " + e.getMessage());
            stopVpn();
        }
    }

    private void startDnsWorker() {
        // Bounded queue: an unbounded executor retains every packet while the
        // upstream resolver is slow, which starves memory on low-end phones.
        RejectedExecutionHandler dropWithNotice = (r, executor) -> {
            if (!mDnsQueueWarned) {
                mDnsQueueWarned = true;
                LogManager.log("WARN", "DNS queue saturated; dropping excess queries.");
            }
        };
        mDnsExecutor = new ThreadPoolExecutor(
                DNS_WORKERS, DNS_WORKERS, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(DNS_QUEUE_CAPACITY),
                dropWithNotice);
        mWorkerThread = new Thread(() -> {
            FileInputStream in = new FileInputStream(mVpnInterface.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(mVpnInterface.getFileDescriptor());
            byte[] packet = new byte[32768];

            try {
                LogManager.log("DNS", "DNS worker active. Queries use DNS-over-TLS with UDP/53 fallback.");

                while (!mShouldStop) {
                    int length = in.read(packet);
                    if (length <= 0) continue;

                    mTxBytes.addAndGet(length);

                    int version = (packet[0] >> 4) & 0x0F;

                    if (version == 4 && (packet[9] & 0xFF) == 17) { // IPv4 UDP
                        int ipHdrLen = (packet[0] & 0x0F) * 4;
                        int srcPort = ((packet[ipHdrLen] & 0xFF) << 8) | (packet[ipHdrLen + 1] & 0xFF);
                        int dstPort = ((packet[ipHdrLen + 2] & 0xFF) << 8) | (packet[ipHdrLen + 3] & 0xFF);
                        int udpLen = ((packet[ipHdrLen + 4] & 0xFF) << 8) | (packet[ipHdrLen + 5] & 0xFF);

                        if (dstPort == 53 && udpLen > 8) {
                            int dnsLen = udpLen - 8;
                            byte[] query = new byte[dnsLen];
                            System.arraycopy(packet, ipHdrLen + 8, query, 0, dnsLen);

                            byte[] rawPacketCopy = new byte[ipHdrLen + 8];
                            System.arraycopy(packet, 0, rawPacketCopy, 0, rawPacketCopy.length);

                            mDnsExecutor.execute(() -> {
                                try {
                                    byte[] response = resolveDnsQuery(query);
                                    if (response == null) return;

                                    mRxBytes.addAndGet(response.length);
                                    byte[] respIpPacket = buildUdpResponse(
                                            rawPacketCopy, ipHdrLen,
                                            dstPort, srcPort,
                                            response, response.length
                                    );
                                    synchronized (out) {
                                        out.write(respIpPacket);
                                    }
                                } catch (Exception ignored) {
                                }
                            });
                        }
                    } else if (version == 6 && length >= 48 && (packet[6] & 0xFF) == 17) { // IPv6 UDP
                        int srcPort = ((packet[40] & 0xFF) << 8) | (packet[41] & 0xFF);
                        int dstPort = ((packet[42] & 0xFF) << 8) | (packet[43] & 0xFF);
                        int udpLen = ((packet[44] & 0xFF) << 8) | (packet[45] & 0xFF);

                        if (dstPort == 53 && udpLen > 8 && 40 + udpLen <= length) {
                            int dnsLen = udpLen - 8;
                            byte[] query = new byte[dnsLen];
                            System.arraycopy(packet, 48, query, 0, dnsLen);

                            byte[] rawPacketCopy = new byte[48];
                            System.arraycopy(packet, 0, rawPacketCopy, 0, 48);

                            mDnsExecutor.execute(() -> {
                                try {
                                    byte[] response = resolveDnsQuery(query);
                                    if (response == null) return;

                                    mRxBytes.addAndGet(response.length);
                                    byte[] respIpPacket = buildUdpV6Response(
                                            rawPacketCopy,
                                            dstPort, srcPort,
                                            response, response.length
                                    );
                                    synchronized (out) {
                                        out.write(respIpPacket);
                                    }
                                } catch (Exception ignored) {
                                }
                            });
                        }
                    }
                }
            } catch (Exception e) {
                if (!mShouldStop) {
                    LogManager.log("DNS", "Worker error: " + e.getMessage());
                }
            } finally {
                try {
                    in.close();
                    out.close();
                } catch (IOException ignored) {}
            }
        }, "BeeVpn-DnsWorker");
        mWorkerThread.start();
    }

    private byte[] resolveDnsQuery(byte[] query) {
        String cacheKey = dnsCacheKey(query);
        if (cacheKey != null) {
            DnsCacheEntry cached = mDnsCache.get(cacheKey);
            if (cached != null && SystemClock.elapsedRealtime() < cached.expiresAt) {
                byte[] hit = cached.response.clone();
                hit[0] = query[0];
                hit[1] = query[1];
                return hit;
            }
        }

        long now = SystemClock.elapsedRealtime();
        byte[] response = null;
        if (now >= mDotRetryAfterMs) {
            DnsOverTls resolver = mDotResolvers.get();
            if (resolver == null) {
                resolver = new DnsOverTls();
                mDotResolvers.set(resolver);
                mOpenDotResolvers.add(resolver);
            }

            response = resolver.resolve(query);
            if (response != null) {
                if (!mDotHealthy) {
                    mDotHealthy = true;
                    LogManager.log("DNS", "DNS-over-TLS established with " + DOT_HOST + " (" + DOT_SERVER + ":" + DOT_PORT + ").");
                }
                mDotRetryAfterMs = 0;
            } else {
                mDotHealthy = false;
                mDotRetryAfterMs = now + DOT_RETRY_INTERVAL_MS;
                LogManager.log("DNS", "DNS-over-TLS unavailable; using UDP/53 for the next 60s.");
            }
        }

        if (response == null) {
            response = resolveUdp(query);
        }

        if (response != null && cacheKey != null && response.length > 12 && (response[3] & 0x0F) == 0) {
            long ttlMs = dnsResponseTtlMs(response);
            mDnsCache.put(cacheKey, new DnsCacheEntry(response, SystemClock.elapsedRealtime() + ttlMs));
        }

        return response;
    }

    /**
     * Key on the DNS question (QNAME + QTYPE + QCLASS) instead of the whole
     * datagram: EDNS0 padding and varying flags would otherwise defeat caching.
     */
    private static String dnsCacheKey(byte[] query) {
        if (query == null || query.length < 17) return null;
        int pos = 12;
        for (int hops = 0; hops < 128 && pos < query.length; hops++) {
            int len = query[pos] & 0xFF;
            if (len == 0) {
                int end = pos + 5; // terminating zero + QTYPE + QCLASS
                if (end > query.length) return null;
                return android.util.Base64.encodeToString(query, 12, end - 12, android.util.Base64.NO_WRAP);
            }
            if ((len & 0xC0) != 0) return null; // compressed names are not expected in queries
            pos += 1 + len;
        }
        return null;
    }

    /** Returns the smallest answer TTL, clamped to 30s..180s; 0 means "do not cache". */
    private static long dnsResponseTtlMs(byte[] response) {
        try {
            int questions = ((response[4] & 0xFF) << 8) | (response[5] & 0xFF);
            int answers = ((response[6] & 0xFF) << 8) | (response[7] & 0xFF);
            if (answers <= 0) return 0;

            int pos = 12;
            for (int i = 0; i < questions; i++) {
                pos = skipDnsName(response, pos);
                if (pos < 0 || pos + 4 > response.length) return 0;
                pos += 4;
            }

            long minTtl = Long.MAX_VALUE;
            for (int i = 0; i < answers; i++) {
                pos = skipDnsName(response, pos);
                if (pos < 0 || pos + 10 > response.length) return 0;
                long ttl = ((long) (response[pos + 4] & 0xFF) << 24)
                        | ((long) (response[pos + 5] & 0xFF) << 16)
                        | ((long) (response[pos + 6] & 0xFF) << 8)
                        | (long) (response[pos + 7] & 0xFF);
                if (ttl > 0 && ttl < minTtl) minTtl = ttl;
                int rdLength = ((response[pos + 8] & 0xFF) << 8) | (response[pos + 9] & 0xFF);
                pos += 10 + rdLength;
                if (pos > response.length) return 0;
            }
            if (minTtl == Long.MAX_VALUE) return 0;
            long clamped = Math.max(30, Math.min(minTtl, 180));
            return clamped * 1000L;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int skipDnsName(byte[] msg, int pos) {
        for (int hops = 0; hops < 128; hops++) {
            if (pos < 0 || pos >= msg.length) return -1;
            int len = msg[pos] & 0xFF;
            if ((len & 0xC0) == 0xC0) {
                return (pos + 2 <= msg.length) ? pos + 2 : -1;
            }
            if (len == 0) return pos + 1;
            pos += 1 + len;
        }
        return -1;
    }

    private byte[] resolveUdp(byte[] query) {
        for (String server : UDP_FALLBACK_SERVERS) {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                protect(socket);
                socket.setSoTimeout(UDP_TIMEOUT_MS);

                InetAddress address = InetAddress.getByName(server);
                socket.send(new DatagramPacket(query, query.length, address, 53));
                byte[] respBuf = new byte[4096];
                DatagramPacket inPacket = new DatagramPacket(respBuf, respBuf.length);
                socket.receive(inPacket);

                byte[] response = new byte[inPacket.getLength()];
                System.arraycopy(respBuf, 0, response, 0, response.length);
                return response;
            } catch (Exception ignored) {
                // Try the next resolver instead of failing the whole query.
            } finally {
                if (socket != null) socket.close();
            }
        }
        return null;
    }

    private void closeDotResolvers() {
        for (DnsOverTls resolver : mOpenDotResolvers) {
            resolver.close();
        }
        mOpenDotResolvers.clear();
    }

    private final class DnsOverTls implements Closeable {
        private Socket mRawSocket;
        private SSLSocket mSocket;
        private InputStream mIn;
        private OutputStream mOut;

        synchronized byte[] resolve(byte[] query) {
            if (query.length == 0 || query.length > 65535) return null;

            // One attempt only: a retry here doubles the latency of every query
            // before the caller falls back, and the caller now backs off DoT.
            try {
                connect();
                mOut.write((query.length >> 8) & 0xFF);
                mOut.write(query.length & 0xFF);
                mOut.write(query);
                mOut.flush();

                int hi = mIn.read();
                int lo = mIn.read();
                if (hi < 0 || lo < 0) throw new IOException("DNS-over-TLS stream closed");
                int length = (hi << 8) | lo;
                if (length <= 0) throw new IOException("Invalid DNS-over-TLS response length");

                byte[] response = new byte[length];
                readFully(mIn, response, response.length);
                return response;
            } catch (Exception e) {
                closeQuietly();
                return null;
            }
        }

        private void connect() throws IOException {
            if (mSocket != null && !mSocket.isClosed()) return;

            mRawSocket = new Socket();
            // Protect before connecting: once the socket is bound to a route,
            // protect() can no longer reliably exclude it from the VPN tunnel.
            if (!BeeVpnService.this.protect(mRawSocket)) {
                LogManager.log("WARN", "Could not exempt the DNS-over-TLS socket from the VPN route.");
            }
            mRawSocket.connect(new InetSocketAddress(DOT_SERVER, DOT_PORT), DOT_CONNECT_TIMEOUT_MS);
            mRawSocket.setKeepAlive(true);

            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket socket = (SSLSocket) factory.createSocket(
                    mRawSocket, DOT_HOST, DOT_PORT, true);
            socket.setSoTimeout(DOT_READ_TIMEOUT_MS);

            // Layering over the connected IP socket keeps certificate checks and
            // SNI bound to the DoT host name (1.1.1.1 is only the transport).
            SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.startHandshake();

            mSocket = socket;
            mIn = socket.getInputStream();
            mOut = socket.getOutputStream();
        }

        @Override
        public void close() {
            // Never wait for an in-flight query: closing the sockets unblocks it.
            try {
                if (mSocket != null) mSocket.close();
            } catch (Exception ignored) {}
            try {
                if (mRawSocket != null) mRawSocket.close();
            } catch (Exception ignored) {}
        }

        private void closeQuietly() {
            try {
                if (mSocket != null) mSocket.close();
            } catch (Exception ignored) {}
            try {
                if (mRawSocket != null) mRawSocket.close();
            } catch (Exception ignored) {}
            mSocket = null;
            mRawSocket = null;
            mIn = null;
            mOut = null;
        }
    }

    private byte[] buildUdpResponse(byte[] req, int reqIpHdrLen, int srcPort, int dstPort, byte[] payload, int payloadLen) {
        int ipLen = 20 + 8 + payloadLen;
        byte[] resp = new byte[ipLen];

        // IPv4 Header
        resp[0] = 0x45;
        resp[1] = 0x00;
        resp[2] = (byte) ((ipLen >> 8) & 0xFF);
        resp[3] = (byte) (ipLen & 0xFF);
        resp[4] = 0x00;
        resp[5] = 0x01;
        resp[6] = 0x40; // Don't fragment
        resp[7] = 0x00;
        resp[8] = 64;   // TTL
        resp[9] = 17;   // Protocol UDP

        // Swap Source & Dest IP addresses
        System.arraycopy(req, 16, resp, 12, 4); // Source = original Dest (1.1.1.1)
        System.arraycopy(req, 12, resp, 16, 4); // Dest = original Source (10.8.0.2)

        // IP Checksum
        int sum = 0;
        for (int i = 0; i < 20; i += 2) {
            if (i == 10) continue;
            sum += ((resp[i] & 0xFF) << 8) | (resp[i + 1] & 0xFF);
        }
        while ((sum >> 16) > 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        int checksum = (~sum) & 0xFFFF;
        resp[10] = (byte) ((checksum >> 8) & 0xFF);
        resp[11] = (byte) (checksum & 0xFF);

        // UDP Header
        resp[20] = (byte) ((srcPort >> 8) & 0xFF);
        resp[21] = (byte) (srcPort & 0xFF);
        resp[22] = (byte) ((dstPort >> 8) & 0xFF);
        resp[23] = (byte) (dstPort & 0xFF);
        int udpTotal = 8 + payloadLen;
        resp[24] = (byte) ((udpTotal >> 8) & 0xFF);
        resp[25] = (byte) (udpTotal & 0xFF);
        resp[26] = 0; // Checksum disabled for IPv4 UDP
        resp[27] = 0;

        // Payload
        System.arraycopy(payload, 0, resp, 28, payloadLen);
        return resp;
    }

    private byte[] buildUdpV6Response(byte[] req, int srcPort, int dstPort, byte[] payload, int payloadLen) {
        int udpLen = 8 + payloadLen;
        int totalLen = 40 + udpLen;
        byte[] resp = new byte[totalLen];

        // IPv6 Header
        resp[0] = 0x60;
        resp[1] = 0x00;
        resp[2] = 0x00;
        resp[3] = 0x00;
        resp[4] = (byte) ((udpLen >> 8) & 0xFF);
        resp[5] = (byte) (udpLen & 0xFF);
        resp[6] = 17;   // Next Header: UDP
        resp[7] = 64;   // Hop Limit

        // Swap Source & Dest IP addresses
        System.arraycopy(req, 24, resp, 8, 16);  // Source = original Dest
        System.arraycopy(req, 8, resp, 24, 16);  // Dest = original Source

        // UDP Header
        resp[40] = (byte) ((srcPort >> 8) & 0xFF);
        resp[41] = (byte) (srcPort & 0xFF);
        resp[42] = (byte) ((dstPort >> 8) & 0xFF);
        resp[43] = (byte) (dstPort & 0xFF);
        resp[44] = (byte) ((udpLen >> 8) & 0xFF);
        resp[45] = (byte) (udpLen & 0xFF);
        resp[46] = 0;
        resp[47] = 0;

        // Payload
        System.arraycopy(payload, 0, resp, 48, payloadLen);

        // IPv6 UDP Checksum (mandatory)
        int sum = 0;
        for (int i = 8; i < 24; i += 2) sum += ((resp[i] & 0xFF) << 8) | (resp[i + 1] & 0xFF);
        for (int i = 24; i < 40; i += 2) sum += ((resp[i] & 0xFF) << 8) | (resp[i + 1] & 0xFF);
        sum += (udpLen & 0xFFFF);
        sum += 17;

        for (int i = 40; i < totalLen; i += 2) {
            if (i == 46) continue;
            int val = (resp[i] & 0xFF) << 8;
            if (i + 1 < totalLen) val |= (resp[i + 1] & 0xFF);
            sum += val;
        }

        while ((sum >> 16) > 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        int csum = (~sum) & 0xFFFF;
        if (csum == 0) csum = 0xFFFF;
        resp[46] = (byte) ((csum >> 8) & 0xFF);
        resp[47] = (byte) (csum & 0xFF);

        return resp;
    }

    private void startPsiphonMode() {
        try {
            sIsConnecting = true;
            sIsRunning = false;
            mIsTunnelConnected = false;
            mSocksPort = 0;
            mLastStatusMsg = "Connecting to Psiphon...";
            LogManager.log("PSIPHON", "Initializing Psiphon Tunnel Core v495...");
            SharedPreferences startPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String startRegion = startPrefs.getString(PREF_EGRESS_REGION, "");
            LogManager.log("CORE", startRegion == null || startRegion.isEmpty()
                    ? "Exit region: Auto (fastest available)"
                    : "Exit region: " + startRegion);
            if (startPrefs.getBoolean(PREF_FAST_PROTOCOLS, true)) {
                LogManager.log("CORE", "Protocol profile: Fast (OSSH + TLS-OSSH, server tactics disabled)");
            }
            updateNotification("BeeVPN Connecting", "Connecting to Psiphon network...");
            broadcastStatus(false, true, mLastStatusMsg);

            // Connect timeout watchdog. 45s is generous relative to the core's
            // 20s EstablishTunnelTimeoutSeconds while still failing fast for users.
            if (mConnectTimeoutRunnable != null) {
                mHandler.removeCallbacks(mConnectTimeoutRunnable);
            }
            mConnectTimeoutRunnable = () -> {
                if (!sIsConnecting || sIsRunning) return;

                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                boolean fastProfile = prefs.getBoolean(PREF_FAST_PROTOCOLS, true);
                if (fastProfile && !mCompatibilityFallbackUsed) {
                    mCompatibilityFallbackUsed = true;
                    LogManager.log("WARN", "Fast profile did not establish in 45s; retrying once with Compatible protocols.");
                    mStatusNotice = "Compatible profile in use (fast profile unavailable)";
                    prefs.edit().putBoolean(PREF_FAST_PROTOCOLS, false).apply();
                    stopVpn();
                    Intent retry = new Intent(this, BeeVpnService.class);
                    retry.setAction(ACTION_CONNECT);
                    retry.putExtra(EXTRA_MODE, MODE_PSIPHON);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(retry);
                    } else {
                        startService(retry);
                    }
                    return;
                }

                LogManager.log("WARN", "Connection attempt timed out after 45s. Resetting.");
                stopVpn();
            };
            mHandler.postDelayed(mConnectTimeoutRunnable, 45000);

            mPsiphonTunnel = PsiphonTunnel.newPsiphonTunnel(new PsiphonTunnel.HostService() {
                @Override
                public Context getContext() {
                    return BeeVpnService.this;
                }

                @Override
                public String getPsiphonConfig() {
                    JSONObject config = new JSONObject();
                    try {
                        config.put("ClientVersion", "495");
                        config.put("PropagationChannelId", "EE0B7486ACAE75AA");
                        config.put("SponsorId", "67DDC3614847FCEA");
                        config.put("DataRootDirectory", getFilesDir().getAbsolutePath());
                        config.put("TunnelWholeDevice", 1);
                        config.put("TunnelPoolSize", 2);
                        config.put("EstablishTunnelTimeoutSeconds", 20);
                        SharedPreferences connPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                        String egressRegion = connPrefs.getString(PREF_EGRESS_REGION, "");
                        config.put("EgressRegion", egressRegion == null ? "" : egressRegion);
                        if (connPrefs.getBoolean(PREF_FAST_PROTOCOLS, true)) {
                            config.put("LimitTunnelProtocols", new JSONArray().put("OSSH").put("TLS-OSSH"));
                            // Tactics can re-enable WebRTC inproxies and override the limit above.
                            config.put("DisableTactics", true);
                        }
                        config.put("EmitDiagnosticNotices", true);
                        config.put("EmitDiagnosticNetworkParameters", true);
                        config.put("EmitBytesTransferred", true);
                        config.put("NetworkLatencyMultiplierLambda", 0.05);

                        // Official Psiphon verified server pools and cryptographic keys
                        config.put("RemoteServerListURLs", new JSONArray("[{\"URL\": \"aHR0cHM6Ly9zMy5hbWF6b25hd3MuY29tL3BzaXBob24vd2ViL2lvaHEtd2FhNC1xNGR0L3NlcnZlcl9saXN0X2NvbXByZXNzZWQ=\", \"OnlyAfterAttempts\": 0, \"SkipVerify\": false}, {\"URL\": \"aHR0cHM6Ly93d3cuY29ycG9yYXRlaGlyZXByZXNzdGguY29tL3dlYi9pb2hxLXdhYTQtcTRkdC9zZXJ2ZXJfbGlzdF9jb21wcmVzc2Vk\", \"OnlyAfterAttempts\": 2, \"SkipVerify\": true}, {\"URL\": \"aHR0cHM6Ly93d3cuYnJhbmRpbmd1c2FnYW1lcmVwLmNvbS93ZWIvaW9ocS13YWE0LXE0ZHQvc2VydmVyX2xpc3RfY29tcHJlc3NlZA==\", \"OnlyAfterAttempts\": 2, \"SkipVerify\": true}, {\"URL\": \"aHR0cHM6Ly93d3cuaGVyYm14ZGlpbmNvcnBvcmF0ZWQuY29tL3dlYi9pb2hxLXdhYTQtcTRkdC9zZXJ2ZXJfbGlzdF9jb21wcmVzc2Vk\", \"OnlyAfterAttempts\": 2, \"SkipVerify\": true}]"));
                        config.put("ObfuscatedServerListRootURLs", new JSONArray("[{\"URL\": \"aHR0cHM6Ly9zMy5hbWF6b25hd3MuY29tL3BzaXBob24vd2ViL2lvaHEtd2FhNC1xNGR0L29zbA==\", \"OnlyAfterAttempts\": 0, \"SkipVerify\": false}, {\"URL\": \"aHR0cHM6Ly93d3cuY29ycG9yYXRlaGlyZXByZXNzdGguY29tL3dlYi9pb2hxLXdhYTQtcTRkdC9vc2w=\", \"OnlyAfterAttempts\": 2, \"SkipVerify\": true}, {\"URL\": \"aHR0cHM6Ly93d3cuYnJhbmRpbmd1c2FnYW1lcmVwLmNvbS93ZWIvaW9ocS13YWE0LXE0ZHQvb3Ns\", \"OnlyAfterAttempts\": 2, \"SkipVerify\": true}, {\"URL\": \"aHR0cHM6Ly93d3cuaGVyYm14ZGlpbmNvcnBvcmF0ZWQuY29tL3dlYi9pb2hxLXdhYTQtcTRkdC9vc2w=\", \"OnlyAfterAttempts\": 2, \"SkipVerify\": true}]"));
                        config.put("RemoteServerListSignaturePublicKey", "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM=");
                        config.put("ServerEntrySignaturePublicKey", "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA=");
                        config.put("ExchangeObfuscationKey", "DpXzloJk1Hw6aSzmKKky0xcahsEHubch81Mi6K0XMlU=");
                        config.put("DNSResolverAlternateServers", new JSONArray("[\"1.1.1.1\", \"1.0.0.1\", \"8.8.8.8\", \"8.8.4.4\"]"));

                        String addParams = loadRawResource(R.raw.additional_parameters);
                        if (!addParams.isEmpty()) {
                            config.put("AdditionalParameters", addParams);
                        }
                    } catch (Exception e) {
                        LogManager.log("ERROR", "Psiphon config build error: " + e.getMessage());
                    }
                    return config.toString();
                }

                @Override
                public void bindToDevice(long fileDescriptor) {
                    boolean protectedOk = BeeVpnService.this.protect((int) fileDescriptor);
                    if (!protectedOk) {
                        LogManager.log("WARN", "VpnService.protect(" + fileDescriptor + ") failed.");
                    }
                }

                @Override
                public void onDiagnosticMessage(final String message) {
                    try {
                        String noticeType = "";
                        String jsonData = "";
                        int colonIdx = message.indexOf(": ");
                        if (colonIdx > 0) {
                            noticeType = message.substring(0, colonIdx).trim();
                            jsonData = message.substring(colonIdx + 2).trim();
                        } else {
                            noticeType = message.trim();
                        }

                        if ("BytesTransferred".equals(noticeType)) return;
                        if ("Homepage".equals(noticeType) ||
                            "ClientUpgradeAvailable".equals(noticeType) ||
                            "ServerTimestamp".equals(noticeType) ||
                            "ActiveAuthorizationIDs".equals(noticeType) ||
                            "TrafficRateLimits".equals(noticeType) ||
                            "BuildInfo".equals(noticeType) ||
                            "ApplicationParameters".equals(noticeType) ||
                            "SessionId".equals(noticeType) ||
                            "NetworkID".equals(noticeType) ||
                            "Info".equals(noticeType)) {
                            return;
                        }

                        if ("ConnectingServer".equals(noticeType)) {
                            JSONObject d = new JSONObject(jsonData);
                            String r = d.optString("region", "Auto");
                            String p = d.optString("protocol", "OSSH");
                            LogManager.log("PROBE", "Connecting to server (region=" + r + ", proto=" + p + ")...");
                        } else if ("ConnectedServer".equals(noticeType)) {
                            JSONObject d = new JSONObject(jsonData);
                            String r = d.optString("region", "Global");
                            String p = d.optString("protocol", "OSSH");
                            String sni = d.optString("tlsOSSHSNIServerName", "");
                            LogManager.log("ACTIVE", "Connected to " + r + " server via " + p + (!sni.isEmpty() ? " (" + sni + ")" : ""));
                        } else if ("ActiveTunnel".equals(noticeType) || "Tunnels".equals(noticeType)) {
                            LogManager.log("STATUS", "Encrypted tunnel online and routing traffic.");
                        } else if ("CandidateServers".equals(noticeType)) {
                            JSONObject d = new JSONObject(jsonData);
                            int count = d.optInt("count", 0);
                            LogManager.log("FLEET", "Candidate servers ready: " + count);
                        } else if ("ClientRegion".equals(noticeType)) {
                            JSONObject d = new JSONObject(jsonData);
                            LogManager.log("GEO", "Local network detected: " + d.optString("region", "Global"));
                        } else if ("Alert".equals(noticeType)) {
                            LogManager.log("ALERT", jsonData);
                        } else if ("Error".equals(noticeType)) {
                            JSONObject d = new JSONObject(jsonData);
                            LogManager.log("WARN", d.optString("message", jsonData));
                        } else if (!noticeType.isEmpty()) {
                            LogManager.log("DIAG", noticeType + (!jsonData.isEmpty() ? ": " + jsonData : ""));
                        }
                    } catch (Exception e) {
                        LogManager.log("DIAG", message);
                    }
                }

                @Override
                public void onAvailableEgressRegions(List<String> regions) {
                    LogManager.log("REGIONS", "Exit regions available: " + regions.size() + " regions (" + regions + ")");
                    Intent regionIntent = new Intent(ACTION_REGIONS_UPDATE);
                    regionIntent.setPackage(getPackageName());
                    regionIntent.putStringArrayListExtra(EXTRA_REGIONS, new ArrayList<>(regions));
                    sendBroadcast(regionIntent);
                }

                @Override
                public void onConnectedServerRegion(String region) {
                    LogManager.log("REGION", "Connected to egress region: " + region);
                }

                @Override
                public void onListeningSocksProxyPort(int port) {
                    mSocksPort = port;
                    LogManager.log("SOCKS", "Psiphon SOCKS proxy ready on port " + port);
                    checkAndEstablishPsiphonVpn();
                }

                @Override
                public void onListeningHttpProxyPort(int port) {
                    LogManager.log("HTTP", "Psiphon HTTP proxy ready on port " + port);
                }

                @Override
                public void onUpstreamProxyError(String message) {
                    LogManager.log("ERROR", "Upstream proxy error: " + message);
                }

                @Override
                public void onConnecting() {
                    LogManager.log("STATUS", "Negotiating connection with server fleet...");
                }

                @Override
                public void onConnected() {
                    sIsRunning = true;
                    sIsConnecting = false;
                    mIsTunnelConnected = true;
                    if (mConnectTimeoutRunnable != null) {
                        mHandler.removeCallbacks(mConnectTimeoutRunnable);
                    }
                    mLastStatusMsg = "Psiphon Connected";
                    LogManager.log("STATUS", "CONNECTED! Encrypted tunnel online.");
                    updateNotification("BeeVPN Connected", "Connected via Psiphon Network");
                    checkAndEstablishPsiphonVpn();
                    broadcastStatus(true, false, mLastStatusMsg);
                }

                @Override
                public void onBytesTransferred(long sent, long received) {
                    mTxBytes.addAndGet(sent);
                    mRxBytes.addAndGet(received);
                }
            });

            mPsiphonTunnel.setVpnMode(true);
            String servers = loadEmbeddedServers();
            LogManager.log("INIT", "Loaded 405 embedded servers. Launching tunnel thread...");
            mPsiphonTunnel.startTunneling(servers);
            startStatsTicker();

        } catch (Exception e) {
            LogManager.log("ERROR", "Psiphon startup exception: " + e.getMessage());
            LogManager.log("WARN", "Falling back to Cloudflare DNS mode...");
            startCloudflareDnsMode();
        }
    }

    private synchronized void checkAndEstablishPsiphonVpn() {
        if (mIsTunnelConnected && mSocksPort > 0 && mVpnInterface == null && !mShouldStop) {
            establishPsiphonVpn(mSocksPort);
        }
    }

    private synchronized void establishPsiphonVpn(int socksPort) {
        if (mVpnInterface != null) return;

        try {
            LogManager.log("VPN", "Establishing TUN interface (10.0.0.1/24) for tun2socks (MTU: 1400)...");
            Builder builder = new Builder();
            builder.setSession("BeeVPN Psiphon Tunnel");
            builder.addAddress("10.0.0.1", 24);
            builder.addRoute("0.0.0.0", 0);
            builder.addDnsServer("10.0.0.2");

            // Prevent IPv6 leaks by blackholing IPv6 into TUN interface
            try {
                builder.addAddress("fd00::1", 128);
                builder.addRoute("::", 0);
            } catch (Exception ignored) {}

            if (!applyAppRouting(builder)) {
                LogManager.log("ERROR", "Aborting connection: split-tunnel selection has no installed apps.");
                mLastStatusMsg = "No apps selected for split tunnel";
                mHandler.post(BeeVpnService.this::stopVpn);
                return;
            }
            builder.setMtu(1400);

            mVpnInterface = builder.establish();
            if (mVpnInterface == null) {
                try { Thread.sleep(300); } catch (Exception ignored) {}
                mVpnInterface = builder.establish();
            }
            if (mVpnInterface == null) {
                LogManager.log("ERROR", "VPN establish() returned null! Please re-grant VPN permission.");
                mLastStatusMsg = "VPN permission required";
                mHandler.post(BeeVpnService.this::stopVpn);
                return;
            }

            sIsRunning = true;
            sIsConnecting = false;
            mStartTime = System.currentTimeMillis();

            mTun2SocksThread = new Thread(() -> {
                try {
                    LogManager.log("TUN2S", "Starting tun2socks bridge to 127.0.0.1:" + socksPort);
                    Tun2SocksJniLoader.runTun2Socks(
                            mVpnInterface.detachFd(),
                            1400,
                            "10.0.0.2",
                            "255.255.255.0",
                            null,
                            "127.0.0.1:" + socksPort,
                            "127.0.0.1:7300",
                            1
                    );
                    LogManager.log("TUN2S", "tun2socks exited.");
                } catch (Throwable t) {
                    LogManager.log("ERROR", "tun2socks fatal error: " + t.getMessage());
                } finally {
                    if (!mShouldStop) {
                        LogManager.log("WARN", "Tunnel bridge stopped unexpectedly.");
                        mHandler.post(BeeVpnService.this::stopVpn);
                    }
                }
            }, "BeeVpn-Tun2Socks");
            mTun2SocksThread.start();

            mLastStatusMsg = "Psiphon Connected";
            updateNotification("BeeVPN Connected", "Connected via Psiphon Network");
            broadcastStatus(true, false, mLastStatusMsg);

        } catch (Exception e) {
            LogManager.log("ERROR", "Failed to establish VPN interface: " + e.getMessage());
            mHandler.post(BeeVpnService.this::stopVpn);
        }
    }

    /**
     * @return false when split tunneling was requested but nothing can be routed;
     *         the caller must abort rather than silently tunnel every app.
     */
    private boolean applyAppRouting(Builder builder) {
        try {
            SharedPreferences prefs = getSharedPreferences("beevpn_prefs", MODE_PRIVATE);
            int routingMode = prefs.getInt("app_routing_mode", -1);
            if (routingMode == -1) {
                boolean tunnelAll = prefs.getBoolean("tunnel_all_apps", true);
                routingMode = tunnelAll ? 0 : 1;
            }
            Set<String> selectedPackages = prefs.getStringSet("selected_packages", null);

            if (routingMode == 1) { // Whitelist: route ONLY selected apps
                if (selectedPackages == null || selectedPackages.isEmpty()) {
                    LogManager.log("WARN", "Split tunnel (Whitelist) has no apps chosen; refusing to tunnel everything.");
                    return false;
                }

                PackageManager pm = getPackageManager();
                int allowedCount = 0;
                for (String pkg : selectedPackages) {
                    try {
                        pm.getPackageInfo(pkg, 0);
                        builder.addAllowedApplication(pkg);
                        allowedCount++;
                    } catch (PackageManager.NameNotFoundException ignored) {
                        // Application uninstalled or unavailable
                    }
                }
                if (allowedCount > 0) {
                    LogManager.log("VPN", "Split tunnel active: routing traffic for " + allowedCount + " selected app(s).");
                    return true;
                }

                LogManager.log("WARN", "None of the " + selectedPackages.size()
                        + " split-tunnel apps are installed; refusing to fall back to full tunnel.");
                return false;
            } else if (routingMode == 2) { // Blacklist: BYPASS selected apps
                builder.addDisallowedApplication(getPackageName());
                int bypassedCount = 0;
                if (selectedPackages != null && !selectedPackages.isEmpty()) {
                    PackageManager pm = getPackageManager();
                    for (String pkg : selectedPackages) {
                        try {
                            pm.getPackageInfo(pkg, 0);
                            builder.addDisallowedApplication(pkg);
                            bypassedCount++;
                        } catch (PackageManager.NameNotFoundException ignored) {
                            // Application uninstalled or unavailable
                        }
                    }
                }
                LogManager.log("VPN", "Split tunnel active: bypassing " + bypassedCount + " app(s) (direct), routing all other device traffic.");
                return true;
            }

            builder.addDisallowedApplication(getPackageName());
            LogManager.log("VPN", "Full tunnel active: routing all device applications.");
            return true;
        } catch (Exception e) {
            LogManager.log("WARN", "App routing setup error: " + e.getMessage());
            return false;
        }
    }

    private String loadRawResource(int resId) {
        try {
            InputStream is = getResources().openRawResource(resId);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString().trim();
        } catch (Exception e) {
            LogManager.log("WARN", "Failed to read resource " + resId + ": " + e.getMessage());
            return "";
        }
    }

    private String loadEmbeddedServers() {
        return loadRawResource(R.raw.servers);
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "0.0 MB";
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    private void startStatsTicker() {
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!sIsRunning && !sIsConnecting) return;

                // Live latency check every 10s. Each probe opens a tunneled TCP
                // connection, so checking every 5s needlessly skewed throughput.
                if (sIsRunning && System.currentTimeMillis() - mLastLatencyCheck > 10000) {
                    mLastLatencyCheck = System.currentTimeMillis();
                    final int mode = sCurrentMode;
                    final int socks = mSocksPort;
                    new Thread(() -> {
                        if (mode == MODE_PSIPHON) {
                            mMeasuredLatencyMs = socks > 0 ? probeLatencyViaSocks(socks) : -1;
                        } else {
                            mMeasuredLatencyMs = probeDirectLatency();
                        }
                    }, "BeeVpn-Ping").start();
                }

                // Live physical traffic accounting fallback via Android kernel UID stats
                if (sIsRunning) {
                    long uidRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
                    long uidTx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid());
                    if (uidRx != android.net.TrafficStats.UNSUPPORTED && uidTx != android.net.TrafficStats.UNSUPPORTED && mUidRxBaseline >= 0) {
                        long rxDelta = Math.max(0, uidRx - mUidRxBaseline);
                        long txDelta = Math.max(0, uidTx - mUidTxBaseline);
                        if (rxDelta > mRxBytes.get()) mRxBytes.set(rxDelta);
                        if (txDelta > mTxBytes.get()) mTxBytes.set(txDelta);
                    }
                }

                long now = SystemClock.elapsedRealtime();
                long dt = (mLastTickerTime > 0) ? (now - mLastTickerTime) : 1000;
                if (dt <= 0) dt = 1000;
                long curRx = mRxBytes.get();
                long curTx = mTxBytes.get();
                if (mLastTickerTime > 0 && sIsRunning) {
                    long rxDiff = curRx - mLastTickerRx;
                    long txDiff = curTx - mLastTickerTx;
                    mRxSpeedBps = (Math.max(0, rxDiff) * 1000) / dt;
                    mTxSpeedBps = (Math.max(0, txDiff) * 1000) / dt;
                } else if (!sIsRunning) {
                    mRxSpeedBps = 0;
                    mTxSpeedBps = 0;
                }
                mLastTickerRx = curRx;
                mLastTickerTx = curTx;
                mLastTickerTime = now;

                broadcastStatus(sIsRunning, sIsConnecting, mLastStatusMsg);
                if (sIsRunning) {
                    String prefix = sCurrentMode == MODE_PSIPHON ? "Protected" : "DNS Only";
                    String stats = prefix + " • ↓ " + formatBytes(mRxBytes.get()) + " (" + formatSpeed(mRxSpeedBps) + ")";
                    updateNotification("BeeVPN Connected", stats);
                }
                mHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    public static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "0.0 KB/s";
        if (bytesPerSec < 1024 * 1024)
            return String.format(java.util.Locale.US, "%.1f KB/s", bytesPerSec / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0));
    }

    private static int probeDirectLatency() {
        Socket s = new Socket();
        try {
            long t0 = System.currentTimeMillis();
            s.connect(new InetSocketAddress("1.1.1.1", 53), 2000);
            return (int) (System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return -1;
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    // Measures the real tunnel path: BeeVPN itself is excluded from the TUN, so a
    // direct socket would always report pre-tunnel latency.
    private static int probeLatencyViaSocks(int socksPort) {
        Socket s = new Socket();
        try {
            long t0 = System.currentTimeMillis();
            s.connect(new InetSocketAddress("127.0.0.1", socksPort), 3000);
            s.setSoTimeout(3000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            out.write(new byte[]{0x05, 0x01, 0x00});
            out.flush();
            byte[] greeting = new byte[2];
            readFully(in, greeting, 2);
            if (greeting[0] != 0x05 || greeting[1] != 0x00) return -1;

            // CONNECT 1.1.1.1:443
            out.write(new byte[]{0x05, 0x01, 0x00, 0x01, 1, 1, 1, 1, 0x01, (byte) 0xBB});
            out.flush();
            byte[] reply = new byte[4];
            readFully(in, reply, 4);
            if (reply[0] != 0x05 || reply[1] != 0x00) return -1;

            int skip;
            if (reply[3] == 0x01) {
                skip = 6;
            } else if (reply[3] == 0x04) {
                skip = 18;
            } else if (reply[3] == 0x03) {
                byte[] len = new byte[1];
                readFully(in, len, 1);
                skip = (len[0] & 0xFF) + 2;
            } else {
                return -1;
            }
            readFully(in, new byte[skip], skip);

            return (int) (System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return -1;
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new IOException("Unexpected end of stream");
            off += n;
        }
    }

    private synchronized void stopVpn() {
        boolean wasActive = sIsRunning || sIsConnecting;
        sIsRunning = false;
        sIsConnecting = false;
        mIsTunnelConnected = false;
        mShouldStop = true;
        mSocksPort = 0;
        mMeasuredLatencyMs = -1;
        mDotHealthy = false;
        mDotRetryAfterMs = 0;
        mDnsQueueWarned = false;

        if (mConnectTimeoutRunnable != null) {
            mHandler.removeCallbacks(mConnectTimeoutRunnable);
            mConnectTimeoutRunnable = null;
        }

        if (mPsiphonTunnel != null) {
            try {
                LogManager.log("CORE", "Stopping Psiphon tunnel...");
                mPsiphonTunnel.stop();
            } catch (Exception ignored) {}
            mPsiphonTunnel = null;
        }

        try {
            Tun2SocksJniLoader.terminateTun2Socks();
        } catch (Throwable ignored) {}

        if (mTun2SocksThread != null) {
            mTun2SocksThread.interrupt();
            mTun2SocksThread = null;
        }

        if (mDnsExecutor != null) {
            closeDotResolvers();
            mDnsExecutor.shutdownNow();
            mDnsExecutor = null;
        }

        if (mWorkerThread != null) {
            mWorkerThread.interrupt();
            mWorkerThread = null;
        }

        if (mVpnInterface != null) {
            try {
                mVpnInterface.close();
            } catch (Exception ignored) {}
            mVpnInterface = null;
        }

        LogManager.log("STATUS", "Tunnel disconnected. Device traffic restored to normal.");
        broadcastStatus(false, false, "Disconnected");
        stopForeground(true);

        if (wasActive) {
            showDisconnectedNotification();
        }

        stopSelf();
    }

    @Override
    public void onRevoke() {
        LogManager.log("VPN", "VPN permission revoked by system or user.");
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(PREF_SHOULD_CONNECT, false).apply();
        stopVpn();
        super.onRevoke();
    }

    private void showDisconnectedNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        createNotificationChannel();

        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nb = new Notification.Builder(this, CHANNEL_ALERTS_ID);
        } else {
            nb = new Notification.Builder(this);
        }

        Notification notif = nb.setContentTitle("BeeVPN Disconnected")
                .setContentText("Tunnel disconnected. Device traffic restored to normal.")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOngoing(false)
                .build();

        nm.notify(NOTIFICATION_DISCONNECTED_ID, notif);
    }

    private void broadcastStatus(boolean isConnected, boolean isConnecting, String msg) {
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_IS_CONNECTED, isConnected);
        intent.putExtra(EXTRA_IS_CONNECTING, isConnecting);
        intent.putExtra(EXTRA_STATUS_MSG, msg);
        long durationSec = isConnected && mStartTime > 0 ? (System.currentTimeMillis() - mStartTime) / 1000 : 0;
        intent.putExtra(EXTRA_DURATION, durationSec);
        intent.putExtra(EXTRA_RX_BYTES, mRxBytes.get());
        intent.putExtra(EXTRA_TX_BYTES, mTxBytes.get());
        intent.putExtra(EXTRA_RX_SPEED, mRxSpeedBps);
        intent.putExtra(EXTRA_TX_SPEED, mTxSpeedBps);
        intent.putExtra(EXTRA_LATENCY, mMeasuredLatencyMs);
        intent.putExtra(EXTRA_ACTIVE_MODE, sCurrentMode);
        intent.putExtra(EXTRA_NOTICE, mStatusNotice);
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel serviceChannel = new NotificationChannel(
                        CHANNEL_ID,
                        "BeeVPN Active Service",
                        NotificationManager.IMPORTANCE_LOW
                );
                serviceChannel.setDescription("Shows active connection and protection status");
                nm.createNotificationChannel(serviceChannel);

                NotificationChannel alertChannel = new NotificationChannel(
                        CHANNEL_ALERTS_ID,
                        "BeeVPN Status Alerts",
                        NotificationManager.IMPORTANCE_DEFAULT
                );
                alertChannel.setDescription("Notifies on connection status changes");
                nm.createNotificationChannel(alertChannel);
            }
        }
    }

    private Notification buildNotification(String title, String text, boolean dnsOnly) {
        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent disconnectIntent = new Intent(this, BeeVpnService.class);
        disconnectIntent.setAction(ACTION_DISCONNECT);
        PendingIntent pDisconnect = PendingIntent.getService(this, 1, disconnectIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nb = new Notification.Builder(this, CHANNEL_ID);
        } else {
            nb = new Notification.Builder(this);
        }

        return nb.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(dnsOnly
                        ? android.R.drawable.ic_dialog_info
                        : android.R.drawable.stat_sys_upload)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", pDisconnect)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(title, text, sCurrentMode == MODE_CLOUDFLARE));
        }
    }

    @Override
    public void onDestroy() {
        if (mNetworkReceiver != null) {
            try { unregisterReceiver(mNetworkReceiver); } catch (Exception ignored) {}
            mNetworkReceiver = null;
        }
        stopVpn();
        super.onDestroy();
    }
}
