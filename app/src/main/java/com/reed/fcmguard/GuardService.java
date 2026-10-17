package com.reed.fcmguard;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
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
    private boolean foreground;
    private ContentObserver nightObserver;
    private NightFcmProtection night;
    private HandlerThread repairThread;
    private Handler repairHandler;
    private boolean destroyed;
    private final Runnable nightRepair = () -> night.maintain();
    private final Runnable nightChanged = () -> {
        if (!destroyed) reconcileNightObserver();
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
        applyExecutionMode();
        registerObserver();
        repairThread = new HandlerThread("MilletGuard");
        repairThread.start();
        repairHandler = new Handler(repairThread.getLooper());
        night = NightFcmProtection.get(this);
        night.listen(nightChanged);
        night.whenReady(() -> handler.post(() -> {
            if (destroyed) return;
            reconcileNightObserver();
            night.maintain();
        }));
        handler.post(fallbackCheck);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!SettingsGuard.isProtectionEnabled(this) && night.isInitialized() && !night.isEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        registerObserver();
        applyExecutionMode();
        handler.removeCallbacks(fallbackCheck);
        handler.post(fallbackCheck);
        return START_STICKY;
    }

    private void repair(boolean notifyFailure) {
        night.maintain();
        repairHandler.post(() -> {
            if (!SettingsGuard.isProtectionEnabled(this)) return;
            try {
                SettingsGuard.Result result = SettingsGuard.repair(this);
                if (result.changed) FcmReconnect.kick(this);
                handler.post(() -> {
                    if (destroyed) return;
                    if (result.changed) refreshNotification(getString(R.string.notification_repaired));
                    else if (!result.success && notifyFailure) refreshNotification(result.message);
                });
            } catch (Throwable error) { night.serviceFailure(error); }
        });
    }

    private void registerObserver() {
        ContentResolver resolver = getContentResolver();
        try {
            if (observer != null) resolver.unregisterContentObserver(observer);
        } catch (Throwable ignored) {}

        observer = null;
        if (!SettingsGuard.isProtectionEnabled(this)) return;
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

    private void reconcileNightObserver() {
        if (night.isEnabled() && nightObserver == null) {
            ContentObserver candidate = new ContentObserver(handler) {
                @Override public void onChange(boolean selfChange, Uri uri) {
                    handler.removeCallbacks(nightRepair);
                    handler.postDelayed(nightRepair, 400L);
                }
            };
            try {
                getContentResolver().registerContentObserver(
                        Settings.System.getUriFor(NightProtectionEngine.KEY), false, candidate);
                nightObserver = candidate;
                night.observer(true);
            } catch (Throwable e) { night.serviceFailure(e); }
        } else if (!night.isEnabled() && nightObserver != null) {
            try { getContentResolver().unregisterContentObserver(nightObserver); }
            catch (Throwable e) { night.serviceFailure(e); }
            nightObserver = null;
            handler.removeCallbacks(nightRepair);
            night.observer(false);
        }
        if (night.isInitialized() && !night.isEnabled() && !SettingsGuard.isProtectionEnabled(this)) stopSelf();
    }

    private void applyExecutionMode() {
        if (SettingsGuard.usePersistentNotification(this)) {
            ensureNotificationChannel(this);
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_active)));
            foreground = true;
        } else {
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

    @Override public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (night != null) {
            night.unlisten(nightChanged);
            night.observer(false);
        }
        if (repairThread != null) repairThread.quitSafely();
        try { if (nightObserver != null) getContentResolver().unregisterContentObserver(nightObserver); }
        catch (Throwable ignored) {}
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
