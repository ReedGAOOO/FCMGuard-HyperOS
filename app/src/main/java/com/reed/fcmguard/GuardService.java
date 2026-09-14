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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class GuardService extends Service {
    private static final String CHANNEL_ID = "fcm_guard";
    private static final int NOTIFICATION_ID = 426;
    private static final long FALLBACK_INTERVAL_MS = 30_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ContentObserver observer;

    private final Runnable fallbackCheck = new Runnable() {
        @Override public void run() {
            SettingsGuard.Result result = SettingsGuard.repair(GuardService.this);
            if (result.success) FcmReconnect.kick(GuardService.this);
            refreshNotification(result.message);
            handler.postDelayed(this, FALLBACK_INTERVAL_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Watching HyperOS settings"));
        ContentResolver resolver = getContentResolver();
        observer = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                handler.removeCallbacks(repairDebounced);
                handler.postDelayed(repairDebounced, 250);
            }
        };
        resolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, observer);
        SettingsGuard.rememberIfUseful(this);
        handler.post(fallbackCheck);
    }

    private final Runnable repairDebounced = new Runnable() {
        @Override public void run() {
            SettingsGuard.Result result = SettingsGuard.repair(GuardService.this);
            if (result.success) FcmReconnect.kick(GuardService.this);
            refreshNotification(result.message);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        getSharedPreferences("guard_state", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        handler.removeCallbacks(fallbackCheck);
        handler.post(fallbackCheck);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try { if (observer != null) getContentResolver().unregisterContentObserver(observer); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "FCM Guard", NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private void refreshNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }
}
