package com.beevpn;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

public class LogManager {

    public static final String ACTION_NEW_LOG = "com.beevpn.ACTION_NEW_LOG";
    public static final String EXTRA_LOG_LINE = "extra_log_line";
    public static final String EXTRA_ALL_LOGS = "extra_all_logs";

    private static final int MAX_LOG_ENTRIES = 250;
    private static final LinkedList<String> sLogs = new LinkedList<>();
    private static final SimpleDateFormat sDateFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static Context sContext = null;

    public static synchronized void init(Context context) {
        sContext = context.getApplicationContext();
    }

    public static synchronized void log(String tag, String message) {
        if (message == null || message.trim().isEmpty()) return;

        String time = sDateFormat.format(new Date());
        String formatted = String.format(Locale.US, "[%s] %-6s %s", time, tag, message.trim());

        Log.d("BeeVPN-" + tag, message);

        if (sLogs.size() >= MAX_LOG_ENTRIES) {
            sLogs.removeFirst();
        }
        sLogs.addLast(formatted);

        if (sContext != null) {
            Intent intent = new Intent(ACTION_NEW_LOG);
            intent.setPackage(sContext.getPackageName());
            intent.putExtra(EXTRA_LOG_LINE, formatted);
            sContext.sendBroadcast(intent);
        }
    }

    public static synchronized String getAllLogs() {
        if (sLogs.isEmpty()) {
            return "[READY] BeeVPN Log Console initialized.\n";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : sLogs) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        sLogs.clear();
        log("SYS", "Logs cleared.");
    }
}
