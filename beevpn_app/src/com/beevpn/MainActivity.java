package com.beevpn;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int REQUEST_VPN_PERMISSION = 2024;
    private static final String PREF_NAME = "beevpn_prefs";
    private static final String KEY_MODE = "selected_mode";
    private static final String KEY_TUNNEL_ALL = "tunnel_all_apps";
    private static final String KEY_SELECTED_PACKAGES = "selected_packages";

    private TextView mTvStatusBadge;
    private TextView mTvConnectionState;
    private TextView mTvHint;
    private Button mBtnToggleVpn;
    private TextView mTvDuration;
    private TextView mTvDownload;
    private TextView mTvUpload;
    private TextView mTvLatency;
    private TextView mTvServerTitle;
    private TextView mTvServerSub;
    private Spinner mSpinnerMode;

    private View mCardAppRouting;
    private TextView mTvAppRoutingSub;

    private TextView mTvLogs;
    private ScrollView mScrollLogs;
    private Button mBtnCopyLogs;
    private Button mBtnClearLogs;

    private int mSelectedMode = BeeVpnService.MODE_PSIPHON;

    private final List<AppEntry> mInstalledApps = new ArrayList<>();
    private final List<Runnable> mPendingAppCallbacks = new ArrayList<>();
    private boolean mAppsLoaded = false;
    private boolean mIsLoadingApps = false;

    private static final Set<String> POPULAR_PACKAGES = new HashSet<>(Arrays.asList(
            "org.telegram.messenger",
            "org.telegram.plus",
            "org.thunderdog.challegram",
            "org.telegram.messenger.web",
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.twitter.android",
            "com.instagram.android",
            "com.facebook.katana",
            "com.facebook.lite",
            "com.facebook.orca",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.spotify.music"
    ));

    private final BroadcastReceiver mStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                if (BeeVpnService.ACTION_STATUS_UPDATE.equals(intent.getAction())) {
                    boolean isConnected = intent.getBooleanExtra(BeeVpnService.EXTRA_IS_CONNECTED, false);
                    boolean isConnecting = intent.getBooleanExtra(BeeVpnService.EXTRA_IS_CONNECTING, false);
                    String statusMsg = intent.getStringExtra(BeeVpnService.EXTRA_STATUS_MSG);
                    long durationSec = intent.getLongExtra(BeeVpnService.EXTRA_DURATION, 0);
                    long rxBytes = intent.getLongExtra(BeeVpnService.EXTRA_RX_BYTES, 0);
                    long txBytes = intent.getLongExtra(BeeVpnService.EXTRA_TX_BYTES, 0);
                    int latency = intent.getIntExtra(BeeVpnService.EXTRA_LATENCY, -1);
                    updateUiState(isConnected, isConnecting, statusMsg, durationSec, rxBytes, txBytes, latency);
                } else if (LogManager.ACTION_NEW_LOG.equals(intent.getAction())) {
                    String line = intent.getStringExtra(LogManager.EXTRA_LOG_LINE);
                    if (line != null && mTvLogs != null) {
                        mTvLogs.append(line + "\n");
                        scrollToBottom();
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        LogManager.init(this);

        mTvStatusBadge = findViewById(R.id.tv_status_badge);
        mTvConnectionState = findViewById(R.id.tv_connection_state);
        mTvHint = findViewById(R.id.tv_hint);
        mBtnToggleVpn = findViewById(R.id.btn_toggle_vpn);
        mTvDuration = findViewById(R.id.tv_duration);
        mTvDownload = findViewById(R.id.tv_download);
        mTvUpload = findViewById(R.id.tv_upload);
        mTvLatency = findViewById(R.id.tv_latency);
        mTvServerTitle = findViewById(R.id.tv_server_title);
        mTvServerSub = findViewById(R.id.tv_server_sub);
        mSpinnerMode = findViewById(R.id.spinner_mode);

        mCardAppRouting = findViewById(R.id.card_app_routing);
        mTvAppRoutingSub = findViewById(R.id.tv_app_routing_sub);

        mTvLogs = findViewById(R.id.tv_logs);
        mScrollLogs = findViewById(R.id.scroll_logs);
        mBtnCopyLogs = findViewById(R.id.btn_copy_logs);
        mBtnClearLogs = findViewById(R.id.btn_clear_logs);

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        mSelectedMode = prefs.getInt(KEY_MODE, BeeVpnService.MODE_PSIPHON);

        setupModeSpinner();

        mBtnToggleVpn.setOnClickListener(v -> toggleVpn());

        if (mCardAppRouting != null) {
            mCardAppRouting.setOnClickListener(v -> showAppSelectionDialog());
        }

        mBtnCopyLogs.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                ClipData clip = ClipData.newPlainText("BeeVPN Logs", mTvLogs.getText().toString());
                cm.setPrimaryClip(clip);
                Toast.makeText(MainActivity.this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });

        mBtnClearLogs.setOnClickListener(v -> {
            LogManager.clear();
            mTvLogs.setText(LogManager.getAllLogs());
        });

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
            }
        }

        loadInstalledAppsAsync(null);
        updateAppRoutingSubtitle();
        updateUiState(BeeVpnService.isRunning(), BeeVpnService.isConnecting(), null, 0, 0, 0, -1);
    }

    private int resolveColor(int resId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getColor(resId);
        } else {
            return getResources().getColor(resId);
        }
    }

    private void setupModeSpinner() {
        String[] modes = new String[]{
                "1. Psiphon Circumvention (Bypass)",
                "2. Cloudflare DNS (1.1.1.1 Fast)"
        };

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.spinner_item, modes);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        mSpinnerMode.setAdapter(adapter);

        mSpinnerMode.setSelection(mSelectedMode);
        updateServerCardText(mSelectedMode);

        mSpinnerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mSelectedMode = position;
                getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                        .edit()
                        .putInt(KEY_MODE, mSelectedMode)
                        .apply();
                updateServerCardText(mSelectedMode);
                LogManager.log("UI", "Selected mode: " + (position == 0 ? "Psiphon Circumvention" : "Cloudflare DNS"));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void updateServerCardText(int mode) {
        if (mode == BeeVpnService.MODE_PSIPHON) {
            if (mTvServerTitle != null) mTvServerTitle.setText("Psiphon Fleet (Auto)");
            if (mTvServerSub != null) mTvServerSub.setText("Global obfuscated circumvention network");
            if (mCardAppRouting != null) mCardAppRouting.setVisibility(View.VISIBLE);
        } else {
            if (mTvServerTitle != null) mTvServerTitle.setText("Cloudflare DNS (1.1.1.1)");
            if (mTvServerSub != null) mTvServerSub.setText("Fast public DNS resolver (bypasses DNS filtering)");
            if (mCardAppRouting != null) mCardAppRouting.setVisibility(View.GONE);
        }
    }

    private void updateAppRoutingSubtitle() {
        if (mTvAppRoutingSub == null) return;
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        boolean tunnelAll = prefs.getBoolean(KEY_TUNNEL_ALL, true);
        Set<String> selected = prefs.getStringSet(KEY_SELECTED_PACKAGES, null);
        int count = selected != null ? selected.size() : 0;

        if (tunnelAll || count == 0) {
            mTvAppRoutingSub.setText("All device apps routed (tap to customize)");
        } else {
            mTvAppRoutingSub.setText("Tunneling " + count + " selected app" + (count > 1 ? "s" : "") + " only");
        }
    }

    private synchronized void loadInstalledAppsAsync(final Runnable onComplete) {
        if (mAppsLoaded) {
            if (onComplete != null) runOnUiThread(onComplete);
            return;
        }
        if (onComplete != null) {
            synchronized (mPendingAppCallbacks) {
                mPendingAppCallbacks.add(onComplete);
            }
        }
        if (mIsLoadingApps) return;
        mIsLoadingApps = true;

        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN, null);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);

            SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            Set<String> savedSelected = prefs.getStringSet(KEY_SELECTED_PACKAGES, new HashSet<>());

            List<AppEntry> loaded = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String myPkg = getPackageName();

            for (ResolveInfo ri : resolveInfos) {
                if (ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(myPkg) || seen.contains(pkg)) continue;
                seen.add(pkg);

                String label = ri.loadLabel(pm).toString();
                Drawable icon = ri.loadIcon(pm);
                boolean isSelected = savedSelected.contains(pkg);
                loaded.add(new AppEntry(label, pkg, icon, isSelected));
            }

            Collections.sort(loaded, (a, b) -> a.name.compareToIgnoreCase(b.name));

            runOnUiThread(() -> {
                synchronized (mInstalledApps) {
                    mInstalledApps.clear();
                    mInstalledApps.addAll(loaded);
                    mAppsLoaded = true;
                    mIsLoadingApps = false;
                }
                List<Runnable> pending;
                synchronized (mPendingAppCallbacks) {
                    pending = new ArrayList<>(mPendingAppCallbacks);
                    mPendingAppCallbacks.clear();
                }
                for (Runnable r : pending) {
                    r.run();
                }
            });
        }, "BeeVpn-AppLoader").start();
    }

    private void showAppSelectionDialog() {
        if (!mAppsLoaded) {
            Toast.makeText(this, "Loading installed apps, please wait...", Toast.LENGTH_SHORT).show();
            loadInstalledAppsAsync(this::showAppSelectionDialog);
            return;
        }

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_app_selection);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        boolean currentTunnelAll = prefs.getBoolean(KEY_TUNNEL_ALL, true);

        RadioGroup rgMode = dialog.findViewById(R.id.rg_tunnel_mode);
        RadioButton rbAll = dialog.findViewById(R.id.rb_all_apps);
        RadioButton rbSelected = dialog.findViewById(R.id.rb_selected_apps);
        Button btnPopular = dialog.findViewById(R.id.btn_quick_popular);
        Button btnClear = dialog.findViewById(R.id.btn_clear_all_apps);
        ListView lvApps = dialog.findViewById(R.id.lv_installed_apps);
        Button btnCancel = dialog.findViewById(R.id.btn_cancel_app_dialog);
        Button btnSave = dialog.findViewById(R.id.btn_save_app_dialog);

        if (currentTunnelAll) {
            rbAll.setChecked(true);
        } else {
            rbSelected.setChecked(true);
        }

        List<AppEntry> dialogAppList = new ArrayList<>();
        synchronized (mInstalledApps) {
            for (AppEntry e : mInstalledApps) {
                dialogAppList.add(new AppEntry(e.name, e.packageName, e.icon, e.selected));
            }
        }

        AppAdapter adapter = new AppAdapter(this, dialogAppList);
        lvApps.setAdapter(adapter);

        lvApps.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry entry = dialogAppList.get(position);
            entry.selected = !entry.selected;
            adapter.notifyDataSetChanged();
            if (entry.selected) {
                rbSelected.setChecked(true);
            }
        });

        btnPopular.setOnClickListener(v -> {
            int count = 0;
            for (AppEntry app : dialogAppList) {
                if (POPULAR_PACKAGES.contains(app.packageName)) {
                    app.selected = true;
                    count++;
                }
            }
            adapter.notifyDataSetChanged();
            rbSelected.setChecked(true);
            Toast.makeText(this, "Selected " + count + " popular apps", Toast.LENGTH_SHORT).show();
        });

        btnClear.setOnClickListener(v -> {
            for (AppEntry app : dialogAppList) {
                app.selected = false;
            }
            adapter.notifyDataSetChanged();
        });

        btnSave.setOnClickListener(v -> {
            boolean tunnelAll = rbAll.isChecked();
            Set<String> selectedPkgs = new HashSet<>();
            for (AppEntry app : dialogAppList) {
                if (app.selected) {
                    selectedPkgs.add(app.packageName);
                }
            }

            if (!tunnelAll && selectedPkgs.isEmpty()) {
                Toast.makeText(this, "Please select at least 1 app, or choose 'Route all applications'", Toast.LENGTH_SHORT).show();
                return;
            }

            synchronized (mInstalledApps) {
                for (AppEntry app : mInstalledApps) {
                    app.selected = selectedPkgs.contains(app.packageName);
                }
            }

            getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_TUNNEL_ALL, tunnelAll)
                    .putStringSet(KEY_SELECTED_PACKAGES, selectedPkgs)
                    .apply();

            updateAppRoutingSubtitle();
            dialog.dismiss();

            LogManager.log("UI", "App routing: " + (tunnelAll ? "All device apps" : selectedPkgs.size() + " app(s) selected"));

            if (BeeVpnService.isRunning() || BeeVpnService.isConnecting()) {
                Toast.makeText(this, "Routing rules saved! Reconnect BeeVPN to apply to new tunnel.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "App routing preferences saved.", Toast.LENGTH_SHORT).show();
            }
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void scrollToBottom() {
        if (mScrollLogs != null) {
            mScrollLogs.post(() -> mScrollLogs.fullScroll(View.FOCUS_DOWN));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BeeVpnService.ACTION_STATUS_UPDATE);
        filter.addAction(LogManager.ACTION_NEW_LOG);
        registerReceiver(mStatusReceiver, filter);

        if (mTvLogs != null) {
            mTvLogs.setText(LogManager.getAllLogs());
            scrollToBottom();
        }

        updateAppRoutingSubtitle();
        updateUiState(BeeVpnService.isRunning(), BeeVpnService.isConnecting(), null, 0, 0, 0, -1);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(mStatusReceiver);
        } catch (Exception ignored) {}
    }

    private void toggleVpn() {
        if (BeeVpnService.isRunning() || BeeVpnService.isConnecting()) {
            Intent intent = new Intent(this, BeeVpnService.class);
            intent.setAction(BeeVpnService.ACTION_DISCONNECT);
            startService(intent);
        } else {
            Intent vpnIntent = VpnService.prepare(this);
            if (vpnIntent != null) {
                startActivityForResult(vpnIntent, REQUEST_VPN_PERMISSION);
            } else {
                startVpnService();
            }
        }
    }

    private void startVpnService() {
        Intent intent = new Intent(this, BeeVpnService.class);
        intent.setAction(BeeVpnService.ACTION_CONNECT);
        intent.putExtra(BeeVpnService.EXTRA_MODE, mSelectedMode);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                startVpnService();
            } else {
                Toast.makeText(this, "VPN permission is required to create tunnel", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void updateUiState(boolean isConnected, boolean isConnecting, String statusMsg, long durationSec, long rxBytes, long txBytes, int latency) {
        if (isConnected) {
            mBtnToggleVpn.setText(R.string.disconnect);
            mBtnToggleVpn.setBackgroundResource(R.drawable.btn_disconnect_bg);

            mTvStatusBadge.setText(mSelectedMode == BeeVpnService.MODE_PSIPHON ? getString(R.string.status_connected_desc) : "DNS ACTIVE");
            mTvStatusBadge.setBackgroundResource(R.drawable.badge_connected_bg);
            mTvStatusBadge.setTextColor(resolveColor(R.color.status_connected));

            String modeName = statusMsg != null ? statusMsg : (mSelectedMode == BeeVpnService.MODE_PSIPHON ? "PSIPHON ACTIVE" : "CLOUDFLARE DNS ACTIVE");
            mTvConnectionState.setText(modeName.toUpperCase(Locale.US));
            mTvConnectionState.setTextColor(resolveColor(R.color.status_connected));

            if (mSelectedMode == BeeVpnService.MODE_PSIPHON) {
                mTvHint.setText("Traffic is encrypted and securely routed");
            } else {
                mTvHint.setText("DNS queries routed via 1.1.1.1 (general traffic direct)");
            }

            long hrs = durationSec / 3600;
            long mins = (durationSec % 3600) / 60;
            long secs = durationSec % 60;
            mTvDuration.setText(String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs));

            mTvDownload.setText(BeeVpnService.formatBytes(rxBytes));
            mTvUpload.setText(BeeVpnService.formatBytes(txBytes));

            if (latency > 0) {
                mTvLatency.setText("● " + latency + " ms");
            } else {
                mTvLatency.setText("○ Measuring");
            }
            mTvLatency.setTextColor(resolveColor(R.color.status_connected));

            mSpinnerMode.setEnabled(false);
        } else if (isConnecting) {
            mBtnToggleVpn.setText("CANCEL");
            mBtnToggleVpn.setBackgroundResource(R.drawable.btn_connect_bg);

            mTvStatusBadge.setText("CONNECTING");
            mTvStatusBadge.setBackgroundResource(R.drawable.badge_status_bg);
            mTvStatusBadge.setTextColor(resolveColor(R.color.bee_gold));

            mTvConnectionState.setText(statusMsg != null ? statusMsg.toUpperCase(Locale.US) : "SECURING TUNNEL...");
            mTvConnectionState.setTextColor(resolveColor(R.color.bee_gold));

            mTvHint.setText("Negotiating handshake with server fleet...");

            mTvDuration.setText("00:00:00");
            mTvDownload.setText("0.0 MB");
            mTvUpload.setText("0.0 MB");
            mTvLatency.setText("○ Probing");
            mTvLatency.setTextColor(resolveColor(R.color.bee_gold));

            mSpinnerMode.setEnabled(false);
        } else {
            mBtnToggleVpn.setText(R.string.connect);
            mBtnToggleVpn.setBackgroundResource(R.drawable.btn_connect_bg);

            mTvStatusBadge.setText(R.string.status_not_connected);
            mTvStatusBadge.setBackgroundResource(R.drawable.badge_status_bg);
            mTvStatusBadge.setTextColor(resolveColor(R.color.status_disconnected));

            mTvConnectionState.setText("DISCONNECTED");
            mTvConnectionState.setTextColor(resolveColor(R.color.text_dim));

            mTvHint.setText("Select network mode and tap to connect");

            mTvDuration.setText("00:00:00");
            mTvDownload.setText("0.0 MB");
            mTvUpload.setText("0.0 MB");
            mTvLatency.setText("○ Idle");
            mTvLatency.setTextColor(resolveColor(R.color.text_dim));

            mSpinnerMode.setEnabled(true);
        }
    }

    public static class AppEntry {
        public final String name;
        public final String packageName;
        public final Drawable icon;
        public boolean selected;

        public AppEntry(String name, String packageName, Drawable icon, boolean selected) {
            this.name = name;
            this.packageName = packageName;
            this.icon = icon;
            this.selected = selected;
        }
    }

    private static class AppAdapter extends BaseAdapter {
        private final Context context;
        private final List<AppEntry> apps;
        private final LayoutInflater inflater;

        public AppAdapter(Context context, List<AppEntry> apps) {
            this.context = context;
            this.apps = apps;
            this.inflater = LayoutInflater.from(context);
        }

        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        private static class ViewHolder {
            ImageView icon;
            TextView name;
            TextView pkg;
            CheckBox checkBox;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_app_select, parent, false);
                holder = new ViewHolder();
                holder.icon = convertView.findViewById(R.id.iv_app_icon);
                holder.name = convertView.findViewById(R.id.tv_app_name);
                holder.pkg = convertView.findViewById(R.id.tv_app_package);
                holder.checkBox = convertView.findViewById(R.id.cb_app_selected);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            AppEntry entry = apps.get(position);
            holder.name.setText(entry.name);
            holder.pkg.setText(entry.packageName);
            if (entry.icon != null) {
                holder.icon.setImageDrawable(entry.icon);
            } else {
                holder.icon.setImageResource(R.drawable.ic_bee_logo);
            }
            holder.checkBox.setChecked(entry.selected);

            return convertView;
        }
    }
}
