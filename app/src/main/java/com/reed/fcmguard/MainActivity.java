package com.reed.fcmguard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    private static final String[] LANGUAGE_CODES = {
            "system", "en", "zh-CN", "zh-TW", "fr", "ja", "ko", "es", "pt", "de", "ru"
    };
    private static final String[] LANGUAGE_BUTTON_LABELS = {
            "Language", "语言", "語言", "Langue", "言語", "언어", "Idioma", "Idioma", "Sprache", "Язык"
    };
    private static final long LANGUAGE_LABEL_INTERVAL_MS = 2200L;

    private EditText keyEdit;
    private EditText itemEdit;
    private TextView statusHeadline;
    private TextView statusText;
    private TextView currentValueText;
    private TextView fcmAppsStatusText;
    private LinearLayout fcmAppsContainer;
    private Button languageButton;
    private Switch protectionSwitch;
    private Switch notificationSwitch;
    private Button permissionBtn;
    private RadioGroup appearanceGroup;
    private boolean suppressSwitchCallbacks = false;
    private boolean suppressAppearanceCallbacks = false;

    private final Handler languageAnimationHandler = new Handler(Looper.getMainLooper());
    private int languageLabelIndex = 0;
    private boolean languageAnimationRunning = false;
    private final Runnable languageLabelTicker = new Runnable() {
        @Override public void run() {
            if (!languageAnimationRunning || languageButton == null) return;
            animateToNextLanguageLabel();
            languageAnimationHandler.postDelayed(this, LANGUAGE_LABEL_INTERVAL_MS);
        }
    };

    @Override protected void attachBaseContext(Context newBase) {
        Context localized = LocaleHelper.apply(newBase);
        super.attachBaseContext(ThemeHelper.apply(localized));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LocaleHelper.migrateLegacyPreference(this);
        configureFullEdgeToEdge();
        setContentView(R.layout.activity_main);
        applySystemBarInsets();
        bindViews();
        loadConfigIntoFields();
        setupLanguagePicker();
        setupAppearance();
        setupSwitches();
        bindActions();
        refreshStatus(null);
    }

    @Override protected void onResume() {
        super.onResume();
        configureFullEdgeToEdge();
        startLanguageButtonAnimation();
        refreshStatus(null);
    }

    @Override protected void onPause() {
        stopLanguageButtonAnimation();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) configureFullEdgeToEdge();
    }

    private void bindViews() {
        keyEdit = findViewById(R.id.keyEdit);
        itemEdit = findViewById(R.id.itemEdit);
        statusHeadline = findViewById(R.id.statusHeadline);
        statusText = findViewById(R.id.statusText);
        currentValueText = findViewById(R.id.currentValueText);
        fcmAppsStatusText = findViewById(R.id.fcmAppsStatusText);
        fcmAppsContainer = findViewById(R.id.fcmAppsContainer);
        languageButton = findViewById(R.id.languageButton);
        protectionSwitch = findViewById(R.id.protectionSwitch);
        notificationSwitch = findViewById(R.id.notificationSwitch);
        permissionBtn = findViewById(R.id.permissionBtn);
        appearanceGroup = findViewById(R.id.appearanceGroup);
    }

    private void loadConfigIntoFields() {
        keyEdit.setText(SettingsGuard.getConfiguredKey(this));
        itemEdit.setText(SettingsGuard.getConfiguredRequiredItem(this));
    }

    private void setupLanguagePicker() {
        languageButton.setText(LANGUAGE_BUTTON_LABELS[0]);
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

    private void startLanguageButtonAnimation() {
        if (languageButton == null || languageAnimationRunning) return;
        languageAnimationRunning = true;
        languageAnimationHandler.removeCallbacks(languageLabelTicker);
        languageAnimationHandler.postDelayed(languageLabelTicker, LANGUAGE_LABEL_INTERVAL_MS);
    }

    private void stopLanguageButtonAnimation() {
        languageAnimationRunning = false;
        languageAnimationHandler.removeCallbacks(languageLabelTicker);
        if (languageButton != null) {
            languageButton.animate().cancel();
            languageButton.setAlpha(1f);
            languageButton.setTranslationY(0f);
        }
    }

    private void animateToNextLanguageLabel() {
        if (languageButton == null) return;
        languageLabelIndex = (languageLabelIndex + 1) % LANGUAGE_BUTTON_LABELS.length;
        languageButton.animate().cancel();
        languageButton.animate()
                .alpha(0f)
                .translationY(-dp(3))
                .setDuration(140L)
                .withEndAction(() -> {
                    if (!languageAnimationRunning || languageButton == null) return;
                    languageButton.setText(LANGUAGE_BUTTON_LABELS[languageLabelIndex]);
                    languageButton.setAlpha(0f);
                    languageButton.setTranslationY(dp(3));
                    languageButton.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(180L)
                            .start();
                })
                .start();
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
                    if (which >= 0 && which < LANGUAGE_CODES.length) {
                        LocaleHelper.setLanguage(this, LANGUAGE_CODES[which]);
                    }
                    d.dismiss();
                    if (Build.VERSION.SDK_INT < 33) recreate();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
    }

    private void setupAppearance() {
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
                recreate();
            }
        });
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

        findViewById(R.id.repairBtn).setOnClickListener(v -> {
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
        findViewById(R.id.scanFcmAppsBtn).setOnClickListener(v -> scanFcmApps());
        findViewById(R.id.openAutostartBtn).setOnClickListener(v -> {
            if (!HyperOsSettings.openAutoStartManager(this)) {
                toast(getString(R.string.autostart_manager_unavailable));
            }
        });
    }

    private void scanFcmApps() {
        List<FcmAppScanner.AppEntry> apps = FcmAppScanner.scan(this);
        fcmAppsContainer.removeAllViews();

        if (apps.isEmpty()) {
            fcmAppsStatusText.setText(R.string.no_fcm_apps);
            return;
        }

        fcmAppsStatusText.setText(getString(R.string.fcm_apps_found, apps.size()));
        for (FcmAppScanner.AppEntry app : apps) {
            addFcmAppRow(app);
        }
    }

    private void addFcmAppRow(FcmAppScanner.AppEntry app) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);

        TextView label = new TextView(this);
        label.setText(app.label);
        label.setTextColor(getResources().getColor(R.color.text_primary));
        label.setTextSize(14f);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView packageName = new TextView(this);
        packageName.setText(app.packageName);
        packageName.setTextColor(getResources().getColor(R.color.text_secondary));
        packageName.setTextSize(10.5f);
        packageName.setMaxLines(1);
        packageName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        packageName.setPadding(0, dp(2), dp(8), 0);

        labels.addView(label);
        labels.addView(packageName);
        row.addView(labels, labelsParams);

        Button settingsButton = new Button(this);
        settingsButton.setText(R.string.app_settings);
        settingsButton.setAllCaps(false);
        settingsButton.setTextSize(12f);
        settingsButton.setTextColor(getResources().getColor(R.color.blue));
        settingsButton.setBackground(getResources().getDrawable(R.drawable.secondary_button_bg));
        settingsButton.setMinWidth(0);
        settingsButton.setMinHeight(0);
        settingsButton.setPadding(dp(10), 0, dp(10), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settingsButton.setStateListAnimator(null);
            settingsButton.setElevation(0f);
        }
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(88), dp(42));
        settingsButton.setOnClickListener(v -> {
            if (!HyperOsSettings.openAppPermissionEditor(this, app.packageName)) {
                toast(getString(R.string.app_settings_unavailable));
            }
        });
        row.addView(settingsButton, buttonParams);

        fcmAppsContainer.addView(row);

        View divider = new View(this);
        divider.setBackgroundColor(getResources().getColor(R.color.divider));
        fcmAppsContainer.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private void startProtectionService() {
        Intent service = new Intent(this, GuardService.class);
        try {
            boolean persistent = SettingsGuard.usePersistentNotification(this);
            if (persistent) requestNotificationPermissionIfNeeded();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && persistent) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Throwable t) {
            toast(t.getClass().getSimpleName());
        }
    }

    private void openWriteSettings() {
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + getPackageName())
        );
        startActivity(intent);
    }

    private void openFcmDiagnostics() {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(
                    "com.google.android.gms",
                    "com.google.android.gms.gtalkservice.diagnostics.GTalkServiceDiagnostics"
            );
            startActivity(intent);
        } catch (Throwable t) {
            toast(getString(R.string.diagnostics_unavailable));
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

        if (enabled && canWrite && present) {
            statusHeadline.setText(R.string.status_protected);
            statusHeadline.setTextColor(getResources().getColor(R.color.green));
        } else if (canWrite && present) {
            statusHeadline.setText(R.string.status_ready);
            statusHeadline.setTextColor(getResources().getColor(R.color.blue));
        } else {
            statusHeadline.setText(R.string.status_attention);
            statusHeadline.setTextColor(getResources().getColor(R.color.red));
        }

        StringBuilder sb = new StringBuilder();
        if (firstLine != null && !firstLine.trim().isEmpty()) {
            sb.append("✓ ").append(firstLine).append("\n");
        }
        sb.append(canWrite ? getString(R.string.status_granted) : getString(R.string.status_not_granted)).append("\n");
        sb.append(enabled ? getString(R.string.status_enabled) : getString(R.string.status_disabled)).append("\n");
        sb.append(present ? getString(R.string.present_yes) : getString(R.string.present_no)).append("\n");
        sb.append(notification ? getString(R.string.notification_mode_foreground) : getString(R.string.notification_mode_quiet));
        statusText.setText(sb.toString());
        currentValueText.setText(current == null ? getString(R.string.missing_current) : current);
        permissionBtn.setVisibility(canWrite ? View.GONE : View.VISIBLE);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    @SuppressWarnings("deprecation")
    private void configureFullEdgeToEdge() {
        Window window = getWindow();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.clearFlags(
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS |
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
            );
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
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
        window.getDecorView().setSystemUiVisibility(flags);
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
