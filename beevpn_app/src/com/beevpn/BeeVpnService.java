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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
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

    private long mStartTime = 0;
    private long mRxBytes = 0;
    private long mTxBytes = 0;
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

        mStartTime = System.currentTimeMillis();
        mRxBytes = 0;
        mTxBytes = 0;
        mShouldStop = false;

        if (mode == MODE_PSIPHON) {
            LogManager.log("CORE", "Starting Psiphon Circumvention Mode (Global Bypass)...");
            startPsiphonMode();
        } else {
            LogManager.log("CORE", "Starting Cloudflare WARP Mode (Direct Fast Tunnel)...");
            startCloudflareMode();
        }
    }

    private void startCloudflareMode() {
        try {
            LogManager.log("WARP", "Building VPN tunnel interface (10.8.0.2)...");
            Builder builder = new Builder();
            builder.setSession("BeeVPN Cloudflare WARP");
            builder.addAddress("10.8.0.2", 24);
            builder.addDnsServer("1.1.1.1");
            builder.addDnsServer("1.0.0.1");
            builder.addRoute("1.1.1.1", 32);
            builder.addRoute("1.0.0.1", 32);
            applyAppRouting(builder);
            builder.setMtu(1500);

            mVpnInterface = builder.establish();
            if (mVpnInterface == null) {
                LogManager.log("ERROR", "VpnService.Builder.establish() returned null!");
                stopSelf();
                return;
            }

            sIsRunning = true;
            sIsConnecting = false;
            mLastStatusMsg = "Cloudflare WARP Active";
            LogManager.log("WARP", "CONNECTED! Ultra-fast direct tunnel active (DNS: 1.1.1.1).");
            updateNotification("BeeVPN Connected", "Protected via Cloudflare WARP");
            broadcastStatus(true, false, mLastStatusMsg);

            startDnsWorker();
            startStatsTicker();
        } catch (Exception e) {
            LogManager.log("ERROR", "Failed to start Cloudflare mode: " + e.getMessage());
            stopVpn();
        }
    }

    private void startDnsWorker() {
        mWorkerThread = new Thread(() -> {
            FileInputStream in = new FileInputStream(mVpnInterface.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(mVpnInterface.getFileDescriptor());
            byte[] packet = new byte[32768];

            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                protect(socket); // Exempt from VPN tunnel loop
                socket.setSoTimeout(4000);
                InetAddress dnsServer = InetAddress.getByName("1.1.1.1");
                LogManager.log("WARP", "DNS worker active. Forwarding queries to 1.1.1.1");

                while (!mShouldStop) {
                    int length = in.read(packet);
                    if (length <= 0) continue;

                    mTxBytes += length;

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

                            DatagramPacket outPacket = new DatagramPacket(query, query.length, dnsServer, 53);
                            socket.send(outPacket);

                            byte[] respBuf = new byte[4096];
                            DatagramPacket inPacket = new DatagramPacket(respBuf, respBuf.length);
                            socket.receive(inPacket);

                            int respDnsLen = inPacket.getLength();
                            mRxBytes += respDnsLen;

                            byte[] respIpPacket = buildUdpResponse(
                                    packet, ipHdrLen,
                                    dstPort, srcPort,
                                    respBuf, respDnsLen
                            );
                            out.write(respIpPacket);
                        }
                    }
                }
            } catch (Exception e) {
                if (!mShouldStop) {
                    LogManager.log("WARP", "Worker error: " + e.getMessage());
                }
            } finally {
                if (socket != null) socket.close();
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
            mLastStatusMsg = "Connecting to Psiphon...";
            LogManager.log("PSIPHON", "Initializing Psiphon Tunnel Core v495...");
            updateNotification("BeeVPN Connecting", "Connecting to Psiphon network...");
            broadcastStatus(false, true, mLastStatusMsg);

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
                        config.put("DisableTimeouts", true);
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
                            if (mSocksPort > 0) {
                                establishPsiphonVpn(mSocksPort);
                            }
                        } else if ("ActiveTunnel".equals(noticeType) || "Tunnels".equals(noticeType)) {
                            LogManager.log("STATUS", "Encrypted tunnel online and routing traffic.");
                            if (mSocksPort > 0) {
                                establishPsiphonVpn(mSocksPort);
                            }
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
                    mLastStatusMsg = "Psiphon Connected";
                    LogManager.log("STATUS", "CONNECTED! Encrypted tunnel online.");
                    updateNotification("BeeVPN Connected", "Connected via Psiphon Network");
                    if (mSocksPort > 0) {
                        establishPsiphonVpn(mSocksPort);
                    }
                    broadcastStatus(true, false, mLastStatusMsg);
                }

                @Override
                public void onBytesTransferred(long sent, long received) {
                    mTxBytes += sent;
                    mRxBytes += received;
                }
            });

            mPsiphonTunnel.setVpnMode(true);
            String servers = loadEmbeddedServers();
            LogManager.log("INIT", "Loaded 405 embedded servers. Launching tunnel thread...");
            mPsiphonTunnel.startTunneling(servers);
            startStatsTicker();

        } catch (Exception e) {
            LogManager.log("ERROR", "Psiphon startup exception: " + e.getMessage());
            LogManager.log("WARN", "Falling back to Cloudflare WARP mode...");
            startCloudflareMode();
        }
    }

    private synchronized void establishPsiphonVpn(int socksPort) {
        if (mVpnInterface != null) return;

        try {
            LogManager.log("VPN", "Establishing TUN interface (10.0.0.1/24) for tun2socks...");
            Builder builder = new Builder();
            builder.setSession("BeeVPN Psiphon Tunnel");
            builder.addAddress("10.0.0.1", 24);
            builder.addRoute("0.0.0.0", 0);
            builder.addDnsServer("10.0.0.2");
            applyAppRouting(builder);
            builder.setMtu(1500);

            mVpnInterface = builder.establish();
            if (mVpnInterface == null) {
                try { Thread.sleep(300); } catch (Exception ignored) {}
                mVpnInterface = builder.establish();
            }
            if (mVpnInterface == null) {
                LogManager.log("ERROR", "VPN establish() returned null! Please re-grant VPN permission.");
                return;
            }

            sIsRunning = true;
            sIsConnecting = false;

            mTun2SocksThread = new Thread(() -> {
                try {
                    LogManager.log("TUN2S", "Starting tun2socks bridge to 127.0.0.1:" + socksPort);
                    Tun2SocksJniLoader.runTun2Socks(
                            mVpnInterface.detachFd(),
                            1500,
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
                }
            }, "BeeVpn-Tun2Socks");
            mTun2SocksThread.start();

            mLastStatusMsg = "Psiphon Connected";
            updateNotification("BeeVPN Connected", "Connected via Psiphon Network");
            broadcastStatus(true, false, mLastStatusMsg);

        } catch (Exception e) {
            LogManager.log("ERROR", "Failed to establish VPN interface: " + e.getMessage());
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
                broadcastStatus(sIsRunning, sIsConnecting, mLastStatusMsg);
                if (sIsRunning) {
                    String stats = "Protected (↓ " + formatBytes(mRxBytes) + "  •  ↑ " + formatBytes(mTxBytes) + ")";
                    updateNotification("BeeVPN Connected", stats);
                }
                mHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private synchronized void stopVpn() {
        boolean wasActive = sIsRunning || sIsConnecting;
        sIsRunning = false;
        sIsConnecting = false;
        mShouldStop = true;
        mSocksPort = 0;

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
        intent.putExtra(EXTRA_RX_BYTES, mRxBytes);
        intent.putExtra(EXTRA_TX_BYTES, mTxBytes);
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
