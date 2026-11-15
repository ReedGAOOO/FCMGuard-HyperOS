package com.alofcm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

/**
 * The OriginOS core: a periodic FCM heartbeat.
 *
 * HyperOS lets an app put Google Play services on a private no-restrict list; OriginOS has
 * no such public switch. What goes wrong on vivo instead is that the long-lived
 * Google Play services (MCS) connection is silently dropped when the phone idles or the
 * network changes, and FCM only notices after its own long heartbeat interval (often
 * 15-28 minutes), so messages arrive late or in a burst. Sending the GTALK/MCS heartbeat
 * intents on a short interval makes GMS check the socket and reconnect promptly.
 *
 * The alarm is allowed while idle, so it keeps working in Doze, and its receiver also
 * restarts {@link GuardService} if OriginOS killed it.
 */
public final class HeartbeatScheduler extends BroadcastReceiver {
    public static final String PREF_INTERVAL_MIN = "heartbeat_interval_min";
    public static final String PREF_LAST_KICK = "heartbeat_last_kick";
    public static final int DEFAULT_INTERVAL_MIN = 10;
    /** Android throttles allow-while-idle alarms to roughly once per 9 minutes in Doze. */
    public static final int MIN_INTERVAL_MIN = 5;
    public static final int MAX_INTERVAL_MIN = 60;
    private static final int REQUEST_CODE = 427;

    @Override public void onReceive(Context context, Intent intent) {
        if (!SettingsGuard.isProtectionEnabled(context)) {
            cancel(context);
            return;
        }
        kickNow(context);
        schedule(context);
        GuardService.startIfNeeded(context);
    }

    /** Sends the heartbeat now and records the time. */
    public static void kickNow(Context context) {
        if (FcmReconnect.kick(context)) {
            prefs(context).edit().putLong(PREF_LAST_KICK, System.currentTimeMillis()).apply();
        }
    }

    public static long lastKick(Context context) {
        return prefs(context).getLong(PREF_LAST_KICK, 0L);
    }

    public static int intervalMinutes(Context context) {
        int v = prefs(context).getInt(PREF_INTERVAL_MIN, DEFAULT_INTERVAL_MIN);
        return Math.max(MIN_INTERVAL_MIN, Math.min(MAX_INTERVAL_MIN, v));
    }

    /** (Re)arms the next heartbeat one interval from now. */
    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long at = SystemClock.elapsedRealtime() + intervalMinutes(context) * 60_000L;
        PendingIntent pi = pendingIntent(context);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Throwable t) {
            // Exact alarms refused: fall back to an inexact one rather than none.
            try { am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi); } catch (Throwable ignored) {}
        }
    }

    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pendingIntent(context));
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, HeartbeatScheduler.class);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(SettingsGuard.PREFS, Context.MODE_PRIVATE);
    }
}
