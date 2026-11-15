package com.alofcm;

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
            "system", "en", "vi", "zh-CN", "zh-TW", "fr", "ja", "ko", "es", "pt", "de", "ru"
    };

    private Button heartbeatIntervalBtn;
    private EditText keyEdit;
    private EditText itemEdit;
    private TextView statusHeadline;
    private TextView statusMessage;
    /** Tile slots in display order (two per row); filled most severe first. */
    private View[] statusTiles;
    private TextView currentValueText;
    private TextView fcmAppsStatusText;
    private LinearLayout fcmAppsContainer;
    private TextView fcmMarkedTitle;
    private LinearLayout fcmMarkedContainer;
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
    /** App whose Autostart page was just opened; asked about on return. */
    private String pendingMarkPackage;
    private CharSequence pendingMarkLabel;
    private boolean fcmListExpanded = false;
    private boolean fcmScanInFlight = false;
    private int fcmScanGeneration = 0;
    private List<FcmAppScanner.AppEntry> scannedFcmApps;

    /**
     * Package-manager and AppOps queries for other apps are binder calls (and label
     * loading touches their resources), so they never run on the UI thread.
     */
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            runnable.run();
        }, "alofcm-scan");
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
        setupSectionTabs();
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
        refreshFcmLists();
        if (pendingMarkPackage != null) {
            String pkg = pendingMarkPackage;
            CharSequence label = pendingMarkLabel;
            pendingMarkPackage = null;
            pendingMarkLabel = null;
            dashboard.postDelayed(() -> askAutostartMark(pkg, label), 300L);
        }
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
        heartbeatIntervalBtn = findViewById(R.id.heartbeatIntervalBtn);
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
        fcmMarkedTitle = findViewById(R.id.fcmMarkedTitle);
        fcmMarkedContainer = findViewById(R.id.fcmMarkedContainer);
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

    private static final String PREF_SECTION = "dashboard_section";

    /** Shows one section at a time; the chosen tab is remembered across launches. */
    private void setupSectionTabs() {
        RadioGroup tabs = findViewById(R.id.sectionTabs);
        ColorStateList tabText = new ColorStateList(
                new int[][] {{android.R.attr.state_checked}, {}},
                new int[] {
                        AppColors.get(this, R.attr.pal_text_primary),
                        AppColors.get(this, R.attr.pal_text_secondary)
                });
        int[] ids = {R.id.tabProtection, R.id.tabApps, R.id.tabTools, R.id.tabAppearance};
        for (int id : ids) ((RadioButton) findViewById(id)).setTextColor(tabText);

        int saved = getSharedPreferences(SettingsGuard.PREFS, MODE_PRIVATE).getInt(PREF_SECTION, 0);
        int index = saved >= 0 && saved < ids.length ? saved : 0;
        tabs.check(ids[index]);
        showSection(index);
        tabs.setOnCheckedChangeListener((group, checkedId) -> {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] != checkedId) continue;
                showSection(i);
                getSharedPreferences(SettingsGuard.PREFS, MODE_PRIVATE)
                        .edit().putInt(PREF_SECTION, i).apply();
            }
        });
    }

    private void showSection(int index) {
        findViewById(R.id.sectionProtection).setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.sectionNotes).setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.sectionApps).setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.sectionTools).setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        findViewById(R.id.sectionAppearance).setVisibility(index == 3 ? View.VISIBLE : View.GONE);
    }

    private void setupGlassMaterial() {
        boolean supported = SystemMaterial.isDeviceSupported(this);
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
        boolean wanted = SystemMaterial.isDeviceSupported(this) &&
                ThemeHelper.isGlassEnabled(this) &&
                SystemMaterial.isAllowedNow(this);
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
                // WRITE_SETTINGS is only needed for the optional whitelist key; the
                // OriginOS heartbeat works without it.
                if (SettingsGuard.isWhitelistConfigured(this) && !SettingsGuard.canWrite(this)) {
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
                SettingsGuard.repair(this);
                HeartbeatScheduler.kickNow(this);
                HeartbeatScheduler.schedule(this);
                if (!OriginOsSettings.isIgnoringBatteryOptimizations(this, getPackageName())) {
                    openOwnBatterySettings();
                }
                toast(getString(R.string.service_started));
            } else {
                // Clear the flag first so the service's onDestroy also drops the alarm.
                SettingsGuard.setProtectionEnabled(this, false);
                HeartbeatScheduler.cancel(this);
                stopService(new Intent(this, GuardService.class));
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
        heartbeatIntervalBtn.setOnClickListener(v -> showHeartbeatIntervalDialog());
        findViewById(R.id.heartbeatIntervalRow).setOnClickListener(v -> showHeartbeatIntervalDialog());

        findViewById(R.id.protectNowBtn).setOnClickListener(v -> {
            SettingsGuard.saveConfig(this, keyEdit.getText().toString(), itemEdit.getText().toString());
            SettingsGuard.Result result = SettingsGuard.repair(this);
            HeartbeatScheduler.kickNow(this);
            refreshStatus(SettingsGuard.isWhitelistConfigured(this)
                    ? result.message : getString(R.string.wake_sent));
        });

        findViewById(R.id.wakeBtn).setOnClickListener(v -> {
            HeartbeatScheduler.kickNow(this);
            toast(getString(R.string.wake_sent));
            refreshStatus(getString(R.string.wake_sent));
        });

        findViewById(R.id.diagBtn).setOnClickListener(v -> openFcmDiagnostics());
        findViewById(R.id.autostartDiagBtn).setOnClickListener(v -> showAutostartDiagnostics());
        scanFcmAppsBtn.setOnClickListener(v -> toggleFcmAppsList());
        findViewById(R.id.openAutostartBtn).setOnClickListener(v -> {
            if (!OriginOsSettings.openAutoStartManager(this)) {
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
            runOnUiThread(() -> onFcmScanFinished(generation, apps));
        });
        if (!queued) {
            fcmScanInFlight = false;
            scanFcmAppsBtn.setEnabled(true);
            updateScanButton(false);
            fcmAppsStatusText.setVisibility(View.GONE);
        }
    }

    private void onFcmScanFinished(int generation, List<FcmAppScanner.AppEntry> apps) {
        if (isFinishing() || isDestroyed() || generation != fcmScanGeneration) return;

        fcmScanInFlight = false;
        scanFcmAppsBtn.setEnabled(true);
        scannedFcmApps = apps;
        fcmListExpanded = true;
        fcmAppsStatusText.setVisibility(View.VISIBLE);
        updateScanButton(true);

        if (apps.isEmpty()) {
            fcmAppsContainer.removeAllViews();
            fcmAppsContainer.setVisibility(View.GONE);
            fcmAppsStatusText.setText(R.string.no_fcm_apps);
            renderMarkedList();
            return;
        }
        renderFcmLists();
    }

    /** Rebuilds the lists from the saved marks (called when the page comes back). */
    private void refreshFcmLists() {
        if (fcmListExpanded && scannedFcmApps != null && !scannedFcmApps.isEmpty()) {
            renderFcmLists();
        } else {
            renderMarkedList();
        }
    }

    private boolean runInBackground(Runnable task) {
        try {
            backgroundExecutor.execute(task);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            return false;
        }
    }

    /**
     * Scanned apps the user has not marked yet, with only the total shown above them (no
     * per-app status: OriginOS does not let AloFCM read it), plus the separate marked list.
     */
    private void renderFcmLists() {
        if (scannedFcmApps == null || scannedFcmApps.isEmpty()) {
            renderMarkedList();
            return;
        }
        java.util.Set<String> marked = ManualAutostart.markedPackages(this);
        fcmAppsContainer.removeAllViews();
        for (FcmAppScanner.AppEntry app : scannedFcmApps) {
            if (!marked.contains(app.packageName)) addFcmAppRow(app);
        }
        fcmAppsContainer.setVisibility(fcmAppsContainer.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        fcmAppsStatusText.setText(getString(R.string.fcm_apps_total, scannedFcmApps.size(), marked.size())
                + "\n" + getString(R.string.fcm_pick_hint));
        renderMarkedList();
    }

    /** The apps the user marked as set to autostart, listed on their own. */
    private void renderMarkedList() {
        java.util.List<String[]> items = new java.util.ArrayList<>();
        for (String pkg : ManualAutostart.markedPackages(this)) {
            items.add(new String[] {labelFor(pkg), pkg});
        }
        java.util.Collections.sort(items, (a, b) -> a[0].compareToIgnoreCase(b[0]));

        fcmMarkedContainer.removeAllViews();
        boolean any = !items.isEmpty();
        fcmMarkedTitle.setVisibility(any ? View.VISIBLE : View.GONE);
        fcmMarkedContainer.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any) return;
        fcmMarkedTitle.setText(getString(R.string.marked_title, items.size()));
        for (String[] item : items) addMarkedRow(item[0], item[1]);
    }

    private String labelFor(String packageName) {
        if (scannedFcmApps != null) {
            for (FcmAppScanner.AppEntry app : scannedFcmApps) {
                if (app.packageName.equals(packageName)) return app.label;
            }
        }
        try {
            CharSequence label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0));
            return label == null ? packageName : label.toString();
        } catch (Throwable t) {
            return packageName;
        }
    }

    /** Asks whether Autostart was switched on, after the user came back from iManager. */
    private void askAutostartMark(String packageName, CharSequence label) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setMessage(R.string.autostart_mark_question)
                .setPositiveButton(R.string.mark_on, (d, w) -> applyMark(packageName, true))
                .setNegativeButton(R.string.mark_off, (d, w) -> applyMark(packageName, false))
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    private void applyMark(String packageName, boolean enabled) {
        ManualAutostart.set(this, packageName, enabled);
        renderFcmLists();
        refreshStatus(null);
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

    private LinearLayout newAppRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(8), dp(2), dp(8));
        row.setMinimumHeight(dp(52));
        row.setBackground(selectableItemBackground());
        row.setClickable(true);
        return row;
    }

    private LinearLayout appLabels(CharSequence name, String packageName) {
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextColor(AppColors.get(this, R.attr.pal_text_primary));
        label.setTextSize(14f);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView pkg = new TextView(this);
        pkg.setText(packageName);
        pkg.setTextColor(AppColors.get(this, R.attr.pal_text_secondary));
        pkg.setTextSize(12f);
        pkg.setMaxLines(1);
        pkg.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        pkg.setPadding(0, dp(2), dp(8), 0);

        labels.addView(label);
        labels.addView(pkg);
        return labels;
    }

    private void addDivider(LinearLayout container) {
        View divider = new View(this);
        divider.setBackgroundColor(AppColors.get(this, R.attr.pal_divider));
        container.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
    }

    /** An app to pick: tap opens its Autostart page, then asks to mark; long-press marks. */
    private void addFcmAppRow(FcmAppScanner.AppEntry app) {
        LinearLayout row = newAppRow();
        row.setContentDescription(app.label);
        row.setOnClickListener(v -> {
            if (OriginOsSettings.openAppPermissionEditor(this, app.packageName)) {
                pendingMarkPackage = app.packageName;
                pendingMarkLabel = app.label;
            } else {
                toast(getString(R.string.app_settings_unavailable));
            }
        });
        row.setOnLongClickListener(v -> {
            applyMark(app.packageName, true);
            toast(getString(R.string.app_marked_toast));
            return true;
        });
        row.addView(appLabels(app.label, app.packageName), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextColor(AppColors.get(this, R.attr.pal_text_secondary));
        chevron.setTextSize(20f);
        chevron.setPadding(dp(8), 0, dp(4), dp(2));
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron);

        fcmAppsContainer.addView(row);
        addDivider(fcmAppsContainer);
    }

    /** A marked app: tap reopens its page; the button removes the mark. */
    private void addMarkedRow(String name, String packageName) {
        LinearLayout row = newAppRow();
        row.setContentDescription(name);
        row.setOnClickListener(v -> {
            if (!OriginOsSettings.openAppPermissionEditor(this, packageName)) {
                toast(getString(R.string.app_settings_unavailable));
            }
        });

        TextView check = new TextView(this);
        check.setText("✓");
        check.setTextColor(AppColors.get(this, R.attr.pal_badge_text_green));
        check.setTextSize(16f);
        check.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        check.setPadding(0, 0, dp(10), 0);
        check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(check);

        row.addView(appLabels(name, packageName), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView remove = new TextView(this);
        remove.setText(R.string.marked_remove);
        remove.setTextColor(AppColors.get(this, R.attr.pal_badge_text_red));
        remove.setTextSize(12.5f);
        remove.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        remove.setGravity(Gravity.CENTER);
        remove.setMinHeight(dp(36));
        remove.setPadding(dp(12), dp(6), dp(12), dp(6));
        remove.setBackground(makePill(R.attr.pal_status_value_red_bg, dp(12)));
        remove.setOnClickListener(v -> {
            ManualAutostart.remove(this, packageName);
            renderFcmLists();
            refreshStatus(null);
        });
        row.addView(remove);

        fcmMarkedContainer.addView(row);
        addDivider(fcmMarkedContainer);
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

    private static final int REQUEST_POST_NOTIFICATIONS = 4260;

    private void ensurePersistentNotificationAccess() {
        boolean createdNow = GuardService.ensureNotificationChannel(this);
        // Android 13+: notifications are a runtime permission for apps targeting 33+.
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            notificationAccessPending = false;
            uiHandler.removeCallbacks(notificationAccessFollowUp);
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_POST_NOTIFICATIONS);
            return;
        }
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

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_POST_NOTIFICATIONS) return;
        boolean granted = grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            if (SettingsGuard.isProtectionEnabled(this)) startProtectionService();
        } else if (Build.VERSION.SDK_INT >= 33 &&
                !shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
            // Denied permanently (or the dialog was suppressed): only Settings can fix it.
            openNotificationSettings();
        }
        refreshStatus(null);
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
        boolean canWrite = SettingsGuard.canWrite(this);
        boolean enabled = SettingsGuard.isProtectionEnabled(this);
        boolean notification = SettingsGuard.usePersistentNotification(this);
        boolean whitelist = SettingsGuard.isWhitelistConfigured(this);
        String current = SettingsGuard.read(this);
        boolean present = !whitelist || SettingsGuard.hasRequiredItem(this, current);

        suppressSwitchCallbacks = true;
        protectionSwitch.setChecked(enabled);
        notificationSwitch.setChecked(notification);
        suppressSwitchCallbacks = false;

        // On OriginOS, AloFCM itself must be allowed to run in the background, or the
        // heartbeat alarm and the service are frozen with everything else: it needs the
        // Doze exemption / "Allow high background power use" and Autostart. An unreadable
        // Autostart state is not treated as off.
        String self = getPackageName();
        boolean selfBattery = OriginOsSettings.isIgnoringBatteryOptimizations(this, self);
        AutostartStatusReader.Status selfRead = AutostartStatusReader.check(this, self);
        AutostartStatusReader.Status selfAutostart =
                selfRead != AutostartStatusReader.Status.UNKNOWN
                        || ManualAutostart.get(this, self) != null
                        ? ManualAutostart.effective(this, self, selfRead) : selfRead;
        boolean selfAutostartOff = selfAutostart == AutostartStatusReader.Status.DISABLED ||
                selfAutostart == AutostartStatusReader.Status.PARTIAL;
        boolean writeOk = !whitelist || canWrite;
        boolean selfOk = selfBattery && !selfAutostartOff;

        if (enabled && writeOk && present && selfOk) {
            setHeadline(R.string.status_protected, R.attr.pal_green, R.drawable.ic_status_protected);
        } else if (writeOk && present && selfOk) {
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

        boolean notificationsOk = GuardService.canShowPersistentNotification(this);
        int interval = HeartbeatScheduler.intervalMinutes(this);

        // Setup steps in the order they should be done (not sorted by severity).
        java.util.List<StatusItem> items = new java.util.ArrayList<>();
        items.add(new StatusItem(getString(R.string.status_label_notifications),
                getString(notificationsOk ? R.string.status_value_granted : R.string.status_value_not_granted),
                notificationsOk ? TONE_GOOD : TONE_BAD,
                notificationsOk ? null : v -> ensurePersistentNotificationAccess()));
        items.add(new StatusItem(getString(R.string.status_label_self_whitelist),
                getString(selfBattery ? R.string.status_value_allowed : R.string.status_value_restricted),
                selfBattery ? TONE_GOOD : TONE_BAD,
                selfBattery ? null : v -> openOwnBatterySettings()));
        items.add(new StatusItem(getString(R.string.status_label_self_autostart),
                getString(autostartStatusText(selfAutostart)), autostartTone,
                autostartTone == TONE_GOOD ? null : v -> openOwnAutostartSettings()));
        items.add(new StatusItem(getString(R.string.status_label_protection),
                getString(enabled ? R.string.status_value_enabled : R.string.status_value_disabled),
                enabled ? TONE_GOOD : TONE_WARN,
                enabled ? null : v -> protectionSwitch.setChecked(true)));
        items.add(new StatusItem(getString(R.string.status_label_heartbeat),
                enabled ? getString(R.string.heartbeat_every, interval)
                        : getString(R.string.status_value_disabled),
                enabled ? TONE_GOOD : TONE_WARN,
                v -> showHeartbeatIntervalDialog()));
        items.add(new StatusItem(getString(R.string.status_label_background_mode),
                getString(visibleForeground ? R.string.status_value_foreground : R.string.status_value_quiet),
                visibleForeground ? TONE_GOOD : TONE_WARN,
                visibleForeground || notification ? null : v -> notificationSwitch.setChecked(true)));
        for (int i = 0; i < statusTiles.length && i < items.size(); i++) {
            StatusItem item = items.get(i);
            bindStatusRow(statusTiles[i], i + 1, item.label, item.value, item.tone, item.action);
        }
        if (heartbeatIntervalBtn != null) {
            heartbeatIntervalBtn.setText(getString(R.string.heartbeat_every, interval));
        }
        currentValueText.setText(whitelist ? buildWhitelistValue(current) : buildHeartbeatValue());
        permissionBtn.setVisibility(writeOk ? View.GONE : View.VISIBLE);
    }

    /** Shown instead of the whitelist value when no key is configured (the OriginOS default). */
    private CharSequence buildHeartbeatValue() {
        long last = HeartbeatScheduler.lastKick(this);
        String when = last <= 0L ? getString(R.string.heartbeat_never)
                : android.text.format.DateUtils.getRelativeTimeSpanString(
                        last, System.currentTimeMillis(),
                        android.text.format.DateUtils.MINUTE_IN_MILLIS).toString();
        return getString(R.string.heartbeat_summary, HeartbeatScheduler.intervalMinutes(this), when);
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
        final CharSequence label;
        final CharSequence value;
        final int tone;
        final View.OnClickListener action;

        StatusItem(CharSequence label, CharSequence value, int tone, View.OnClickListener action) {
            this.label = label;
            this.value = value;
            this.tone = tone;
            this.action = action;
        }
    }

    /** Lets the user pick how often the FCM heartbeat is sent. */
    private void showHeartbeatIntervalDialog() {
        int[] choices = HeartbeatScheduler.INTERVAL_CHOICES;
        int current = HeartbeatScheduler.intervalMinutes(this);
        String[] labels = new String[choices.length];
        int checked = -1;
        for (int i = 0; i < choices.length; i++) {
            labels[i] = getString(R.string.heartbeat_every, choices[i]);
            if (choices[i] == current) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.heartbeat_interval)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    if (which < 0 || which >= choices.length || choices[which] == current) return;
                    HeartbeatScheduler.setIntervalMinutes(this, choices[which]);
                    if (SettingsGuard.isProtectionEnabled(this)) {
                        HeartbeatScheduler.kickNow(this);
                        HeartbeatScheduler.schedule(this);
                    }
                    refreshStatus(null);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
    private void bindStatusRow(View row, int step, CharSequence labelText, CharSequence valueText,
            int tone, View.OnClickListener action) {
        TextView stepView = row.findViewById(R.id.statusRowStep);
        TextView label = row.findViewById(R.id.statusRowLabel);
        TextView value = row.findViewById(R.id.statusRowValue);
        TextView chevron = row.findViewById(R.id.statusRowChevron);
        int tintRes = tone == TONE_GOOD ? R.attr.pal_status_value_green_bg
                : tone == TONE_WARN ? R.attr.pal_status_value_yellow_bg
                : tone == TONE_NEUTRAL ? R.attr.pal_status_value_neutral_bg : R.attr.pal_status_value_red_bg;
        int valueColorRes = tone == TONE_GOOD ? R.attr.pal_badge_text_green
                : tone == TONE_WARN ? R.attr.pal_badge_text_yellow
                : tone == TONE_NEUTRAL ? R.attr.pal_badge_text_neutral : R.attr.pal_badge_text_red;

        label.setText(labelText);
        value.setText(valueText);
        value.setTextColor(AppColors.get(this, valueColorRes));
        stepView.setText(String.valueOf(step));
        stepView.setTextColor(AppColors.get(this, valueColorRes));
        GradientDrawable badge = new GradientDrawable();
        badge.setShape(GradientDrawable.OVAL);
        badge.setColor(AppColors.get(this, R.attr.pal_surface));
        stepView.setBackground(badge);
        row.setContentDescription(step + ". " + labelText + ": " + valueText);
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
        if (!OriginOsSettings.openBatterySettings(this, getPackageName(), getString(R.string.app_name))) {
            toast(getString(R.string.app_settings_unavailable));
        }
    }

    private void openOwnAutostartSettings() {
        if (AutostartStatusReader.check(this, getPackageName()) == AutostartStatusReader.Status.UNKNOWN) {
            pendingMarkPackage = getPackageName();
            pendingMarkLabel = getString(R.string.app_name);
        }
        if (!OriginOsSettings.openAppPermissionEditor(this, getPackageName()) &&
                !OriginOsSettings.openAutoStartManager(this)) {
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

    /** Collects the Autostart provider report off the UI thread and shows it, copyable. */
    private void showAutostartDiagnostics() {
        final Context appContext = getApplicationContext();
        toast(getString(R.string.scanning_fcm_apps));
        runInBackground(() -> {
            String report = AutostartStatusReader.diagnose(appContext);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                TextView text = new TextView(this);
                text.setText(report);
                text.setTextIsSelectable(true);
                text.setTextSize(11f);
                text.setTypeface(Typeface.MONOSPACE);
                text.setTextColor(AppColors.get(this, R.attr.pal_text_primary));
                text.setPadding(dp(20), dp(8), dp(20), dp(8));
                android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                scroll.addView(text);
                new AlertDialog.Builder(this)
                        .setTitle(R.string.autostart_diag)
                        .setView(scroll)
                        .setPositiveButton(R.string.copy_report, (d, w) -> {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("AloFCM", report));
                                toast(getString(R.string.report_copied));
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
