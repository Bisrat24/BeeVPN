package com.beevpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
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
import java.util.List;
import java.util.Set;

import ca.psiphon.PsiphonTunnel;
import ca.psiphon.Tun2SocksJniLoader;

public class BeeVpnService extends VpnService {

    public static final String ACTION_CONNECT = "com.beevpn.ACTION_CONNECT";
    public static final String ACTION_DISCONNECT = "com.beevpn.ACTION_DISCONNECT";
    public static final String ACTION_STATUS_UPDATE = "com.beevpn.ACTION_STATUS_UPDATE";

    public static final String EXTRA_MODE = "extra_mode";
    public static final int MODE_PSIPHON = 0;
    public static final int MODE_CLOUDFLARE = 1;

    public static final String EXTRA_IS_CONNECTED = "extra_is_connected";
    public static final String EXTRA_IS_CONNECTING = "extra_is_connecting";
    public static final String EXTRA_STATUS_MSG = "extra_status_msg";
    public static final String EXTRA_DURATION = "extra_duration";
    public static final String EXTRA_RX_BYTES = "extra_rx_bytes";
    public static final String EXTRA_TX_BYTES = "extra_tx_bytes";
    public static final String EXTRA_LATENCY = "extra_latency";

    private static final String CHANNEL_ID = "beevpn_channel";
    private static final String CHANNEL_ALERTS_ID = "beevpn_alerts";
    private static final int NOTIFICATION_ID = 7701;
    private static final int NOTIFICATION_DISCONNECTED_ID = 7702;

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

    private long mStartTime = 0;
    private final java.util.concurrent.atomic.AtomicLong mRxBytes = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong mTxBytes = new java.util.concurrent.atomic.AtomicLong(0);
    private volatile int mMeasuredLatencyMs = -1;
    private long mLastLatencyCheck = 0;
    private String mLastStatusMsg = "Ready";

    private Handler mHandler;

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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            LogManager.log("CORE", "Disconnect command received from user.");
            stopVpn();
            return START_NOT_STICKY;
        }

        int mode = MODE_PSIPHON;
        if (intent != null) {
            mode = intent.getIntExtra(EXTRA_MODE, MODE_PSIPHON);
        }
        sCurrentMode = mode;

        startVpn(mode);
        return START_STICKY;
    }

    private synchronized void startVpn(int mode) {
        if (sIsRunning || sIsConnecting) return;

        createNotificationChannel();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_DISCONNECTED_ID);
        }
        startForeground(NOTIFICATION_ID, buildNotification("BeeVPN Tunnel", "Securing connection..."));

        mStartTime = 0;
        mRxBytes.set(0);
        mTxBytes.set(0);
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
        try {
            LogManager.log("DNS", "Building DNS resolver VPN interface (10.8.0.2)...");
            Builder builder = new Builder();
            builder.setSession("BeeVPN Cloudflare DNS");
            builder.addAddress("10.8.0.2", 24);
            builder.addDnsServer("1.1.1.1");
            builder.addDnsServer("1.0.0.1");
            builder.addRoute("1.1.1.1", 32);
            builder.addRoute("1.0.0.1", 32);
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
            LogManager.log("DNS", "ACTIVE! Fast DNS resolver active (1.1.1.1). General device traffic is direct.");
            updateNotification("BeeVPN DNS Active", "DNS via 1.1.1.1 (General traffic direct)");
            broadcastStatus(true, false, mLastStatusMsg);

            startDnsWorker();
            startStatsTicker();
        } catch (Exception e) {
            LogManager.log("ERROR", "Failed to start Cloudflare DNS mode: " + e.getMessage());
            stopVpn();
        }
    }

    private void startDnsWorker() {
        mDnsExecutor = java.util.concurrent.Executors.newFixedThreadPool(4);
        mWorkerThread = new Thread(() -> {
            FileInputStream in = new FileInputStream(mVpnInterface.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(mVpnInterface.getFileDescriptor());
            byte[] packet = new byte[32768];

            try {
                InetAddress dnsServer = InetAddress.getByName("1.1.1.1");
                LogManager.log("DNS", "DNS worker active. Forwarding queries to 1.1.1.1 via parallel pool.");

                while (!mShouldStop) {
                    int length = in.read(packet);
                    if (length <= 0) continue;

                    mTxBytes.addAndGet(length);

                    int version = (packet[0] >> 4) & 0x0F;
                    int protocol = packet[9] & 0xFF;

                    if (version == 4 && protocol == 17) { // IPv4 UDP
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
                                DatagramSocket socket = null;
                                try {
                                    socket = new DatagramSocket();
                                    protect(socket);
                                    socket.setSoTimeout(3000);

                                    DatagramPacket outPacket = new DatagramPacket(query, query.length, dnsServer, 53);
                                    socket.send(outPacket);

                                    byte[] respBuf = new byte[4096];
                                    DatagramPacket inPacket = new DatagramPacket(respBuf, respBuf.length);
                                    socket.receive(inPacket);

                                    int respDnsLen = inPacket.getLength();
                                    mRxBytes.addAndGet(respDnsLen);

                                    byte[] respIpPacket = buildUdpResponse(
                                            rawPacketCopy, ipHdrLen,
                                            dstPort, srcPort,
                                            respBuf, respDnsLen
                                    );
                                    synchronized (out) {
                                        out.write(respIpPacket);
                                    }
                                } catch (Exception ignored) {
                                } finally {
                                    if (socket != null) socket.close();
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

    private void startPsiphonMode() {
        try {
            sIsConnecting = true;
            sIsRunning = false;
            mIsTunnelConnected = false;
            mSocksPort = 0;
            mLastStatusMsg = "Connecting to Psiphon...";
            LogManager.log("PSIPHON", "Initializing Psiphon Tunnel Core v495...");
            updateNotification("BeeVPN Connecting", "Connecting to Psiphon network...");
            broadcastStatus(false, true, mLastStatusMsg);

            // Connect timeout watchdog (75s)
            if (mConnectTimeoutRunnable != null) {
                mHandler.removeCallbacks(mConnectTimeoutRunnable);
            }
            mConnectTimeoutRunnable = () -> {
                if (sIsConnecting && !sIsRunning) {
                    LogManager.log("WARN", "Connection attempt timed out after 75s. Resetting.");
                    stopVpn();
                }
            };
            mHandler.postDelayed(mConnectTimeoutRunnable, 75000);

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
                        config.put("EgressRegion", "");
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

            applyAppRouting(builder);
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

    private void applyAppRouting(Builder builder) {
        try {
            SharedPreferences prefs = getSharedPreferences("beevpn_prefs", MODE_PRIVATE);
            boolean tunnelAll = prefs.getBoolean("tunnel_all_apps", true);
            Set<String> selectedPackages = prefs.getStringSet("selected_packages", null);

            if (!tunnelAll && selectedPackages != null && !selectedPackages.isEmpty()) {
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
                    return;
                }
            }

            builder.addDisallowedApplication(getPackageName());
            LogManager.log("VPN", "Full tunnel active: routing all device applications.");
        } catch (Exception e) {
            LogManager.log("WARN", "App routing setup error: " + e.getMessage());
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (Exception ignored) {}
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

                // Live latency check every 5s while connected
                if (sIsRunning && System.currentTimeMillis() - mLastLatencyCheck > 5000) {
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

                broadcastStatus(sIsRunning, sIsConnecting, mLastStatusMsg);
                if (sIsRunning) {
                    String prefix = sCurrentMode == MODE_PSIPHON ? "Protected" : "DNS Active";
                    String stats = prefix + " (↓ " + formatBytes(mRxBytes.get()) + "  •  ↑ " + formatBytes(mTxBytes.get()) + ")";
                    updateNotification("BeeVPN Connected", stats);
                }
                mHandler.postDelayed(this, 1000);
            }
        }, 1000);
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
        intent.putExtra(EXTRA_LATENCY, mMeasuredLatencyMs);
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

    private Notification buildNotification(String title, String text) {
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
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", pDisconnect)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(title, text));
        }
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
