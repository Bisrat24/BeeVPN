package com.beevpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class BeeVpnTileService extends TileService {

    private boolean mReceiverRegistered = false;
    private final BroadcastReceiver mStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BeeVpnService.ACTION_STATUS_UPDATE.equals(intent.getAction())) {
                updateTileState();
            }
        }
    };

    @Override
    public void onStartListening() {
        super.onStartListening();
        if (!mReceiverRegistered) {
            try {
                registerReceiver(mStatusReceiver, new IntentFilter(BeeVpnService.ACTION_STATUS_UPDATE));
                mReceiverRegistered = true;
            } catch (Exception ignored) {
                // A duplicated registration is harmless; the next onStartListening retries.
            }
        }
        updateTileState();
    }

    @Override
    public void onStopListening() {
        if (mReceiverRegistered) {
            try {
                unregisterReceiver(mStatusReceiver);
            } catch (Exception ignored) {
            }
            mReceiverRegistered = false;
        }
        super.onStopListening();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (BeeVpnService.isRunning() || BeeVpnService.isConnecting()) {
            Intent intent = new Intent(this, BeeVpnService.class);
            intent.setAction(BeeVpnService.ACTION_DISCONNECT);
            startService(intent);
        } else {
            Intent vpnIntent = VpnService.prepare(this);
            if (vpnIntent != null) {
                Intent mainIntent = new Intent(this, MainActivity.class);
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivityAndCollapse(mainIntent);
            } else {
                Intent connectIntent = new Intent(this, BeeVpnService.class);
                connectIntent.setAction(BeeVpnService.ACTION_CONNECT);
                int mode = getSharedPreferences("beevpn_prefs", MODE_PRIVATE).getInt("selected_mode", BeeVpnService.MODE_PSIPHON);
                connectIntent.putExtra(BeeVpnService.EXTRA_MODE, mode);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(connectIntent);
                } else {
                    startService(connectIntent);
                }
            }
        }
        updateTileState();
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean running = BeeVpnService.isRunning();
        boolean connecting = BeeVpnService.isConnecting();
        int mode = (running || connecting)
                ? BeeVpnService.getCurrentMode()
                : getSharedPreferences("beevpn_prefs", MODE_PRIVATE)
                        .getInt("selected_mode", BeeVpnService.MODE_PSIPHON);
        boolean dnsOnly = mode == BeeVpnService.MODE_CLOUDFLARE;

        if (running) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel(dnsOnly ? "BeeVPN: DNS only" : "BeeVPN: Protected");
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setSubtitle(dnsOnly ? "Traffic direct" : "Connected");
            }
        } else if (connecting) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel("BeeVPN: Securing");
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setSubtitle("Connecting...");
            }
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel(dnsOnly ? "BeeVPN: DNS" : "BeeVPN");
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setSubtitle("Tap to connect");
            }
        }
        tile.updateTile();
    }
}
