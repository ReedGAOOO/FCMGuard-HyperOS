package com.reed.fcmguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        boolean enabled = context.getSharedPreferences("guard_state", Context.MODE_PRIVATE)
                .getBoolean("enabled", false);
        if (!enabled) return;

        Intent service = new Intent(context, GuardService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Throwable ignored) {
            // HyperOS may block boot starts unless Autostart is allowed.
        }
    }
}
