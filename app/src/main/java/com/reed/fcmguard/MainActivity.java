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

        // Deliberately use normal Android window fitting here.  FCM Guard must target
        // SDK 22 so it can write Xiaomi's private Settings.System key.  On HyperOS,
        // combining that legacy target with custom edge-to-edge/system-bar flags can
        // trigger a large black compatibility surface at the bottom of the screen.
        // The first working build did not touch those flags, so keep SystemUI in charge.
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

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
