package com.reed.fcmguard;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private EditText keyEdit;
    private EditText itemEdit;
    private TextView statusHeadline;
    private TextView statusText;
    private TextView currentValueText;
    private Spinner languageSpinner;
    private Switch protectionSwitch;
    private Switch notificationSwitch;
    private Button permissionBtn;
    private boolean initializingSpinner = true;
    private boolean suppressSwitchCallbacks = false;

    @Override protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.apply(newBase));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureFullEdgeToEdge();
        setContentView(R.layout.activity_main);
        applySystemBarInsets();
        bindViews();
        loadConfigIntoFields();
        setupLanguageSpinner();
        setupSwitches();
        bindActions();
        refreshStatus(null);
    }

    @Override protected void onResume() {
        super.onResume();
        configureFullEdgeToEdge();
        refreshStatus(null);
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
        languageSpinner = findViewById(R.id.languageSpinner);
        protectionSwitch = findViewById(R.id.protectionSwitch);
        notificationSwitch = findViewById(R.id.notificationSwitch);
        permissionBtn = findViewById(R.id.permissionBtn);
    }

    private void loadConfigIntoFields() {
        keyEdit.setText(SettingsGuard.getConfiguredKey(this));
        itemEdit.setText(SettingsGuard.getConfiguredRequiredItem(this));
    }

    private void setupLanguageSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this,
                R.array.language_entries,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(adapter);

        String lang = LocaleHelper.getLanguage(this);
        int index = 0;
        if ("en".equals(lang)) index = 1;
        else if ("zh-CN".equals(lang)) index = 2;
        languageSpinner.setSelection(index, false);
        initializingSpinner = false;
        languageSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (initializingSpinner) return;
                String code = position == 1 ? "en" : (position == 2 ? "zh-CN" : "system");
                if (!code.equals(LocaleHelper.getLanguage(MainActivity.this))) {
                    LocaleHelper.setLanguage(MainActivity.this, code);
                    recreate();
                }
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
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
        if (firstLine != null && !firstLine.trim().isEmpty()) sb.append("✓ ").append(firstLine).append("\n");
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

    /**
     * Full edge-to-edge presentation: both status and gesture-navigation surfaces are
     * transparent, while content receives explicit safe insets. This gives the same
     * visual model used by modern media apps without letting text sit under the
     * status icons or display cutout.
     */
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

        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    /**
     * Keep the app background behind both system bars, but pad the actual content by
     * the real status/cutout and gesture-navigation insets. Baseline design padding is
     * preserved and a few extra dp provide visual breathing room at both ends.
     */
    @SuppressWarnings("deprecation")
    private void applySystemBarInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;

        final View scroll = findViewById(R.id.scroll);
        final View content = findViewById(R.id.contentRoot);
        final int baseLeft = content.getPaddingLeft();
        final int baseTop = content.getPaddingTop();
        final int baseRight = content.getPaddingRight();
        final int baseBottom = content.getPaddingBottom();
        final int extraTop = dp(4);
        final int extraBottom = dp(8);

        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = Math.max(
                    insets.getSystemWindowInsetTop(),
                    insets.getStableInsetTop()
            );
            int bottom = Math.max(
                    insets.getSystemWindowInsetBottom(),
                    insets.getStableInsetBottom()
            );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    top = Math.max(top, cutout.getSafeInsetTop());
                    bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                }
            }

            content.setPadding(
                    baseLeft,
                    baseTop + top + extraTop,
                    baseRight,
                    baseBottom + bottom + extraBottom
            );
            return insets;
        });
        scroll.requestApplyInsets();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
