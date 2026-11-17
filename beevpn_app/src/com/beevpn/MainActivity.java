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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final int REQUEST_VPN_PERMISSION = 2024;
    private static final String PREF_NAME = "beevpn_prefs";
    private static final String KEY_MODE = "selected_mode";
    private static final String KEY_TUNNEL_ALL = "tunnel_all_apps";
    private static final String KEY_ROUTING_MODE = "app_routing_mode";
    private static final String KEY_SELECTED_PACKAGES = "selected_packages";
    private static final String KEY_REGION = "egress_region";
    private static final String KEY_FAST_PROTOCOLS = "fast_protocols";
    private static final String REGION_AUTO = "Auto (Fastest)";

    public static final int ROUTING_ALL = 0;
    public static final int ROUTING_ONLY_SELECTED = 1;
    public static final int ROUTING_BYPASS_SELECTED = 2;

    private TextView mTvStatusBadge;
    private TextView mTvConnectionState;
    private TextView mTvHint;
    private Button mBtnToggleVpn;
    private TextView mTvDuration;
    private TextView mTvDownload;
    private TextView mTvUpload;
    private TextView mTvDownloadSpeed;
    private TextView mTvUploadSpeed;
    private TextView mTvLatency;
    private TextView mTvServerTitle;
    private TextView mTvServerSub;
    private Spinner mSpinnerMode;
    private Spinner mSpinnerRegion;
    private Spinner mSpinnerProtocol;
    private ArrayAdapter<String> mRegionAdapter;
    private String mSelectedRegion = "";

    private View mCardAppRouting;
    private TextView mTvAppRoutingSub;

    private TextView mTvLogs;
    private ScrollView mScrollLogs;
    private Button mBtnCopyLogs;
    private Button mBtnClearLogs;
    private Button mBtnToggleLogs;
    private android.animation.ObjectAnimator mPulseAnimator;

    private int mSelectedMode = BeeVpnService.MODE_PSIPHON;
    // Mode the service actually runs. Differs from mSelectedMode when Psiphon
    // startup fails and the service falls back to DNS mode.
    private int mActiveMode = BeeVpnService.MODE_PSIPHON;

    private final List<AppEntry> mInstalledApps = new ArrayList<>();
    private final List<Runnable> mPendingAppCallbacks = new ArrayList<>();
    private boolean mAppsLoaded = false;
    private boolean mIsLoadingApps = false;

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
                    long rxSpeed = intent.getLongExtra(BeeVpnService.EXTRA_RX_SPEED, 0);
                    long txSpeed = intent.getLongExtra(BeeVpnService.EXTRA_TX_SPEED, 0);
                    int latency = intent.getIntExtra(BeeVpnService.EXTRA_LATENCY, -1);
                    mActiveMode = intent.getIntExtra(BeeVpnService.EXTRA_ACTIVE_MODE, mSelectedMode);
                    updateUiState(isConnected, isConnecting, statusMsg, durationSec, rxBytes, txBytes, rxSpeed, txSpeed, latency);
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
        mTvDownloadSpeed = findViewById(R.id.tv_download_speed);
        mTvUploadSpeed = findViewById(R.id.tv_upload_speed);
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
        mBtnToggleLogs = findViewById(R.id.btn_toggle_logs);
        if (mBtnToggleLogs != null) {
            mBtnToggleLogs.setOnClickListener(v -> {
                if (mScrollLogs != null) {
                    if (mScrollLogs.getVisibility() == View.VISIBLE) {
                        mScrollLogs.setVisibility(View.GONE);
                        mBtnToggleLogs.setText("SHOW");
                    } else {
                        mScrollLogs.setVisibility(View.VISIBLE);
                        mBtnToggleLogs.setText("HIDE");
                        scrollToBottom();
                    }
                }
            });
        }

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        mSelectedMode = prefs.getInt(KEY_MODE, BeeVpnService.MODE_PSIPHON);

        setupModeSpinner();
        setupConnectionOptions();

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
        syncActiveMode();
        updateUiState(BeeVpnService.isRunning(), BeeVpnService.isConnecting(), null, 0, 0, 0, 0, 0, -1);
    }

    private void syncActiveMode() {
        mActiveMode = (BeeVpnService.isRunning() || BeeVpnService.isConnecting())
                ? BeeVpnService.getCurrentMode()
                : mSelectedMode;
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
                updateOptionAvailability();
                LogManager.log("UI", "Selected mode: " + (position == 0 ? "Psiphon Circumvention" : "Cloudflare DNS"));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupConnectionOptions() {
        mSpinnerRegion = findViewById(R.id.spinner_region);
        mSpinnerProtocol = findViewById(R.id.spinner_protocol);
        if (mSpinnerRegion == null || mSpinnerProtocol == null) return;

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        mSelectedRegion = prefs.getString(KEY_REGION, "");
        boolean fast = prefs.getBoolean(KEY_FAST_PROTOCOLS, true);

        String[] profiles = new String[]{
                "Compatible (all protocols)",
                "Fast (OSSH + TLS)"
        };
        ArrayAdapter<String> profileAdapter = new ArrayAdapter<String>(this, R.layout.spinner_item, profiles);
        profileAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        mSpinnerProtocol.setAdapter(profileAdapter);
        mSpinnerProtocol.setSelection(fast ? 1 : 0, false);
        mSpinnerProtocol.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean fastProfile = position == 1;
                getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_FAST_PROTOCOLS, fastProfile)
                        .apply();
                LogManager.log("UI", fastProfile
                        ? "Protocol profile: Fast (OSSH + TLS-OSSH)"
                        : "Protocol profile: Compatible (all protocols)");
                if (fastProfile) {
                    Toast.makeText(MainActivity.this, "Fast mode can fail on heavily filtered networks",
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        List<String> regionItems = new ArrayList<>();
        regionItems.add(REGION_AUTO);
        mRegionAdapter = new ArrayAdapter<String>(this, R.layout.spinner_item, regionItems);
        mRegionAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        mSpinnerRegion.setAdapter(mRegionAdapter);
        selectSavedRegion();
        mSpinnerRegion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String region = position == 0 ? "" : String.valueOf(parent.getItemAtPosition(position));
                mSelectedRegion = region;
                getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(KEY_REGION, region)
                        .apply();
                updateServerCardText(mSelectedMode);
                LogManager.log("UI", region.isEmpty()
                        ? "Exit region: Auto (fastest available)"
                        : "Exit region: " + region);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        updateOptionAvailability();

        new Thread(() -> {
            List<String> regions = loadEmbeddedRegions();
            runOnUiThread(() -> {
                if (mRegionAdapter == null) return;
                for (String region : regions) {
                    mRegionAdapter.add(region);
                }
                mRegionAdapter.notifyDataSetChanged();
                selectSavedRegion();
            });
        }, "BeeVpn-Regions").start();
    }

    private void selectSavedRegion() {
        if (mSpinnerRegion == null || mRegionAdapter == null) return;

        int index = 0;
        if (mSelectedRegion != null && !mSelectedRegion.isEmpty()) {
            for (int i = 0; i < mRegionAdapter.getCount(); i++) {
                if (mSelectedRegion.equals(mRegionAdapter.getItem(i))) {
                    index = i;
                    break;
                }
            }
        }
        mSpinnerRegion.setSelection(index, false);
    }

    private void updateOptionAvailability() {
        boolean enabled = mSelectedMode == BeeVpnService.MODE_PSIPHON
                && !BeeVpnService.isRunning()
                && !BeeVpnService.isConnecting();
        setOptionControlsEnabled(enabled);
    }

    private List<String> loadEmbeddedRegions() {
        TreeSet<String> regions = new TreeSet<>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(getResources().openRawResource(R.raw.servers), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    String entry = new String(hexToBytes(line), "UTF-8");
                    int jsonStart = entry.indexOf('{');
                    if (jsonStart < 0) continue;
                    JSONObject server = new JSONObject(entry.substring(jsonStart));
                    String region = server.optString("region", "");
                    if (!region.isEmpty()) regions.add(region);
                } catch (Exception ignored) {
                    // Skip malformed server entries
                }
            }
        } catch (Exception e) {
            android.util.Log.w("BeeVPN", "Failed to load embedded regions: " + e.getMessage());
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (Exception ignored) {}
        }
        return new ArrayList<>(regions);
    }

    private static byte[] hexToBytes(String hex) {
        if ((hex.length() & 1) != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Invalid hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private void updateServerCardText(int mode) {
        if (mode == BeeVpnService.MODE_PSIPHON) {
            if (mTvServerTitle != null) {
                if (mSelectedRegion == null || mSelectedRegion.isEmpty()) {
                    mTvServerTitle.setText("Psiphon Fleet (Auto)");
                } else {
                    mTvServerTitle.setText("Psiphon Fleet (" + mSelectedRegion + ")");
                }
            }
            if (mTvServerSub != null) {
                mTvServerSub.setText(mSelectedRegion == null || mSelectedRegion.isEmpty()
                        ? "Global obfuscated circumvention network"
                        : "Exit region pinned to " + mSelectedRegion);
            }
            if (mCardAppRouting != null) mCardAppRouting.setVisibility(View.VISIBLE);
        } else {
            if (mTvServerTitle != null) mTvServerTitle.setText("Cloudflare DNS (1.1.1.1)");
            if (mTvServerSub != null) mTvServerSub.setText("Encrypted DNS (DoT) with UDP fallback");
            if (mCardAppRouting != null) mCardAppRouting.setVisibility(View.GONE);
        }
    }

    private void updateAppRoutingSubtitle() {
        if (mTvAppRoutingSub == null) return;
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        int mode = prefs.getInt(KEY_ROUTING_MODE, -1);
        if (mode == -1) {
            boolean tunnelAll = prefs.getBoolean(KEY_TUNNEL_ALL, true);
            mode = tunnelAll ? ROUTING_ALL : ROUTING_ONLY_SELECTED;
        }
        Set<String> selected = prefs.getStringSet(KEY_SELECTED_PACKAGES, null);
        int count = selected != null ? selected.size() : 0;

        if (mode == ROUTING_ALL) {
            mTvAppRoutingSub.setText("All device apps routed (tap to customize)");
        } else if (mode == ROUTING_ONLY_SELECTED) {
            mTvAppRoutingSub.setText("Tunneling " + count + " selected app" + (count == 1 ? "" : "s") + " only");
        } else {
            mTvAppRoutingSub.setText("Bypassing " + count + " selected app" + (count == 1 ? "" : "s") + " (direct)");
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
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            Set<String> savedSelected = prefs.getStringSet(KEY_SELECTED_PACKAGES, new HashSet<>());
            String myPkg = getPackageName();

            Map<String, AppEntry> appMap = new LinkedHashMap<>();

            // Pass 1: Query all installed packages via PackageManager
            try {
                List<PackageInfo> installedPackages = pm.getInstalledPackages(0);
                if (installedPackages != null) {
                    for (PackageInfo pi : installedPackages) {
                        if (pi == null || pi.packageName == null || pi.packageName.equals(myPkg)) continue;
                        ApplicationInfo ai = pi.applicationInfo;
                        if (ai == null) {
                            try {
                                ai = pm.getApplicationInfo(pi.packageName, 0);
                            } catch (Throwable ignored) {}
                        }
                        if (ai == null) continue;

                        // System app definition: FLAG_SYSTEM is set AND FLAG_UPDATED_SYSTEM_APP is NOT set.
                        // Updated system apps (Chrome, YouTube, Maps, etc.) are treated as user apps.
                        boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                                && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;

                        String label;
                        try {
                            CharSequence cs = ai.loadLabel(pm);
                            label = cs != null ? cs.toString().trim() : pi.packageName;
                        } catch (Throwable t) {
                            label = pi.packageName;
                        }
                        if (label.isEmpty()) label = pi.packageName;

                        Drawable icon = null;
                        try {
                            icon = ai.loadIcon(pm);
                        } catch (Throwable ignored) {}

                        boolean isSelected = savedSelected.contains(pi.packageName);
                        appMap.put(pi.packageName, new AppEntry(label, pi.packageName, icon, isSystem, isSelected));
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("BeeVPN", "Error scanning installed packages: " + t.getMessage());
            }

            // Pass 2: Query launchable activities to ensure 100% of user-facing launchable apps are captured
            try {
                Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
                launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> launcherList = pm.queryIntentActivities(launcherIntent, 0);
                if (launcherList != null) {
                    for (ResolveInfo ri : launcherList) {
                        if (ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
                        String pkg = ri.activityInfo.packageName;
                        if (pkg.equals(myPkg)) continue;

                        ApplicationInfo ai = ri.activityInfo.applicationInfo;
                        if (ai == null) {
                            try {
                                ai = pm.getApplicationInfo(pkg, 0);
                            } catch (Throwable ignored) {}
                        }

                        if (!appMap.containsKey(pkg)) {
                            boolean isSystem = false;
                            if (ai != null) {
                                isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                            }
                            String label;
                            try {
                                CharSequence cs = ri.loadLabel(pm);
                                if (cs == null && ai != null) cs = ai.loadLabel(pm);
                                label = cs != null ? cs.toString().trim() : pkg;
                            } catch (Throwable t) {
                                label = pkg;
                            }
                            if (label.isEmpty()) label = pkg;

                            Drawable icon = null;
                            try {
                                icon = ri.loadIcon(pm);
                                if (icon == null && ai != null) icon = ai.loadIcon(pm);
                            } catch (Throwable ignored) {}

                            boolean isSelected = savedSelected.contains(pkg);
                            appMap.put(pkg, new AppEntry(label, pkg, icon, isSystem, isSelected));
                        } else {
                            // If entry exists but has package name as title, try resolving a human-readable title
                            AppEntry existing = appMap.get(pkg);
                            if (existing != null && existing.name.equals(pkg)) {
                                try {
                                    CharSequence cs = ri.loadLabel(pm);
                                    if (cs != null && !cs.toString().trim().isEmpty()) {
                                        existing.name = cs.toString().trim();
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("BeeVPN", "Error scanning launcher activities: " + t.getMessage());
            }

            List<AppEntry> loaded = new ArrayList<>(appMap.values());

            // User apps first, then system apps. Alphabetical by name.
            Collections.sort(loaded, (a, b) -> {
                if (a.isSystemApp != b.isSystemApp) {
                    return a.isSystemApp ? 1 : -1;
                }
                return a.name.compareToIgnoreCase(b.name);
            });

            runOnUiThread(() -> {
                synchronized (mInstalledApps) {
                    mInstalledApps.clear();
                    mInstalledApps.addAll(loaded);
                    mAppsLoaded = true;
                    mIsLoadingApps = false;
                }
                updateAppRoutingSubtitle();
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
        int currentRoutingMode = prefs.getInt(KEY_ROUTING_MODE, -1);
        if (currentRoutingMode == -1) {
            boolean tunnelAll = prefs.getBoolean(KEY_TUNNEL_ALL, true);
            currentRoutingMode = tunnelAll ? ROUTING_ALL : ROUTING_ONLY_SELECTED;
        }

        TextView tvSelectionCount = dialog.findViewById(R.id.tv_selection_count);
        RadioButton rbAll = dialog.findViewById(R.id.rb_all_apps);
        RadioButton rbTunnelSelected = dialog.findViewById(R.id.rb_tunnel_selected);
        RadioButton rbBypassSelected = dialog.findViewById(R.id.rb_bypass_selected);

        EditText etSearch = dialog.findViewById(R.id.et_search_apps);
        TextView btnClearSearch = dialog.findViewById(R.id.btn_clear_search);

        Button btnTabUser = dialog.findViewById(R.id.btn_tab_user);
        Button btnTabSystem = dialog.findViewById(R.id.btn_tab_system);
        Button btnTabSelected = dialog.findViewById(R.id.btn_tab_selected);
        Button btnTabAll = dialog.findViewById(R.id.btn_tab_all);

        Button btnSelectAll = dialog.findViewById(R.id.btn_action_select_all);
        Button btnInvert = dialog.findViewById(R.id.btn_action_invert);
        Button btnClear = dialog.findViewById(R.id.btn_action_clear);

        ListView lvApps = dialog.findViewById(R.id.lv_installed_apps);
        TextView tvEmpty = dialog.findViewById(R.id.tv_empty_apps);

        Button btnCancel = dialog.findViewById(R.id.btn_cancel_app_dialog);
        Button btnSave = dialog.findViewById(R.id.btn_save_app_dialog);

        if (currentRoutingMode == ROUTING_ALL) {
            rbAll.setChecked(true);
        } else if (currentRoutingMode == ROUTING_ONLY_SELECTED) {
            rbTunnelSelected.setChecked(true);
        } else {
            rbBypassSelected.setChecked(true);
        }

        List<AppEntry> dialogAppList = new ArrayList<>();
        synchronized (mInstalledApps) {
            for (AppEntry e : mInstalledApps) {
                dialogAppList.add(new AppEntry(e.name, e.packageName, e.icon, e.isSystemApp, e.selected));
            }
        }

        AppAdapter adapter = new AppAdapter(this, dialogAppList);
        lvApps.setAdapter(adapter);

        Runnable updateCountsAndTabs = () -> {
            int totalUser = 0;
            int totalSystem = 0;
            int totalSelected = 0;
            for (AppEntry app : dialogAppList) {
                if (app.isSystemApp) totalSystem++; else totalUser++;
                if (app.selected) totalSelected++;
            }
            tvSelectionCount.setText(totalUser + " User Apps • " + totalSystem + " System • " + totalSelected + " Selected");
            btnTabUser.setText("User (" + totalUser + ")");
            btnTabSystem.setText("System (" + totalSystem + ")");
            btnTabSelected.setText("Selected (" + totalSelected + ")");
            btnTabAll.setText("All (" + dialogAppList.size() + ")");
            tvEmpty.setVisibility(adapter.getCount() == 0 ? View.VISIBLE : View.GONE);
        };

        updateCountsAndTabs.run();

        View.OnClickListener tabClickListener = v -> {
            int selectedTab = AppAdapter.TAB_USER;
            int id = v.getId();
            if (id == R.id.btn_tab_system) selectedTab = AppAdapter.TAB_SYSTEM;
            else if (id == R.id.btn_tab_selected) selectedTab = AppAdapter.TAB_SELECTED;
            else if (id == R.id.btn_tab_all) selectedTab = AppAdapter.TAB_ALL;

            btnTabUser.setBackgroundResource(selectedTab == AppAdapter.TAB_USER ? R.drawable.chip_tab_active : R.drawable.chip_tab_inactive);
            btnTabUser.setTextColor(resolveColor(selectedTab == AppAdapter.TAB_USER ? R.color.bee_gold : R.color.text_dim));

            btnTabSystem.setBackgroundResource(selectedTab == AppAdapter.TAB_SYSTEM ? R.drawable.chip_tab_active : R.drawable.chip_tab_inactive);
            btnTabSystem.setTextColor(resolveColor(selectedTab == AppAdapter.TAB_SYSTEM ? R.color.bee_gold : R.color.text_dim));

            btnTabSelected.setBackgroundResource(selectedTab == AppAdapter.TAB_SELECTED ? R.drawable.chip_tab_active : R.drawable.chip_tab_inactive);
            btnTabSelected.setTextColor(resolveColor(selectedTab == AppAdapter.TAB_SELECTED ? R.color.bee_gold : R.color.text_dim));

            btnTabAll.setBackgroundResource(selectedTab == AppAdapter.TAB_ALL ? R.drawable.chip_tab_active : R.drawable.chip_tab_inactive);
            btnTabAll.setTextColor(resolveColor(selectedTab == AppAdapter.TAB_ALL ? R.color.bee_gold : R.color.text_dim));

            adapter.setTab(selectedTab);
            updateCountsAndTabs.run();
        };

        btnTabUser.setOnClickListener(tabClickListener);
        btnTabSystem.setOnClickListener(tabClickListener);
        btnTabSelected.setOnClickListener(tabClickListener);
        btnTabAll.setOnClickListener(tabClickListener);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String q = s != null ? s.toString() : "";
                btnClearSearch.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                adapter.setQuery(q);
                updateCountsAndTabs.run();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnClearSearch.setOnClickListener(v -> etSearch.setText(""));

        lvApps.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry entry = adapter.getItem(position);
            entry.selected = !entry.selected;
            if (entry.selected && rbAll.isChecked()) {
                rbTunnelSelected.setChecked(true);
            }
            adapter.notifyDataSetChanged();
            updateCountsAndTabs.run();
        });

        btnSelectAll.setOnClickListener(v -> {
            for (AppEntry app : adapter.getDisplayedList()) {
                app.selected = true;
            }
            if (rbAll.isChecked()) {
                rbTunnelSelected.setChecked(true);
            }
            adapter.notifyDataSetChanged();
            updateCountsAndTabs.run();
        });

        btnInvert.setOnClickListener(v -> {
            for (AppEntry app : adapter.getDisplayedList()) {
                app.selected = !app.selected;
            }
            if (rbAll.isChecked()) {
                rbTunnelSelected.setChecked(true);
            }
            adapter.notifyDataSetChanged();
            updateCountsAndTabs.run();
        });

        btnClear.setOnClickListener(v -> {
            for (AppEntry app : dialogAppList) {
                app.selected = false;
            }
            adapter.notifyDataSetChanged();
            updateCountsAndTabs.run();
        });

        btnSave.setOnClickListener(v -> {
            int selectedMode = ROUTING_ALL;
            if (rbTunnelSelected.isChecked()) {
                selectedMode = ROUTING_ONLY_SELECTED;
            } else if (rbBypassSelected.isChecked()) {
                selectedMode = ROUTING_BYPASS_SELECTED;
            }

            Set<String> selectedPkgs = new HashSet<>();
            for (AppEntry app : dialogAppList) {
                if (app.selected) {
                    selectedPkgs.add(app.packageName);
                }
            }

            if (selectedMode == ROUTING_ONLY_SELECTED && selectedPkgs.isEmpty()) {
                Toast.makeText(this, "Please select at least 1 app to tunnel, or choose 'Route all applications'", Toast.LENGTH_SHORT).show();
                return;
            }

            synchronized (mInstalledApps) {
                for (AppEntry app : mInstalledApps) {
                    app.selected = selectedPkgs.contains(app.packageName);
                }
            }

            getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_ROUTING_MODE, selectedMode)
                    .putBoolean(KEY_TUNNEL_ALL, selectedMode == ROUTING_ALL)
                    .putStringSet(KEY_SELECTED_PACKAGES, selectedPkgs)
                    .apply();

            updateAppRoutingSubtitle();
            dialog.dismiss();

            String modeName = selectedMode == ROUTING_ALL ? "All device apps"
                    : (selectedMode == ROUTING_ONLY_SELECTED ? "Tunnel only " + selectedPkgs.size() + " app(s)"
                    : "Bypass " + selectedPkgs.size() + " app(s)");
            LogManager.log("UI", "App routing updated: " + modeName);

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
        syncActiveMode();
        updateUiState(BeeVpnService.isRunning(), BeeVpnService.isConnecting(), null, 0, 0, 0, 0, 0, -1);
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

    private void updateUiState(boolean isConnected, boolean isConnecting, String statusMsg, long durationSec, long rxBytes, long txBytes, long rxSpeed, long txSpeed, int latency) {
        boolean optionsEnabled = !isConnected && !isConnecting && mSelectedMode == BeeVpnService.MODE_PSIPHON;
        setOptionControlsEnabled(optionsEnabled);

        if (isConnected) {
            stopConnectPulse();
            mBtnToggleVpn.setText(R.string.disconnect);
            mBtnToggleVpn.setBackgroundResource(R.drawable.btn_disconnect_bg);

            mTvStatusBadge.setText(mActiveMode == BeeVpnService.MODE_PSIPHON ? getString(R.string.status_connected_desc) : "DNS ACTIVE");
            mTvStatusBadge.setBackgroundResource(R.drawable.badge_connected_bg);
            mTvStatusBadge.setTextColor(resolveColor(R.color.status_connected));

            String modeName = statusMsg != null ? statusMsg : (mActiveMode == BeeVpnService.MODE_PSIPHON ? "PSIPHON ACTIVE" : "CLOUDFLARE DNS ACTIVE");
            mTvConnectionState.setText(modeName.toUpperCase(Locale.US));
            mTvConnectionState.setTextColor(resolveColor(R.color.status_connected));

            if (mActiveMode == BeeVpnService.MODE_PSIPHON) {
                mTvHint.setText("Traffic is encrypted and securely routed");
            } else {
                mTvHint.setText("DNS encrypted via DoT; general traffic is direct");
            }

            long hrs = durationSec / 3600;
            long mins = (durationSec % 3600) / 60;
            long secs = durationSec % 60;
            mTvDuration.setText(String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs));

            mTvDownload.setText(BeeVpnService.formatBytes(rxBytes));
            mTvUpload.setText(BeeVpnService.formatBytes(txBytes));
            if (mTvDownloadSpeed != null) {
                mTvDownloadSpeed.setText("↓ " + BeeVpnService.formatSpeed(rxSpeed));
            }
            if (mTvUploadSpeed != null) {
                mTvUploadSpeed.setText("↑ " + BeeVpnService.formatSpeed(txSpeed));
            }

            if (latency > 0) {
                if (latency < 90) {
                    mTvLatency.setText("● " + latency + " ms (Fast)");
                    mTvLatency.setTextColor(resolveColor(R.color.status_connected));
                } else if (latency < 190) {
                    mTvLatency.setText("● " + latency + " ms (Good)");
                    mTvLatency.setTextColor(resolveColor(R.color.bee_gold));
                } else {
                    mTvLatency.setText("● " + latency + " ms (Slow)");
                    mTvLatency.setTextColor(resolveColor(R.color.status_disconnected));
                }
            } else {
                mTvLatency.setText("○ Measuring");
                mTvLatency.setTextColor(resolveColor(R.color.text_dim));
            }

            mSpinnerMode.setEnabled(false);
            mSpinnerMode.setAlpha(0.45f);
        } else if (isConnecting) {
            startConnectPulse();
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
            if (mTvDownloadSpeed != null) mTvDownloadSpeed.setText("↓ 0.0 KB/s");
            if (mTvUploadSpeed != null) mTvUploadSpeed.setText("↑ 0.0 KB/s");

            mTvLatency.setText("○ Probing");
            mTvLatency.setTextColor(resolveColor(R.color.bee_gold));

            mSpinnerMode.setEnabled(false);
            mSpinnerMode.setAlpha(0.45f);
        } else {
            stopConnectPulse();
            mActiveMode = mSelectedMode;
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
            if (mTvDownloadSpeed != null) mTvDownloadSpeed.setText("↓ 0.0 KB/s");
            if (mTvUploadSpeed != null) mTvUploadSpeed.setText("↑ 0.0 KB/s");

            mTvLatency.setText("○ Idle");
            mTvLatency.setTextColor(resolveColor(R.color.text_dim));

            mSpinnerMode.setEnabled(true);
            mSpinnerMode.setAlpha(1f);
        }
    }

    private void startConnectPulse() {
        if (mBtnToggleVpn == null) return;
        if (mPulseAnimator == null) {
            mPulseAnimator = android.animation.ObjectAnimator.ofFloat(mBtnToggleVpn, "scaleX", 1.0f, 1.06f, 1.0f);
            mPulseAnimator.setDuration(1100);
            mPulseAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            mPulseAnimator.setRepeatMode(android.animation.ValueAnimator.RESTART);
            mPulseAnimator.addUpdateListener(anim -> {
                if (mBtnToggleVpn != null) {
                    float val = (Float) anim.getAnimatedValue();
                    mBtnToggleVpn.setScaleY(val);
                }
            });
        }
        if (!mPulseAnimator.isRunning()) {
            mPulseAnimator.start();
        }
    }

    private void stopConnectPulse() {
        if (mPulseAnimator != null && mPulseAnimator.isRunning()) {
            mPulseAnimator.cancel();
        }
        if (mBtnToggleVpn != null) {
            mBtnToggleVpn.setScaleX(1.0f);
            mBtnToggleVpn.setScaleY(1.0f);
        }
    }

    private void setOptionControlsEnabled(boolean enabled) {
        float alpha = enabled ? 1f : 0.45f;
        if (mSpinnerRegion != null) {
            mSpinnerRegion.setEnabled(enabled);
            mSpinnerRegion.setAlpha(alpha);
        }
        if (mSpinnerProtocol != null) {
            mSpinnerProtocol.setEnabled(enabled);
            mSpinnerProtocol.setAlpha(alpha);
        }
    }

    public static class AppEntry {
        public String name;
        public final String packageName;
        public Drawable icon;
        public final boolean isSystemApp;
        public boolean selected;

        public AppEntry(String name, String packageName, Drawable icon, boolean isSystemApp, boolean selected) {
            this.name = name;
            this.packageName = packageName;
            this.icon = icon;
            this.isSystemApp = isSystemApp;
            this.selected = selected;
        }
    }

    private static class AppAdapter extends BaseAdapter {
        public static final int TAB_USER = 0;
        public static final int TAB_SYSTEM = 1;
        public static final int TAB_SELECTED = 2;
        public static final int TAB_ALL = 3;

        private final List<AppEntry> masterList;
        private final List<AppEntry> displayedList = new ArrayList<>();
        private final LayoutInflater inflater;

        private int currentTab = TAB_USER;
        private String currentQuery = "";

        public AppAdapter(Context context, List<AppEntry> masterList) {
            this.masterList = masterList;
            this.inflater = LayoutInflater.from(context);
            applyFilter();
        }

        public void setTab(int tab) {
            this.currentTab = tab;
            applyFilter();
        }

        public void setQuery(String query) {
            this.currentQuery = query != null ? query.trim().toLowerCase(Locale.US) : "";
            applyFilter();
        }

        public void applyFilter() {
            displayedList.clear();
            for (AppEntry entry : masterList) {
                if (currentTab == TAB_USER && entry.isSystemApp) continue;
                if (currentTab == TAB_SYSTEM && !entry.isSystemApp) continue;
                if (currentTab == TAB_SELECTED && !entry.selected) continue;

                if (!currentQuery.isEmpty()) {
                    boolean matchName = entry.name != null && entry.name.toLowerCase(Locale.US).contains(currentQuery);
                    boolean matchPkg = entry.packageName != null && entry.packageName.toLowerCase(Locale.US).contains(currentQuery);
                    if (!matchName && !matchPkg) continue;
                }

                displayedList.add(entry);
            }
            notifyDataSetChanged();
        }

        public List<AppEntry> getDisplayedList() {
            return displayedList;
        }

        @Override
        public int getCount() {
            return displayedList.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return displayedList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        private static class ViewHolder {
            ImageView icon;
            TextView name;
            TextView systemBadge;
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
                holder.systemBadge = convertView.findViewById(R.id.tv_system_badge);
                holder.pkg = convertView.findViewById(R.id.tv_app_package);
                holder.checkBox = convertView.findViewById(R.id.cb_app_selected);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            AppEntry entry = displayedList.get(position);
            holder.name.setText(entry.name);
            holder.pkg.setText(entry.packageName);
            if (holder.systemBadge != null) {
                holder.systemBadge.setVisibility(entry.isSystemApp ? View.VISIBLE : View.GONE);
            }
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
