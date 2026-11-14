package com.reed.fcmguard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String STATE_THEME_PANEL = "theme_panel_open";
    private static final String STATE_THEME_SCROLL = "theme_panel_scroll";
    private static final String[] LANGUAGE_CODES = {
            "system", "en", "zh-CN", "zh-TW", "fr", "ja", "ko", "es", "pt", "de", "ru"
    };

    private EditText keyEdit;
    private EditText itemEdit;
    private TextView statusHeadline;
    private TextView statusMessage;
    /** Tile slots in display order (two per row); filled most severe first. */
    private View[] statusTiles;
    private TextView currentValueText;
    private TextView fcmAppsStatusText;
    private LinearLayout fcmAppsContainer;
    private Button languageButton;
    private Button themeButton;
    private ThemePanel themePanel;
    private Button scanFcmAppsBtn;
    private Switch protectionSwitch;
    private Switch notificationSwitch;
    private Button permissionBtn;
    private RadioGroup appearanceGroup;
    private StickyDashboardLayout dashboard;
    private Switch glassSwitch;
    private TextView glassSummary;
    private boolean suppressGlassCallbacks = false;
    private boolean suppressSwitchCallbacks = false;
    private boolean suppressAppearanceCallbacks = false;
    /** Night bit of the configuration this page was built for. */
    private int shownNightMode;
    private boolean notificationAccessPending = false;
    private boolean fcmListExpanded = false;
    private boolean fcmScanInFlight = false;
    private int fcmScanGeneration = 0;
    private List<FcmAppScanner.AppEntry> scannedFcmApps;
    private AutostartStatusReader.Status[] scannedFcmStatuses;

    /**
     * Package-manager and AppOps queries for other apps are binder calls (and label
     * loading touches their resources), so they never run on the UI thread.
     */
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            runnable.run();
        }, "fcmguard-scan");
        thread.setDaemon(true);
        return thread;
    });

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable notificationAccessFollowUp = new Runnable() {
        @Override public void run() {
            if (!notificationAccessPending || !hasWindowFocus()) return;
            if (SettingsGuard.usePersistentNotification(MainActivity.this) &&
                    !GuardService.canShowPersistentNotification(MainActivity.this)) {
                notificationAccessPending = false;
                openNotificationSettings();
            } else {
                notificationAccessPending = false;
            }
        }
    };

    @Override protected void attachBaseContext(Context newBase) {
        Context localized = LocaleHelper.apply(newBase);
        super.attachBaseContext(ThemeHelper.apply(localized));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        // The palette overlays the pal_* colours of AppTheme before anything is inflated.
        getTheme().applyStyle(Palettes.STYLES[Palettes.current(this)], true);
        super.onCreate(savedInstanceState);
        shownNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        // A recreated activity can keep its window, so set the background explicitly.
        getWindow().setBackgroundDrawable(new ColorDrawable(AppColors.get(this, R.attr.pal_bg)));
        LocaleHelper.migrateLegacyPreference(this);
        configureFullEdgeToEdge();
        setContentView(R.layout.activity_main);
        PageTransition.playPending(this);
        applySystemBarInsets();
        bindViews();
        loadConfigIntoFields();
        setupLanguagePicker();
        setupThemePicker();
        TextView heroTitle = findViewById(R.id.heroTitle);
        heroTitle.setTypeface(Fonts.title());
        heroTitle.setText(BrandText.appName(getString(R.string.app_name)));
        setupAppearance();
        setupGlassMaterial();
        setupSwitches();
        bindActions();
        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_THEME_PANEL)) {
            // Recreated for a theme switch: keep the picker open, scrolled where it was.
            themePanel.restoreScrollX(savedInstanceState.getInt(STATE_THEME_SCROLL, -1));
            themePanel.show(false);
        }
        // refreshStatus() runs in onResume(), which always follows onCreate().
    }

    /**
     * uiMode is handled here (see the manifest) so that a system dark-mode switch while
     * the page follows the system crossfades like an in-app switch instead of flashing.
     */
    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int night = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (night != shownNightMode && ThemeHelper.MODE_SYSTEM.equals(ThemeHelper.getMode(this))) {
            shownNightMode = night;
            PageTransition.recreate(this);
        }
    }

    @Override protected void onPause() {
        // Android is about to recreate the page itself (see PageTransition.captureForRelaunch).
        if (isChangingConfigurations()) PageTransition.captureForRelaunch(this);
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        configureFullEdgeToEdge();
        if (SettingsGuard.isProtectionEnabled(this) &&
                SettingsGuard.usePersistentNotification(this) &&
                GuardService.canShowPersistentNotification(this)) {
            startProtectionService();
        }
        refreshStatus(null);
        recheckFcmAutostartStatuses();
        // Battery saver may have been toggled while we were away; apply after attach so
        // the vendor blur is set on views that already have a render node.
        dashboard.post(this::updateGlassMaterial);
    }

    @Override protected void onDestroy() {
        fcmScanGeneration++;
        backgroundExecutor.shutdownNow();
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            configureFullEdgeToEdge();
            if (notificationAccessPending) {
                uiHandler.removeCallbacks(notificationAccessFollowUp);
                uiHandler.postDelayed(notificationAccessFollowUp, 250L);
            }
        }
    }

    private void bindViews() {
        keyEdit = findViewById(R.id.keyEdit);
        itemEdit = findViewById(R.id.itemEdit);
        statusHeadline = findViewById(R.id.statusHeadline);
        statusMessage = findViewById(R.id.statusMessage);
        statusTiles = new View[] {
                findViewById(R.id.statusTile1), findViewById(R.id.statusTile2),
                findViewById(R.id.statusTile3), findViewById(R.id.statusTile4),
                findViewById(R.id.statusTile5), findViewById(R.id.statusTile6)
        };
        currentValueText = findViewById(R.id.currentValueText);
        fcmAppsStatusText = findViewById(R.id.fcmAppsStatusText);
        fcmAppsContainer = findViewById(R.id.fcmAppsContainer);
        languageButton = findViewById(R.id.languageButton);
        themeButton = findViewById(R.id.themeButton);
        scanFcmAppsBtn = findViewById(R.id.scanFcmAppsBtn);
        protectionSwitch = findViewById(R.id.protectionSwitch);
        notificationSwitch = findViewById(R.id.notificationSwitch);
        permissionBtn = findViewById(R.id.permissionBtn);
        appearanceGroup = findViewById(R.id.appearanceGroup);
        dashboard = findViewById(R.id.root);
        glassSwitch = findViewById(R.id.glassSwitch);
        glassSummary = findViewById(R.id.glassSummary);
    }

    private void loadConfigIntoFields() {
        keyEdit.setText(SettingsGuard.getConfiguredKey(this));
        itemEdit.setText(SettingsGuard.getConfiguredRequiredItem(this));
    }

    private void setupLanguagePicker() {
        String languageName = currentLanguageName();
        languageButton.setText(languageName);
        languageButton.setContentDescription(getString(R.string.language) + ": " + languageName);
        languageButton.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    Intent intent = new Intent(
                            Settings.ACTION_APP_LOCALE_SETTINGS,
                            Uri.parse("package:" + getPackageName())
                    );
                    startActivity(intent);
                    return;
                } catch (Throwable ignored) {}
            }
            showLegacyLanguagePicker();
        });
    }

    /** Native name of the language the UI is actually shown in (resolves "system"). */
    private String currentLanguageName() {
        String code = LocaleHelper.getLanguage(this);
        if ("system".equals(code)) {
            java.util.Locale shown = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    ? getResources().getConfiguration().getLocales().get(0)
                    : getResources().getConfiguration().locale;
            code = LocaleHelper.normalize(shown == null ? null : shown.toLanguageTag());
            if ("system".equals(code)) code = "en"; // Unsupported locales fall back to English.
        }
        String[] names = getResources().getStringArray(R.array.language_entries);
        for (int i = 1; i < LANGUAGE_CODES.length && i < names.length; i++) {
            if (LANGUAGE_CODES[i].equals(code)) return names[i];
        }
        return getString(R.string.language);
    }

    private void showLegacyLanguagePicker() {
        String lang = LocaleHelper.getLanguage(this);
        int checked = 0;
        for (int i = 0; i < LANGUAGE_CODES.length; i++) {
            if (LANGUAGE_CODES[i].equals(lang)) {
                checked = i;
                break;
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.language)
                .setSingleChoiceItems(R.array.language_entries, checked, (d, which) -> {
                    d.dismiss();
                    if (which < 0 || which >= LANGUAGE_CODES.length || LANGUAGE_CODES[which].equals(lang)) {
                        return;
                    }
                    LocaleHelper.setLanguage(this, LANGUAGE_CODES[which]);
                    if (Build.VERSION.SDK_INT < 33) PageTransition.recreate(this);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
    }

    private void setupThemePicker() {
        int index = Palettes.current(this);
        themeButton.setContentDescription(
                getString(R.string.theme_title) + ": " + getString(Palettes.NAMES[index]));
        themePanel = new ThemePanel(this, themeButton, this::onThemePicked);
        themeButton.setOnClickListener(v -> themePanel.show(true));
    }

    /**
     * Applies a theme with the panel left open: the page is recreated under the same
     * crossfade and the panel comes back, so themes can be compared one after another.
     */
    private void onThemePicked(int index) {
        if (index == Palettes.current(this)) return;
        ThemeHelper.setPalette(this, Palettes.IDS[index]);
        themePanel.select(index);
        PageTransition.recreate(this);
    }

    @Override public void onBackPressed() {
        if (themePanel != null && themePanel.isOpen()) {
            themePanel.dismiss();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_THEME_PANEL, themePanel != null && themePanel.isOpen());
        if (themePanel != null) outState.putInt(STATE_THEME_SCROLL, themePanel.scrollX());
    }

    private void setupAppearance() {
        // Segment labels: primary when selected, secondary otherwise (palette-aware).
        ColorStateList segmentText = new ColorStateList(
                new int[][] {{android.R.attr.state_checked}, {}},
                new int[] {
                        AppColors.get(this, R.attr.pal_text_primary),
                        AppColors.get(this, R.attr.pal_text_secondary)
                });
        for (int id : new int[] {R.id.themeSystem, R.id.themeLight, R.id.themeDark}) {
            ((RadioButton) findViewById(id)).setTextColor(segmentText);
        }

        String mode = ThemeHelper.getMode(this);
        suppressAppearanceCallbacks = true;
        if (ThemeHelper.MODE_DARK.equals(mode)) {
            appearanceGroup.check(R.id.themeDark);
        } else if (ThemeHelper.MODE_LIGHT.equals(mode)) {
            appearanceGroup.check(R.id.themeLight);
        } else {
            appearanceGroup.check(R.id.themeSystem);
        }
        suppressAppearanceCallbacks = false;

        appearanceGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressAppearanceCallbacks) return;
            String next;
            if (checkedId == R.id.themeDark) next = ThemeHelper.MODE_DARK;
            else if (checkedId == R.id.themeLight) next = ThemeHelper.MODE_LIGHT;
            else next = ThemeHelper.MODE_SYSTEM;

            if (!next.equals(ThemeHelper.getMode(this))) {
                ThemeHelper.setMode(this, next);
                PageTransition.recreate(this);
            }
        });
    }

    private void setupGlassMaterial() {
        boolean supported = HyperOsMaterial.isDeviceSupported();
        suppressGlassCallbacks = true;
        glassSwitch.setChecked(supported && ThemeHelper.isGlassEnabled(this));
        suppressGlassCallbacks = false;
        glassSwitch.setEnabled(supported);
        glassSummary.setText(supported
                ? R.string.glass_material_summary
                : R.string.glass_material_unavailable);

        glassSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressGlassCallbacks) return;
            ThemeHelper.setGlassEnabled(this, checked);
            PageTransition.crossfade(this, dashboard.glassRegionBottomInWindow(), this::updateGlassMaterial);
        });
    }

    private void updateGlassMaterial() {
        if (isFinishing() || isDestroyed()) return;
        boolean wanted = HyperOsMaterial.isDeviceSupported() &&
                ThemeHelper.isGlassEnabled(this) &&
                HyperOsMaterial.isAllowedNow(this);
        dashboard.setGlassMaterial(wanted);
    }

    private void setupSwitches() {
        suppressSwitchCallbacks = true;
        protectionSwitch.setChecked(SettingsGuard.isProtectionEnabled(this));
        notificationSwitch.setChecked(SettingsGuard.usePersistentNotification(this));
        suppressSwitchCallbacks = false;

        protectionSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressSwitchCallbacks) return;

            if (checked) {
                SettingsGuard.saveConfig(this, keyEdit.getText().toString(), itemEdit.getText().toString());
                if (!Settings.System.canWrite(this)) {
                    SettingsGuard.setProtectionEnabled(this, false);
                    suppressSwitchCallbacks = true;
                    protectionSwitch.setChecked(false);
                    suppressSwitchCallbacks = false;
                    toast(getString(R.string.permission_missing));
                    openWriteSettings();
                    refreshStatus(null);
                    return;
                }

                SettingsGuard.setProtectionEnabled(this, true);
                if (SettingsGuard.usePersistentNotification(this)) {
                    ensurePersistentNotificationAccess();
                }
                startProtectionService();
                SettingsGuard.Result result = SettingsGuard.repair(this);
                if (result.changed) FcmReconnect.kick(this);
                toast(getString(R.string.service_started));
            } else {
                stopService(new Intent(this, GuardService.class));
                SettingsGuard.setProtectionEnabled(this, false);
                toast(getString(R.string.service_stopped));
            }
            refreshStatus(null);
        });

        notificationSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressSwitchCallbacks) return;
            SettingsGuard.setPersistentNotification(this, checked);
            if (checked) {
                ensurePersistentNotificationAccess();
            } else {
                notificationAccessPending = false;
                uiHandler.removeCallbacks(notificationAccessFollowUp);
            }
            if (SettingsGuard.isProtectionEnabled(this)) startProtectionService();
            refreshStatus(null);
        });
    }

    private void bindActions() {
        findViewById(R.id.saveBtn).setOnClickListener(v -> {
            SettingsGuard.saveConfig(this, keyEdit.getText().toString(), itemEdit.getText().toString());
            loadConfigIntoFields();
            if (SettingsGuard.isProtectionEnabled(this)) startProtectionService();
            toast(getString(R.string.saved));
            refreshStatus(getString(R.string.saved));
        });

        permissionBtn.setOnClickListener(v -> openWriteSettings());

        findViewById(R.id.protectNowBtn).setOnClickListener(v -> {
            SettingsGuard.saveConfig(this, keyEdit.getText().toString(), itemEdit.getText().toString());
            SettingsGuard.Result result = SettingsGuard.repair(this);
            if (result.changed) FcmReconnect.kick(this);
            refreshStatus(result.message);
        });

        findViewById(R.id.wakeBtn).setOnClickListener(v -> {
            FcmReconnect.kick(this);
            toast(getString(R.string.wake_sent));
            refreshStatus(getString(R.string.wake_sent));
        });

        findViewById(R.id.diagBtn).setOnClickListener(v -> openFcmDiagnostics());
        scanFcmAppsBtn.setOnClickListener(v -> toggleFcmAppsList());
        findViewById(R.id.openAutostartBtn).setOnClickListener(v -> {
            if (!HyperOsSettings.openAutoStartManager(this)) {
                toast(getString(R.string.autostart_manager_unavailable));
            }
        });
    }

    private void toggleFcmAppsList() {
        if (fcmScanInFlight) return;
        if (fcmListExpanded) {
            fcmScanGeneration++; // Drop any in-flight background re-check.
            fcmListExpanded = false;
            fcmAppsContainer.setVisibility(View.GONE);
            fcmAppsStatusText.setVisibility(View.GONE);
            updateScanButton(false);
            return;
        }
        scanFcmApps();
    }

    private void scanFcmApps() {
        final int generation = ++fcmScanGeneration;
        final Context appContext = getApplicationContext();
        fcmScanInFlight = true;
        scanFcmAppsBtn.setEnabled(false);
        scanFcmAppsBtn.setText(R.string.scanning_fcm_apps);
        fcmAppsStatusText.setVisibility(View.VISIBLE);
        fcmAppsStatusText.setText(R.string.scanning_fcm_apps);

        boolean queued = runInBackground(() -> {
            List<FcmAppScanner.AppEntry> apps = FcmAppScanner.scan(appContext);
            AutostartStatusReader.Status[] statuses = readAutostartStatuses(appContext, apps);
            runOnUiThread(() -> onFcmScanFinished(generation, apps, statuses));
        });
        if (!queued) {
            fcmScanInFlight = false;
            scanFcmAppsBtn.setEnabled(true);
            updateScanButton(false);
            fcmAppsStatusText.setVisibility(View.GONE);
        }
    }

    private void onFcmScanFinished(
            int generation,
            List<FcmAppScanner.AppEntry> apps,
            AutostartStatusReader.Status[] statuses) {
        if (isFinishing() || isDestroyed() || generation != fcmScanGeneration) return;

        fcmScanInFlight = false;
        scanFcmAppsBtn.setEnabled(true);
        scannedFcmApps = apps;
        scannedFcmStatuses = statuses;
        fcmListExpanded = true;
        fcmAppsStatusText.setVisibility(View.VISIBLE);
        updateScanButton(true);

        if (apps.isEmpty()) {
            fcmAppsContainer.removeAllViews();
            fcmAppsContainer.setVisibility(View.GONE);
            fcmAppsStatusText.setText(R.string.no_fcm_apps);
            return;
        }

        renderFcmAppStatuses();
    }

    /**
     * Autostart can be changed in HyperOS while FCM Guard is in the background. Re-read
     * the states off the UI thread and rebuild the rows only when something changed.
     */
    private void recheckFcmAutostartStatuses() {
        if (!fcmListExpanded || fcmScanInFlight ||
                scannedFcmApps == null || scannedFcmApps.isEmpty()) {
            return;
        }
        final int generation = ++fcmScanGeneration;
        final List<FcmAppScanner.AppEntry> apps = scannedFcmApps;
        final Context appContext = getApplicationContext();
        runInBackground(() -> {
            AutostartStatusReader.Status[] statuses = readAutostartStatuses(appContext, apps);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != fcmScanGeneration ||
                        apps != scannedFcmApps) {
                    return;
                }
                if (Arrays.equals(statuses, scannedFcmStatuses)) return;
                scannedFcmStatuses = statuses;
                renderFcmAppStatuses();
            });
        });
    }

    private static AutostartStatusReader.Status[] readAutostartStatuses(
            Context context, List<FcmAppScanner.AppEntry> apps) {
        AutostartStatusReader.Status[] statuses = new AutostartStatusReader.Status[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            FcmAppScanner.AppEntry app = apps.get(i);
            statuses[i] = AutostartStatusReader.check(context, app.packageName, app.uid);
        }
        return statuses;
    }

    private boolean runInBackground(Runnable task) {
        try {
            backgroundExecutor.execute(task);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            return false;
        }
    }

    private void renderFcmAppStatuses() {
        if (scannedFcmApps == null || scannedFcmApps.isEmpty()) return;
        AutostartStatusReader.Status[] statuses = scannedFcmStatuses;
        if (statuses == null || statuses.length != scannedFcmApps.size()) return;

        fcmAppsContainer.removeAllViews();
        int enabledCount = 0;
        int attentionCount = 0;
        int unknownCount = 0;

        for (int i = 0; i < scannedFcmApps.size(); i++) {
            AutostartStatusReader.Status status = statuses[i];
            if (status == AutostartStatusReader.Status.ENABLED) {
                enabledCount++;
            } else if (status == AutostartStatusReader.Status.PARTIAL ||
                    status == AutostartStatusReader.Status.DISABLED) {
                attentionCount++;
            } else {
                unknownCount++;
            }
        }

        if (unknownCount == scannedFcmApps.size()) {
            fcmAppsContainer.setVisibility(View.GONE);
            fcmAppsStatusText.setText(getString(
                    R.string.fcm_autostart_status_unavailable, scannedFcmApps.size()));
            return;
        }

        fcmAppsContainer.setVisibility(View.VISIBLE);
        fcmAppsStatusText.setText(getString(
                R.string.fcm_apps_status_summary,
                scannedFcmApps.size(), enabledCount, attentionCount, unknownCount));

        // Attention first (Disabled, Partial), then Unknown, then Enabled; the scan
        // order (alphabetical) is kept inside each group.
        Integer[] order = new Integer[scannedFcmApps.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(
                attentionRank(statuses[a]), attentionRank(statuses[b])));
        for (int index : order) {
            addFcmAppRow(scannedFcmApps.get(index), statuses[index]);
        }
    }

    private static int attentionRank(AutostartStatusReader.Status status) {
        if (status == AutostartStatusReader.Status.DISABLED) return 0;
        if (status == AutostartStatusReader.Status.PARTIAL) return 1;
        if (status == AutostartStatusReader.Status.UNKNOWN) return 2;
        return 3;
    }

    private void updateScanButton(boolean expanded) {
        if (scanFcmAppsBtn == null) return;
        if (expanded) {
            scanFcmAppsBtn.setText(R.string.collapse_fcm_apps);
            scanFcmAppsBtn.setTextColor(AppColors.get(this, R.attr.pal_blue));
            scanFcmAppsBtn.setBackgroundResource(R.drawable.secondary_button_bg);
        } else {
            scanFcmAppsBtn.setText(R.string.scan_fcm_apps);
            scanFcmAppsBtn.setTextColor(Color.WHITE);
            scanFcmAppsBtn.setBackgroundResource(R.drawable.primary_button_bg);
        }
    }

    private void addFcmAppRow(
            FcmAppScanner.AppEntry app, AutostartStatusReader.Status autostartStatus) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(8), dp(2), dp(8));
        row.setMinimumHeight(dp(52));
        row.setBackground(selectableItemBackground());
        row.setClickable(true);
        row.setContentDescription(app.label + ", " + getString(autostartStatusText(autostartStatus)));
        row.setOnClickListener(v -> {
            if (!HyperOsSettings.openAppPermissionEditor(this, app.packageName)) {
                toast(getString(R.string.app_settings_unavailable));
            }
        });

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);

        TextView label = new TextView(this);
        label.setText(app.label);
        label.setTextColor(AppColors.get(this, R.attr.pal_text_primary));
        label.setTextSize(14f);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView packageName = new TextView(this);
        packageName.setText(app.packageName);
        packageName.setTextColor(AppColors.get(this, R.attr.pal_text_secondary));
        packageName.setTextSize(12f);
        packageName.setMaxLines(1);
        packageName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        packageName.setPadding(0, dp(2), dp(8), 0);

        labels.addView(label);
        labels.addView(packageName);
        row.addView(labels, labelsParams);

        TextView badge = new TextView(this);
        badge.setText(autostartStatusText(autostartStatus));
        badge.setTextColor(AppColors.get(this, autostartStatusTextColor(autostartStatus)));
        badge.setTextSize(11.5f);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setMinWidth(dp(72));
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(makeStatusBadgeBackground(autostartStatus));
        row.addView(badge, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextColor(AppColors.get(this, R.attr.pal_text_secondary));
        chevron.setTextSize(20f);
        chevron.setPadding(dp(8), 0, dp(4), dp(2));
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron);

        fcmAppsContainer.addView(row);

        View divider = new View(this);
        divider.setBackgroundColor(AppColors.get(this, R.attr.pal_divider));
        fcmAppsContainer.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private android.graphics.drawable.Drawable selectableItemBackground() {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return getResources().getDrawable(value.resourceId, getTheme());
    }

    private int autostartStatusText(AutostartStatusReader.Status status) {
        if (status == AutostartStatusReader.Status.ENABLED) return R.string.autostart_enabled;
        if (status == AutostartStatusReader.Status.PARTIAL) return R.string.autostart_partial;
        if (status == AutostartStatusReader.Status.DISABLED) return R.string.autostart_disabled;
        return R.string.autostart_unknown;
    }

    private int autostartStatusTextColor(AutostartStatusReader.Status status) {
        if (status == AutostartStatusReader.Status.ENABLED) return R.attr.pal_badge_text_green;
        if (status == AutostartStatusReader.Status.PARTIAL) return R.attr.pal_badge_text_yellow;
        if (status == AutostartStatusReader.Status.DISABLED) return R.attr.pal_badge_text_red;
        return R.attr.pal_badge_text_neutral;
    }

    private GradientDrawable makeStatusBadgeBackground(AutostartStatusReader.Status status) {
        int color;
        if (status == AutostartStatusReader.Status.ENABLED) {
            color = R.attr.pal_status_value_green_bg;
        } else if (status == AutostartStatusReader.Status.PARTIAL) {
            color = R.attr.pal_status_value_yellow_bg;
        } else if (status == AutostartStatusReader.Status.DISABLED) {
            color = R.attr.pal_status_value_red_bg;
        } else {
            color = R.attr.pal_status_value_neutral_bg;
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(AppColors.get(this, color));
        background.setCornerRadius(dp(12));
        return background;
    }

    private void startProtectionService() {
        Intent service = new Intent(this, GuardService.class);
        try {
            boolean persistent = SettingsGuard.usePersistentNotification(this);
            if (persistent) GuardService.ensureNotificationChannel(this);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && persistent) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Throwable t) {
            toast(t.getClass().getSimpleName());
        }
    }

    private void ensurePersistentNotificationAccess() {
        boolean createdNow = GuardService.ensureNotificationChannel(this);
        if (GuardService.canShowPersistentNotification(this)) {
            notificationAccessPending = false;
            return;
        }

        notificationAccessPending = true;
        uiHandler.removeCallbacks(notificationAccessFollowUp);
        if (createdNow) {
            uiHandler.postDelayed(notificationAccessFollowUp, 900L);
        } else {
            notificationAccessPending = false;
            openNotificationSettings();
        }
    }

    private void openNotificationSettings() {
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                intent = new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())
                );
            }
            startActivity(intent);
        } catch (Throwable ignored) {}
    }

    private void openWriteSettings() {
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + getPackageName())
        );
        startActivity(intent);
    }

    private void openFcmDiagnostics() {
        String[] knownActivities = {
                "com.google.android.gms.gcm.GcmDiagnostics",
                "com.google.android.gms.gtalkservice.diagnostics.GTalkServiceDiagnostics"
        };
        for (String className : knownActivities) {
            if (startGooglePlayServicesActivity(className)) return;
        }

        try {
            PackageInfo info = getPackageManager().getPackageInfo(
                    "com.google.android.gms", PackageManager.GET_ACTIVITIES);
            if (info.activities != null) {
                for (ActivityInfo activity : info.activities) {
                    String name = activity.name;
                    if (name == null) continue;
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    if ((lower.contains("gcm") || lower.contains("fcm") || lower.contains("gtalk")) &&
                            lower.contains("diagnostic")) {
                        if (startGooglePlayServicesActivity(name)) return;
                    }
                }
            }
        } catch (Throwable ignored) {}

        toast(getString(R.string.diagnostics_unavailable));
    }

    private boolean startGooglePlayServicesActivity(String className) {
        try {
            Intent intent = new Intent();
            intent.setClassName("com.google.android.gms", className);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void refreshStatus(String firstLine) {
        boolean canWrite = Settings.System.canWrite(this);
        boolean enabled = SettingsGuard.isProtectionEnabled(this);
        boolean notification = SettingsGuard.usePersistentNotification(this);
        String current = SettingsGuard.read(this);
        boolean present = SettingsGuard.hasRequiredItem(this, current);

        suppressSwitchCallbacks = true;
        protectionSwitch.setChecked(enabled);
        notificationSwitch.setChecked(notification);
        suppressSwitchCallbacks = false;

        // FCM Guard itself must not be frozen by HyperOS, or nothing repairs the whitelist
        // when GMS is removed: it should be on the no-restrict list (HyperOS keeps apps set
        // to battery "No restrictions" there) and its Autostart must not be off. An
        // unreadable Autostart state is not treated as off.
        String self = getPackageName();
        boolean selfWhitelisted = SettingsGuard.containsPackage(current, self);
        AutostartStatusReader.Status selfAutostart = AutostartStatusReader.check(this, self);
        boolean selfAutostartOff = selfAutostart == AutostartStatusReader.Status.DISABLED ||
                selfAutostart == AutostartStatusReader.Status.PARTIAL;
        boolean selfOk = selfWhitelisted && !selfAutostartOff;

        if (enabled && canWrite && present && selfOk) {
            setHeadline(R.string.status_protected, R.attr.pal_green, R.drawable.ic_status_protected);
        } else if (canWrite && present && selfOk) {
            setHeadline(R.string.status_ready, R.attr.pal_blue, R.drawable.ic_status_ready);
        } else {
            setHeadline(R.string.status_attention, R.attr.pal_red, R.drawable.ic_status_attention);
        }

        boolean hasMessage = firstLine != null && !firstLine.trim().isEmpty();
        statusMessage.setVisibility(hasMessage ? View.VISIBLE : View.GONE);
        if (hasMessage) statusMessage.setText(firstLine.trim());

        int autostartTone = selfAutostart == AutostartStatusReader.Status.ENABLED ? TONE_GOOD
                : selfAutostart == AutostartStatusReader.Status.UNKNOWN ? TONE_NEUTRAL : TONE_BAD;
        boolean visibleForeground = notification && enabled && GuardService.canShowPersistentNotification(this);

        java.util.List<StatusItem> items = new java.util.ArrayList<>();
        items.add(new StatusItem(R.string.status_label_permission,
                canWrite ? R.string.status_value_granted : R.string.status_value_not_granted,
                canWrite ? TONE_GOOD : TONE_BAD,
                canWrite ? null : v -> openWriteSettings()));
        items.add(new StatusItem(R.string.status_label_protection,
                enabled ? R.string.status_value_enabled : R.string.status_value_disabled,
                enabled ? TONE_GOOD : TONE_WARN, null));
        items.add(new StatusItem(R.string.status_label_required_item,
                present ? R.string.status_value_yes : R.string.status_value_no,
                present ? TONE_GOOD : TONE_BAD, null));
        items.add(new StatusItem(R.string.status_label_background_mode,
                visibleForeground ? R.string.status_value_foreground : R.string.status_value_quiet,
                visibleForeground ? TONE_GOOD : TONE_WARN, null));
        items.add(new StatusItem(R.string.status_label_self_whitelist,
                selfWhitelisted ? R.string.status_value_yes : R.string.status_value_no,
                selfWhitelisted ? TONE_GOOD : TONE_BAD,
                selfWhitelisted ? null : v -> openOwnBatterySettings()));
        items.add(new StatusItem(R.string.status_label_self_autostart,
                autostartStatusText(selfAutostart), autostartTone,
                autostartTone == TONE_GOOD ? null : v -> openOwnAutostartSettings()));
        // Most severe first (stable), so the row left on top of the collapsed stack is
        // the one that needs attention.
        java.util.Collections.sort(items, (x, y) -> Integer.compare(severity(x.tone), severity(y.tone)));
        for (int i = 0; i < statusTiles.length && i < items.size(); i++) {
            StatusItem item = items.get(i);
            bindStatusRow(statusTiles[i], item.labelRes, item.valueRes, item.tone, item.action);
        }
        currentValueText.setText(buildWhitelistValue(current));
        permissionBtn.setVisibility(canWrite ? View.GONE : View.VISIBLE);
    }

    private void setHeadline(int textRes, int colorRes, int iconRes) {
        statusHeadline.setText(textRes);
        statusHeadline.setTextColor(AppColors.get(this, colorRes));
        statusHeadline.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
    }

    private static final int TONE_GOOD = 0;
    private static final int TONE_WARN = 1;
    private static final int TONE_BAD = 2;
    private static final int TONE_NEUTRAL = 3;

    private static final class StatusItem {
        final int labelRes;
        final int valueRes;
        final int tone;
        final View.OnClickListener action;

        StatusItem(int labelRes, int valueRes, int tone, View.OnClickListener action) {
            this.labelRes = labelRes;
            this.valueRes = valueRes;
            this.tone = tone;
            this.action = action;
        }
    }

    private static int severity(int tone) {
        if (tone == TONE_BAD) return 0;
        if (tone == TONE_WARN) return 1;
        if (tone == TONE_NEUTRAL) return 2;
        return 3;
    }

    /** The tint composited over the card surface: opaque, so stacked rows hide each other. */
    private int opaqueTint(int tintRes) {
        int tint = AppColors.get(this, tintRes);
        int base = AppColors.get(this, R.attr.pal_surface);
        float a = Color.alpha(tint) / 255f;
        return Color.rgb(
                Math.round(Color.red(tint) * a + Color.red(base) * (1f - a)),
                Math.round(Color.green(tint) * a + Color.green(base) * (1f - a)),
                Math.round(Color.blue(tint) * a + Color.blue(base) * (1f - a)));
    }

    /**
     * One status item as a compact rounded tile tinted by its state. Tiles with an
     * action get ripple feedback and a chevron, and open the setting that fixes them.
     */
    private void bindStatusRow(View row, int labelRes, int valueRes, int tone,
            View.OnClickListener action) {
        TextView label = row.findViewById(R.id.statusRowLabel);
        TextView value = row.findViewById(R.id.statusRowValue);
        TextView chevron = row.findViewById(R.id.statusRowChevron);
        int tintRes = tone == TONE_GOOD ? R.attr.pal_status_value_green_bg
                : tone == TONE_WARN ? R.attr.pal_status_value_yellow_bg
                : tone == TONE_NEUTRAL ? R.attr.pal_status_value_neutral_bg : R.attr.pal_status_value_red_bg;
        int valueColorRes = tone == TONE_GOOD ? R.attr.pal_badge_text_green
                : tone == TONE_WARN ? R.attr.pal_badge_text_yellow
                : tone == TONE_NEUTRAL ? R.attr.pal_badge_text_neutral : R.attr.pal_badge_text_red;

        label.setText(labelRes);
        value.setText(valueRes);
        value.setTextColor(AppColors.get(this, valueColorRes));
        chevron.setTextColor(AppColors.get(this, valueColorRes));
        chevron.setVisibility(action != null ? View.VISIBLE : View.GONE);

        GradientDrawable bar = new GradientDrawable();
        bar.setColor(opaqueTint(tintRes));
        bar.setCornerRadius(dp(10));
        if (action != null) {
            row.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(
                            AppColors.get(this, R.attr.pal_ripple_neutral)),
                    bar, null));
            row.setOnClickListener(action);
        } else {
            row.setBackground(bar);
            row.setOnClickListener(null);
            row.setClickable(false);
        }
    }

    private void openOwnBatterySettings() {
        if (!HyperOsSettings.openBatterySettings(this, getPackageName(), getString(R.string.app_name))) {
            toast(getString(R.string.app_settings_unavailable));
        }
    }

    private void openOwnAutostartSettings() {
        if (!HyperOsSettings.openAppPermissionEditor(this, getPackageName()) &&
                !HyperOsSettings.openAutoStartManager(this)) {
            toast(getString(R.string.autostart_manager_unavailable));
        }
    }

    private GradientDrawable makePill(int colorRes, int radiusPx) {
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(AppColors.get(this, colorRes));
        pill.setCornerRadius(radiusPx);
        return pill;
    }

    private CharSequence buildWhitelistValue(String current) {
        if (current == null || current.trim().isEmpty()) {
            SpannableStringBuilder missing = new SpannableStringBuilder(getString(R.string.missing_current));
            missing.setSpan(
                    new BackgroundColorSpan(AppColors.get(this, R.attr.pal_status_value_red_bg)),
                    0,
                    missing.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            return missing;
        }

        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] packages = current.split(",");
        for (String raw : packages) {
            String packageName = raw.trim();
            if (packageName.isEmpty()) continue;
            if (out.length() > 0) out.append(", ");
            int start = out.length();
            out.append(packageName);
            int end = out.length();

            int colorRes = packageChipColor(packageName);
            if (colorRes != 0) {
                out.setSpan(
                        new BackgroundColorSpan(AppColors.get(this, colorRes)),
                        start,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                );
                out.setSpan(
                        new StyleSpan(Typeface.BOLD),
                        start,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                );
            }
        }
        return out;
    }

    private int packageChipColor(String packageName) {
        if ("com.google.android.gms".equals(packageName)) return R.attr.pal_status_value_blue_bg;
        if (getPackageName().equals(packageName)) return R.attr.pal_status_value_purple_bg;
        return 0;
    }

    /**
     * Called from onCreate(), onResume() and onWindowFocusChanged() because HyperOS can
     * reset legacy-target windows after returning from system settings. Every setter is
     * guarded so the common case (nothing changed) costs no window-manager round trip.
     */
    @SuppressWarnings("deprecation")
    private void configureFullEdgeToEdge() {
        Window window = getWindow();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int translucent = WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS |
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
            int currentFlags = window.getAttributes().flags;
            if ((currentFlags & translucent) != 0) window.clearFlags(translucent);
            if ((currentFlags & WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) == 0) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            }
            if (window.getStatusBarColor() != Color.TRANSPARENT) {
                window.setStatusBarColor(Color.TRANSPARENT);
            }
            if (window.getNavigationBarColor() != Color.TRANSPARENT) {
                window.setNavigationBarColor(Color.TRANSPARENT);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (window.isStatusBarContrastEnforced()) window.setStatusBarContrastEnforced(false);
            if (window.isNavigationBarContrastEnforced()) window.setNavigationBarContrastEnforced(false);
        }

        boolean dark = ThemeHelper.isDark(this);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!dark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (!dark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        View decor = window.getDecorView();
        if (decor.getSystemUiVisibility() != flags) decor.setSystemUiVisibility(flags);
    }

    @SuppressWarnings("deprecation")
    private void applySystemBarInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;

        final View root = findViewById(R.id.root);
        final View scroll = findViewById(R.id.scroll);
        final View content = findViewById(R.id.contentRoot);
        final int baseLeft = content.getPaddingLeft();
        final int baseRight = content.getPaddingRight();
        final int baseBottom = content.getPaddingBottom();
        final int extraBottom = dp(8);

        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = Math.max(
                    insets.getSystemWindowInsetBottom(),
                    insets.getStableInsetBottom()
            );
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) bottom = Math.max(bottom, cutout.getSafeInsetBottom());
            }

            content.setPadding(
                    baseLeft,
                    content.getPaddingTop(),
                    baseRight,
                    baseBottom + bottom + extraBottom
            );
            return insets;
        });
        root.requestApplyInsets();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
