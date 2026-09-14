package com.reed.fcmguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!SettingsGuard.isProtectionEnabled(context)) return;

        Intent service = new Intent(context, GuardService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    SettingsGuard.usePersistentNotification(context)) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Throwable ignored) {}
    }
}
