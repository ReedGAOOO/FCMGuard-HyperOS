package com.reed.fcmguard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
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
    private Button scanFcmAppsBtn;
    private Switch protectionSwitch;
    private Switch notificationSwitch;
    private Button permissionBtn;
    private RadioGroup appearanceGroup;
    private boolean suppressSwitchCallbacks = false;
    private boolean suppressAppearanceCallbacks = false;
    private boolean notificationAccessPending = false;
    private boolean fcmListExpanded = false;

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
        if (SettingsGuard.isProtectionEnabled(this) &&
                SettingsGuard.usePersistentNotification(this) &&
                GuardService.canShowPersistentNotification(this)) {
            startProtectionService();
        }
        refreshStatus(null);
    }

    @Override protected void onPause() {
        stopLanguageButtonAnimation();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            configureFullEdgeToEdge();
            if (notificationAccessPending) {
                languageAnimationHandler.removeCallbacks(notificationAccessFollowUp);
                languageAnimationHandler.postDelayed(notificationAccessFollowUp, 250L);
            }
        }
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
        scanFcmAppsBtn = findViewById(R.id.scanFcmAppsBtn);
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
                languageAnimationHandler.removeCallbacks(notificationAccessFollowUp);
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
        scanFcmAppsBtn.setOnClickListener(v -> toggleFcmAppsList());
        findViewById(R.id.openAutostartBtn).setOnClickListener(v -> {
            if (!HyperOsSettings.openAutoStartManager(this)) {
                toast(getString(R.string.autostart_manager_unavailable));
            }
        });
    }

    private void toggleFcmAppsList() {
        if (fcmListExpanded) {
            fcmListExpanded = false;
            fcmAppsContainer.setVisibility(View.GONE);
            fcmAppsStatusText.setVisibility(View.GONE);
            updateScanButton(false);
            return;
        }
        scanFcmApps();
    }

    private void scanFcmApps() {
        List<FcmAppScanner.AppEntry> apps = FcmAppScanner.scan(this);
        fcmAppsContainer.removeAllViews();
        fcmAppsContainer.setVisibility(View.VISIBLE);
        fcmAppsStatusText.setVisibility(View.VISIBLE);

        if (apps.isEmpty()) {
            fcmAppsStatusText.setText(R.string.no_fcm_apps);
            fcmListExpanded = false;
            updateScanButton(false);
            return;
        }

        fcmAppsStatusText.setText(getString(R.string.fcm_apps_found, apps.size()));
        for (FcmAppScanner.AppEntry app : apps) {
            addFcmAppRow(app);
        }
        fcmListExpanded = true;
        updateScanButton(true);
    }

    private void updateScanButton(boolean expanded) {
        if (scanFcmAppsBtn == null) return;
        if (expanded) {
            scanFcmAppsBtn.setText(R.string.collapse_fcm_apps);
            scanFcmAppsBtn.setTextColor(getResources().getColor(R.color.blue));
            scanFcmAppsBtn.setBackgroundResource(R.drawable.secondary_button_bg);
        } else {
            scanFcmAppsBtn.setText(R.string.scan_fcm_apps);
            scanFcmAppsBtn.setTextColor(Color.WHITE);
            scanFcmAppsBtn.setBackgroundResource(R.drawable.primary_button_bg);
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
            if (!HyperOsSettings.openAutoStartManager(this)) {
                toast(getString(R.string.autostart_manager_unavailable));
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
        languageAnimationHandler.removeCallbacks(notificationAccessFollowUp);
        if (createdNow) {
            languageAnimationHandler.postDelayed(notificationAccessFollowUp, 900L);
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

        SpannableStringBuilder status = new SpannableStringBuilder();
        if (firstLine != null && !firstLine.trim().isEmpty()) {
            status.append("✓ ").append(firstLine).append("\n");
        }
        appendStatusValueLine(
                status,
                canWrite ? getString(R.string.status_granted) : getString(R.string.status_not_granted),
                canWrite ? R.color.status_value_green_bg : R.color.status_value_red_bg
        );
        status.append('\n');
        appendStatusValueLine(
                status,
                enabled ? getString(R.string.status_enabled) : getString(R.string.status_disabled),
                enabled ? R.color.status_value_green_bg : R.color.status_value_yellow_bg
        );
        status.append('\n');
        appendStatusValueLine(
                status,
                present ? getString(R.string.present_yes) : getString(R.string.present_no),
                present ? R.color.status_value_green_bg : R.color.status_value_red_bg
        );
        status.append('\n');
        boolean visibleForeground = notification && enabled && GuardService.canShowPersistentNotification(this);
        appendStatusValueLine(
                status,
                visibleForeground
                        ? getString(R.string.notification_mode_foreground)
                        : getString(R.string.notification_mode_quiet),
                visibleForeground ? R.color.status_value_green_bg : R.color.status_value_yellow_bg
        );
        statusText.setText(status);
        currentValueText.setText(buildWhitelistValue(current));
        permissionBtn.setVisibility(canWrite ? View.GONE : View.VISIBLE);
    }

    private void appendStatusValueLine(SpannableStringBuilder out, String text, int backgroundColorRes) {
        int lineStart = out.length();
        out.append(text);
        int lineEnd = out.length();

        int separator = Math.max(text.lastIndexOf(':'), text.lastIndexOf('：'));
        int valueOffset = separator >= 0 ? separator + 1 : 0;
        while (valueOffset < text.length() && Character.isWhitespace(text.charAt(valueOffset))) {
            valueOffset++;
        }
        int valueStart = lineStart + valueOffset;
        if (valueStart >= lineEnd) return;

        out.setSpan(
                new BackgroundColorSpan(getResources().getColor(backgroundColorRes)),
                valueStart,
                lineEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        out.setSpan(
                new ForegroundColorSpan(getResources().getColor(R.color.text_primary)),
                valueStart,
                lineEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        out.setSpan(
                new StyleSpan(Typeface.BOLD),
                valueStart,
                lineEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        );
    }

    private CharSequence buildWhitelistValue(String current) {
        if (current == null || current.trim().isEmpty()) {
            SpannableStringBuilder missing = new SpannableStringBuilder(getString(R.string.missing_current));
            missing.setSpan(
                    new BackgroundColorSpan(getResources().getColor(R.color.status_value_red_bg)),
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
                        new BackgroundColorSpan(getResources().getColor(colorRes)),
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
        if ("com.google.android.gms".equals(packageName)) return R.color.status_value_blue_bg;
        if (getPackageName().equals(packageName)) return R.color.status_value_purple_bg;
        return 0;
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
