package com.reed.fcmguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

public class GuardService extends Service {
    private static final String CHANNEL_ID = "fcm_guard";
    private static final int NOTIFICATION_ID = 426;
    private static final long FALLBACK_INTERVAL_MS = 30L * 60L * 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ContentObserver observer;
    private boolean foreground;

    private final Runnable fallbackCheck = new Runnable() {
        @Override public void run() {
            repair(false);
            handler.postDelayed(this, FALLBACK_INTERVAL_MS);
        }
    };

    private final Runnable repairDebounced = new Runnable() {
        @Override public void run() {
            repair(true);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        applyExecutionMode();
        registerObserver();
        SettingsGuard.rememberIfUseful(this);
        handler.post(fallbackCheck);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        SettingsGuard.setProtectionEnabled(this, true);
        registerObserver();
        applyExecutionMode();
        handler.removeCallbacks(fallbackCheck);
        handler.post(fallbackCheck);
        return START_STICKY;
    }

    private void repair(boolean notifyFailure) {
        SettingsGuard.Result result = SettingsGuard.repair(this);
        if (result.changed) {
            FcmReconnect.kick(this);
            if (foreground) refreshNotification(getString(R.string.notification_repaired));
        } else if (!result.success && notifyFailure && foreground) {
            refreshNotification(result.message);
        }
    }

    private void registerObserver() {
        ContentResolver resolver = getContentResolver();
        try {
            if (observer != null) resolver.unregisterContentObserver(observer);
        } catch (Throwable ignored) {}

        observer = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                handler.removeCallbacks(repairDebounced);
                handler.postDelayed(repairDebounced, 400L);
            }
        };
        resolver.registerContentObserver(
                Settings.System.getUriFor(SettingsGuard.getConfiguredKey(this)),
                false,
                observer
        );
    }

    private void applyExecutionMode() {
        if (SettingsGuard.usePersistentNotification(this)) {
            createChannel();
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_active)));
            foreground = true;
        } else {
            if (foreground) stopForeground(true);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
            foreground = false;
        }
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            if (observer != null) getContentResolver().unregisterContentObserver(observer);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_MIN
        );
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setSound(null, null);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private void refreshNotification(String text) {
        if (!foreground) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        }

        return builder
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }
}
