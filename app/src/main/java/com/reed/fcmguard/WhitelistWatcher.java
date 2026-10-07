package com.reed.fcmguard;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Watches the whitelist key, debounces bursts, and backs off when the ROM
 * rewrites the value in a storm so repeated repairs stay cheap.
 */
public final class WhitelistWatcher {
    public interface Listener {
        void onRepair(boolean changed);
    }

    private static final long STORM_WINDOW_MS = 60L * 1000L;
    private static final long[] STORM_DELAYS_MS = {
            400L, 2000L, 5000L, 10000L, 20000L, 40000L, 60000L
    };

    private final Context context;
    private final Handler handler;
    private final Listener listener;
    private ContentObserver observer;
    private boolean registered;

    private int stormDepth = 0;
    private long lastChangedRepair = 0L;

    public WhitelistWatcher(Context context, Handler handler, Listener listener) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.listener = listener;
    }

    public boolean register() {
        if (registered) return true;
        try {
            Uri uri = Settings.System.getUriFor(SettingsGuard.getConfiguredKey(context));
            observer = new ContentObserver(handler) {
                @Override public void onChange(boolean selfChange, Uri u) {
                    scheduleRepair();
                }
            };
            context.getContentResolver().registerContentObserver(uri, false, observer);
            registered = true;
            return true;
        } catch (Throwable t) {
            observer = null;
            return false;
        }
    }

    public void unregister() {
        if (!registered) return;
        try {
            context.getContentResolver().unregisterContentObserver(observer);
        } catch (Throwable ignored) {}
        handler.removeCallbacks(repairRunnable);
        registered = false;
    }

    public void repairNow() {
        runRepair();
    }

    private final Runnable repairRunnable = new Runnable() {
        @Override public void run() {
            runRepair();
        }
    };

    private void scheduleRepair() {
        long now = SystemClock.elapsedRealtime();
        if (lastChangedRepair != 0 && now - lastChangedRepair > STORM_WINDOW_MS) {
            stormDepth = 0;
        }
        long delay = STORM_DELAYS_MS[Math.min(stormDepth, STORM_DELAYS_MS.length - 1)];
        handler.removeCallbacks(repairRunnable);
        handler.postDelayed(repairRunnable, delay);
    }

    private void runRepair() {
        SettingsGuard.Result result = SettingsGuard.repair(context);
        if (result.changed) {
            lastChangedRepair = SystemClock.elapsedRealtime();
            stormDepth = Math.min(stormDepth + 1, STORM_DELAYS_MS.length - 1);
            FcmReconnect.kick(context);
        }
        listener.onRepair(result.changed);
    }
}
