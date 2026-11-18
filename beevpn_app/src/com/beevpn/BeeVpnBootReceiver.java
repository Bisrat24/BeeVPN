package com.beevpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** Restores a connection that was active when the device was last on. */
public class BeeVpnBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        SharedPreferences prefs = context.getSharedPreferences("beevpn_prefs", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("should_connect", false)) return;
        if (BeeVpnService.isRunning() || BeeVpnService.isConnecting()) return;

        Intent connect = new Intent(context, BeeVpnService.class);
        connect.setAction(BeeVpnService.ACTION_CONNECT);
        connect.putExtra(BeeVpnService.EXTRA_MODE,
                prefs.getInt("selected_mode", BeeVpnService.MODE_PSIPHON));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(connect);
        } else {
            context.startService(connect);
        }
    }
}
