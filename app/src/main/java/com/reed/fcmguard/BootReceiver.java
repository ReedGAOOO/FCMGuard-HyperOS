package com.reed.fcmguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        boolean coreEnabled = SettingsGuard.isProtectionEnabled(context);
        boolean aurogonEnabled = AurogonProtection.isAutoSyncEnabled(context)
                && AurogonProtection.hasSecureSettingsPermission(context);
        if (!coreEnabled && !aurogonEnabled) return;

        Intent service = new Intent(context, GuardService.class);
        service.putExtra(GuardService.EXTRA_ENABLE_CORE_PROTECTION, coreEnabled);
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
