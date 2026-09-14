package com.reed.fcmguard;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private EditText keyEdit;
    private EditText itemEdit;
    private TextView statusText;
    private TextView currentValueText;
    private Spinner languageSpinner;
    private boolean initializingSpinner = true;

    @Override protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.apply(newBase));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();
        setContentView(R.layout.activity_main);
        bindViews();
        requestNotificationPermissionIfNeeded();
        loadConfigIntoFields();
        setupLanguageSpinner();
        bindActions();
        refreshStatus(null);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus(null);
    }

    private void bindViews() {
        keyEdit = findViewById(R.id.keyEdit);
        itemEdit = findViewById(R.id.itemEdit);
        statusText = findViewById(R.id.statusText);
        currentValueText = findViewById(R.id.currentValueText);
        languageSpinner = findViewById(R.id.languageSpinner);
    }

    private void loadConfigIntoFields() {
        keyEdit.setText(SettingsGuard.getConfiguredKey(this));
        itemEdit.setText(SettingsGuard.getConfiguredRequiredItem(this));
    }

    private void setupLanguageSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, R.array.language_entries, android.R.layout.simple_spinner_item);
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

    private void bindActions() {
        ((Button)findViewById(R.id.saveBtn)).setOnClickListener(v -> {
            SettingsGuard.saveConfig(this, keyEdit.getText().toString(), itemEdit.getText().toString());
            loadConfigIntoFields();
            toast(getString(R.string.saved));
            refreshStatus(getString(R.string.saved));
        });

        ((Button)findViewById(R.id.permissionBtn)).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        ((Button)findViewById(R.id.repairBtn)).setOnClickListener(v -> {
            SettingsGuard.saveConfig(this, keyEdit.getText().toString(), itemEdit.getText().toString());
            SettingsGuard.Result result = SettingsGuard.repair(this);
            if (result.success) FcmReconnect.kick(this);
            refreshStatus(result.message);
        });

        ((Button)findViewById(R.id.startBtn)).setOnClickListener(v -> {
            SettingsGuard.saveConfig(this, keyEdit.getText().toString(), itemEdit.getText().toString());
            if (!Settings.System.canWrite(this)) {
                toast(getString(R.string.permission_missing));
                return;
            }
            Intent service = new Intent(this, GuardService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
            getSharedPreferences("guard_state", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
            toast(getString(R.string.service_started));
            refreshStatus(getString(R.string.service_started));
        });

        ((Button)findViewById(R.id.stopBtn)).setOnClickListener(v -> {
            stopService(new Intent(this, GuardService.class));
            getSharedPreferences("guard_state", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            toast(getString(R.string.service_stopped));
            refreshStatus(getString(R.string.service_stopped));
        });

        ((Button)findViewById(R.id.wakeBtn)).setOnClickListener(v -> {
            FcmReconnect.kick(this);
            toast(getString(R.string.wake_sent));
            refreshStatus(getString(R.string.wake_sent));
        });

        ((Button)findViewById(R.id.diagBtn)).setOnClickListener(v -> openFcmDiagnostics());
    }

    private void openFcmDiagnostics() {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName("com.google.android.gms", "com.google.android.gms.gtalkservice.diagnostics.GTalkServiceDiagnostics");
            startActivity(intent);
        } catch (Throwable t) {
            toast(getString(R.string.diagnostics_unavailable));
        }
    }

    private void refreshStatus(String firstLine) {
        boolean canWrite = Settings.System.canWrite(this);
        boolean enabled = getSharedPreferences("guard_state", MODE_PRIVATE).getBoolean("enabled", false);
        String current = SettingsGuard.read(this);
        boolean present = SettingsGuard.hasRequiredItem(this, current);

        StringBuilder sb = new StringBuilder();
        if (firstLine != null && !firstLine.trim().isEmpty()) sb.append("✓ ").append(firstLine).append("\n\n");
        sb.append(canWrite ? getString(R.string.status_granted) : getString(R.string.status_not_granted)).append("\n");
        sb.append(enabled ? getString(R.string.status_enabled) : getString(R.string.status_disabled)).append("\n");
        sb.append(present ? getString(R.string.present_yes) : getString(R.string.present_no));
        statusText.setText(sb.toString());
        currentValueText.setText(current == null ? getString(R.string.missing_current) : current);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    /**
     * HyperOS 3 can render a large black legacy-navigation surface when an app targeting
     * an old SDK combines transparent bars with LAYOUT_HIDE_NAVIGATION.  We intentionally
     * keep normal window fitting here and simply tint both system bars to the app surface.
     * This keeps the gesture pill visually integrated without exposing the black decor
     * background, and it also prevents the title from sliding under the status bar.
     */
    private void configureSystemBars() {
        Window w = getWindow();
        final int bg = getResources().getColor(R.color.bg);
        if (Build.VERSION.SDK_INT >= 21) {
            w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(bg);
            w.setNavigationBarColor(bg);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }

        View decor = w.getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(flags);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
