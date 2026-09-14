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
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status;
    private TextView currentValue;
    private EditText keyInput;
    private EditText tokenInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }

        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private ScrollView buildUi() {
        int pad = dp(24);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackgroundColor(Color.rgb(250, 250, 250));

        TextView title = new TextView(this);
        title.setText("FCM Guard");
        title.setTextSize(30);
        title.setTextColor(Color.rgb(32, 33, 36));
        title.setPadding(0, dp(12), 0, dp(4));
        box.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("HyperOS system-setting watchdog");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.rgb(95, 99, 104));
        subtitle.setPadding(0, 0, 0, dp(18));
        box.addView(subtitle);

        keyInput = new EditText(this);
        keyInput.setHint("System setting key");
        keyInput.setSingleLine(true);
        keyInput.setText(SettingsGuard.getKey(this));
        box.addView(keyInput, fullWidth());

        tokenInput = new EditText(this);
        tokenInput.setHint("Required comma-list item");
        tokenInput.setSingleLine(true);
        tokenInput.setText(SettingsGuard.getToken(this));
        box.addView(tokenInput, fullWidth());

        Button save = new Button(this);
        save.setText("Save configuration");
        save.setOnClickListener(v -> {
            SettingsGuard.configure(
                    this,
                    keyInput.getText().toString(),
                    tokenInput.getText().toString()
            );
            refreshStatus();
        });
        box.addView(save, fullWidth());

        Button permission = new Button(this);
        permission.setText("Grant Modify system settings");
        permission.setOnClickListener(v -> openWriteSettings());
        box.addView(permission, fullWidth());

        Button repair = new Button(this);
        repair.setText("Repair now");
        repair.setOnClickListener(v -> {
            SettingsGuard.configure(
                    this,
                    keyInput.getText().toString(),
                    tokenInput.getText().toString()
            );
            SettingsGuard.Result result = SettingsGuard.repair(this);
            refreshStatus();
            status.setText(result.success ? "✓ " + result.message : "⚠ " + result.message);
        });
        box.addView(repair, fullWidth());

        Button reconnect = new Button(this);
        reconnect.setText("Wake FCM now");
        reconnect.setOnClickListener(v -> {
            boolean sent = FcmReconnect.kick(this);
            refreshStatus();
            status.setText(sent ? "✓ FCM reconnect request sent" : "⚠ Could not send reconnect request");
        });
        box.addView(reconnect, fullWidth());

        Button start = new Button(this);
        start.setText("Start automatic protection");
        start.setOnClickListener(v -> {
            SettingsGuard.configure(
                    this,
                    keyInput.getText().toString(),
                    tokenInput.getText().toString()
            );
            if (!Settings.System.canWrite(this)) {
                openWriteSettings();
                return;
            }
            if (SettingsGuard.getKey(this).isEmpty() || SettingsGuard.getToken(this).isEmpty()) {
                refreshStatus();
                return;
            }
            Intent service = new Intent(this, GuardService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            getSharedPreferences("guard_state", MODE_PRIVATE)
                    .edit().putBoolean("enabled", true).apply();
            SettingsGuard.repair(this);
            FcmReconnect.kick(this);
            refreshStatus();
        });
        box.addView(start, fullWidth());

        Button stop = new Button(this);
        stop.setText("Stop automatic protection");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, GuardService.class));
            getSharedPreferences("guard_state", MODE_PRIVATE)
                    .edit().putBoolean("enabled", false).apply();
            refreshStatus();
        });
        box.addView(stop, fullWidth());

        status = new TextView(this);
        status.setTextSize(17);
        status.setTextColor(Color.rgb(32, 33, 36));
        status.setPadding(0, dp(18), 0, dp(8));
        box.addView(status, fullWidth());

        currentValue = new TextView(this);
        currentValue.setTextSize(13);
        currentValue.setTextColor(Color.rgb(95, 99, 104));
        currentValue.setTextIsSelectable(true);
        currentValue.setPadding(0, 0, 0, dp(20));
        box.addView(currentValue, fullWidth());

        TextView note = new TextView(this);
        note.setText(
                "FCM Guard preserves all existing comma-separated items and only adds the configured required item when it is missing. " +
                "When it repairs the whitelist, it also sends a best-effort heartbeat/reconnect request to Google Play services. " +
                "The foreground watchdog listens for System-setting changes and also checks every 30 seconds.\n\n" +
                "No root, Shizuku, persistent ADB, Accessibility, VPN, overlay, or device-admin permission is used."
        );
        note.setTextSize(14);
        note.setTextColor(Color.rgb(60, 64, 67));
        box.addView(note, fullWidth());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        return scroll;
    }

    private void openWriteSettings() {
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + getPackageName())
        );
        startActivity(intent);
    }

    private void refreshStatus() {
        if (status == null || currentValue == null) return;
        boolean canWrite = Settings.System.canWrite(this);
        boolean enabled = getSharedPreferences("guard_state", MODE_PRIVATE)
                .getBoolean("enabled", false);
        String current = SettingsGuard.read(this);

        status.setText(
                "Modify settings: " + (canWrite ? "granted" : "not granted") +
                "\nAutomatic protection: " + (enabled ? "enabled" : "disabled") +
                "\nRequired item present: " + (SettingsGuard.containsToken(this, current) ? "yes" : "no")
        );

        String key = SettingsGuard.getKey(this);
        currentValue.setText(
                "Current value" + (key.isEmpty() ? "" : " of " + key) + ":\n" +
                (current == null ? "(missing / not configured)" : current)
        );
    }

    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        p.setMargins(0, dp(5), 0, dp(5));
        return p;
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}
