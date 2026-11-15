package com.reed.fcmguard;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

public class GuardService extends Service {
    // v2 intentionally uses a new channel id. Android does not allow an app to raise
    // an existing channel from IMPORTANCE_MIN to IMPORTANCE_LOW after creation.
    private static final String CHANNEL_ID = "fcm_guard_persistent_v2";
    private static final int NOTIFICATION_ID = 426;
    private static final long FALLBACK_INTERVAL_MS = 30L * 60L * 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ContentObserver observer;
    private String observedKey;
    private String observedRequiredItem;
    private boolean foreground;
    /** null until the first onStartCommand(); then the notification mode last applied. */
    private Boolean appliedPersistentMode;
    private boolean fallbackScheduled;
    private BroadcastReceiver wakeReceiver;
    /** Screen-on kicks are rate-limited so unlocking repeatedly does not spam GMS. */
    private static final long SCREEN_ON_KICK_GAP_MS = 2L * 60L * 1000L;
    private static final long NETWORK_KICK_DELAY_MS = 3000L;

    private final Runnable networkKick = new Runnable() {
        @Override public void run() {
            HeartbeatScheduler.kickNow(GuardService.this);
        }
    };

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
        SettingsGuard.rememberIfUseful(this);
        registerWakeReceiver();
        // Observer, foreground state and the first check are set up in onStartCommand(),
        // which always follows onCreate() for this started-only service.
    }

    /**
     * Idempotent: MainActivity calls startService() on every resume, so a repeated start
     * only re-applies what actually changed instead of re-registering the observer and
     * running a full repair each time.
     */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        SettingsGuard.setProtectionEnabled(this, true);
        applyExecutionMode();
        boolean configChanged = registerObserverIfNeeded();
        // OriginOS core: keep the periodic FCM heartbeat armed while protection is on.
        HeartbeatScheduler.schedule(this);
        if (configChanged || !fallbackScheduled) {
            handler.removeCallbacks(fallbackCheck);
            handler.post(fallbackCheck);
            fallbackScheduled = true;
        }
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

    /**
     * Watches the configured key. Returns true when the key or the required item changed
     * since the last call (including the very first call), i.e. when an immediate check
     * is worthwhile.
     */
    private boolean registerObserverIfNeeded() {
        String key = SettingsGuard.getConfiguredKey(this);
        String requiredItem = SettingsGuard.getConfiguredRequiredItem(this);
        boolean keyChanged = !key.equals(observedKey) || (observer == null && !key.isEmpty());
        boolean changed = keyChanged || !requiredItem.equals(observedRequiredItem);
        observedRequiredItem = requiredItem;
        if (!keyChanged) return changed;

        ContentResolver resolver = getContentResolver();
        try {
            if (observer != null) resolver.unregisterContentObserver(observer);
        } catch (Throwable ignored) {}

        observer = null;
        observedKey = key;
        if (key.isEmpty()) return true; // No whitelist key on this build: heartbeat only.

        observer = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                handler.removeCallbacks(repairDebounced);
                handler.postDelayed(repairDebounced, 400L);
            }
        };
        resolver.registerContentObserver(Settings.System.getUriFor(key), false, observer);
        return true;
    }

    private void applyExecutionMode() {
        boolean persistent = SettingsGuard.usePersistentNotification(this);
        boolean modeChanged = appliedPersistentMode == null || appliedPersistentMode != persistent;
        appliedPersistentMode = persistent;

        if (persistent) {
            if (modeChanged) ensureNotificationChannel(this);
            // Always re-enter the foreground: a startForegroundService() call must be
            // answered with startForeground(), and it re-posts the notification after the
            // user re-enables notifications in system settings.
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_active)));
            foreground = true;
        } else if (modeChanged) {
            if (foreground) stopForeground(true);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
            foreground = false;
        }
    }

    /**
     * Creates the visible-but-silent foreground-service channel. Returns true only
     * when this call created the channel for the first time.
     */
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

    /** True when Android will actually place the foreground notification in the shade. */
    public static boolean canShowPersistentNotification(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nm.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
            return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    /**
     * Network changes and screen-on are the moments vivo most often leaves the GMS socket
     * dead; a heartbeat right then makes FCM reconnect without waiting for the next alarm.
     */
    private void registerWakeReceiver() {
        wakeReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                    long since = System.currentTimeMillis() - HeartbeatScheduler.lastKick(context);
                    if (since >= SCREEN_ON_KICK_GAP_MS) HeartbeatScheduler.kickNow(context);
                } else if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action) && isConnected(context)) {
                    handler.removeCallbacks(networkKick);
                    handler.postDelayed(networkKick, NETWORK_KICK_DELAY_MS);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        try {
            registerReceiver(wakeReceiver, filter);
        } catch (Throwable ignored) {
            wakeReceiver = null;
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean isConnected(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Restarts the service when protection is on (used by the heartbeat alarm). */
    public static void startIfNeeded(Context context) {
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

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            if (wakeReceiver != null) unregisterReceiver(wakeReceiver);
        } catch (Throwable ignored) {}
        wakeReceiver = null;
        // Keep the alarm while protection is on: its receiver restarts this service if
        // OriginOS killed it. Only a user-initiated stop cancels it.
        if (!SettingsGuard.isProtectionEnabled(this)) HeartbeatScheduler.cancel(this);
        fallbackScheduled = false;
        try {
            if (observer != null) getContentResolver().unregisterContentObserver(observer);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

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
