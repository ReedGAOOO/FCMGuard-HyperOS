package com.reed.fcmguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class GuardService extends Service {
    // v2 intentionally uses a new channel id. Android does not allow an app to raise
    // an existing channel from IMPORTANCE_MIN to IMPORTANCE_LOW after creation.
    private static final String CHANNEL_ID = "fcm_guard_persistent_v2";
    private static final int NOTIFICATION_ID = 426;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WhitelistWatcher watcher;
    private boolean foreground;

    @Override public void onCreate() {
        super.onCreate();
        SettingsGuard.rememberIfUseful(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        SettingsGuard.setProtectionEnabled(this, true);
        ensureNotificationChannel(this);
        // Always enter foreground first: startForegroundService() callers expect
        // it, and a mode flip between scheduling and delivery must not crash.
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_active)));
        foreground = true;

        if (!SmartGuardController.shouldWatchResidently(this)) {
            SmartGuardController.scheduleJob(this);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (watcher == null) {
            watcher = new WhitelistWatcher(this, handler, new WhitelistWatcher.Listener() {
                @Override public void onRepair(boolean changed) {
                    if (changed) {
                        SmartGuardController.recordBreak(GuardService.this);
                        refreshNotification(getString(R.string.notification_repaired));
                    }
                }
            });
        }
        watcher.register();
        // Repair once per start: covers overwrites that happened while the
        // process was dead and no observer was registered.
        watcher.repairNow();
        return START_STICKY;
    }

    /** True when Android will actually place the foreground notification in the shade. */
    public static boolean canShowPersistentNotification(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nm.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
            return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    @Override public void onDestroy() {
        if (watcher != null) {
            watcher.unregister();
            watcher = null;
        }
        foreground = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** Creates the visible-but-silent foreground-service channel. */
    public static boolean ensureNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;

        boolean created = nm.getNotificationChannel(CHANNEL_ID) == null;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(context.getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setSound(null, null);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(channel);
        return created;
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
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }

        return builder
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }
}
