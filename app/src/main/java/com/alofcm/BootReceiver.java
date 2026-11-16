package com.alofcm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!SettingsGuard.isProtectionEnabled(context)) return;
        GuardService.startIfNeeded(context);
        HeartbeatScheduler.schedule(context);
    }
}
