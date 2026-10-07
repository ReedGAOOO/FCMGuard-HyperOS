package com.reed.fcmguard;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Decides how the protection runs: a resident foreground watcher (vigilant) or
 * system-scheduled checks with no resident process (quiet). Auto mode starts
 * vigilant, relaxes after 24 quiet hours, and returns to vigilant on the first
 * detected overwrite so notifications are never left to a scheduler once the
 * ROM has proven hostile.
 */
public final class SmartGuardController {
    public static final String MODE_AUTO = "auto";
    public static final String MODE_ALWAYS = "always";
    public static final String MODE_SAVING = "saving";

    private static final String KEY_MODE = "guard_mode";
    private static final String KEY_VIGILANT = "guard_vigilant";
    private static final String KEY_LAST_BREAK = "guard_last_break_at";
    private static final long DOWNGRADE_AFTER_MS = 24L * 60L * 60L * 1000L;
    private static final int JOB_ID = 4261;

    private SmartGuardController() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(SettingsGuard.PREFS, Context.MODE_PRIVATE);
    }

    /** One-time bridge from the pre-v1.6.0 persistent-notification switch. */
    public static void migrate(Context context) {
        SharedPreferences p = prefs(context);
        if (p.contains(KEY_MODE)) return;

        boolean legacyNotification = p.getBoolean(SettingsGuard.PREF_PERSISTENT_NOTIFICATION, true);
        SharedPreferences.Editor e = p.edit();
        e.putString(KEY_MODE, MODE_AUTO);
        e.putBoolean(KEY_VIGILANT, legacyNotification);
        if (!p.contains(KEY_LAST_BREAK)) {
            e.putLong(KEY_LAST_BREAK, System.currentTimeMillis());
        }
        e.apply();
    }

    public static String getMode(Context context) {
        String mode = prefs(context).getString(KEY_MODE, MODE_AUTO);
        return MODE_ALWAYS.equals(mode) || MODE_SAVING.equals(mode) ? mode : MODE_AUTO;
    }

    public static void setMode(Context context, String mode) {
        prefs(context).edit().putString(KEY_MODE, mode).apply();
        apply(context);
    }

    /** True when GuardService should run as a resident foreground watcher. */
    public static boolean shouldWatchResidently(Context context) {
        if (!SettingsGuard.isProtectionEnabled(context)) return false;
        String mode = getMode(context);
        if (MODE_ALWAYS.equals(mode)) return true;
        if (MODE_SAVING.equals(mode)) return false;
        return prefs(context).getBoolean(KEY_VIGILANT, true);
    }

    /** Records a real overwrite (a repair that actually wrote the whitelist). */
    public static void recordBreak(Context context) {
        SharedPreferences p = prefs(context);
        long now = System.currentTimeMillis();
        boolean wasVigilant = p.getBoolean(KEY_VIGILANT, true);
        p.edit().putLong(KEY_LAST_BREAK, now).putBoolean(KEY_VIGILANT, true).apply();
        if (!wasVigilant && SettingsGuard.isProtectionEnabled(context)) {
            cancelJob(context);
            startGuardService(context);
        }
    }

    /** Auto mode only: relax after a full quiet day, never after less. */
    public static void checkDowngrade(Context context) {
        SharedPreferences p = prefs(context);
        if (!MODE_AUTO.equals(getMode(context))) return;
        if (!p.getBoolean(KEY_VIGILANT, true)) return;
        long last = p.getLong(KEY_LAST_BREAK, 0L);
        if (last > 0 && System.currentTimeMillis() - last >= DOWNGRADE_AFTER_MS) {
            p.edit().putBoolean(KEY_VIGILANT, false).apply();
            apply(context);
        }
    }

    /** Reconciles running components with mode + protection state. */
    public static void apply(Context context) {
        if (!SettingsGuard.isProtectionEnabled(context)) {
            context.stopService(new Intent(context, GuardService.class));
            cancelJob(context);
            return;
        }
        if (shouldWatchResidently(context)) {
            cancelJob(context);
            startGuardService(context);
        } else {
            context.stopService(new Intent(context, GuardService.class));
            scheduleJob(context);
        }
    }

    public static void startGuardService(Context context) {
        Intent service = new Intent(context, GuardService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                GuardService.ensureNotificationChannel(context);
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Throwable ignored) {}
    }

    public static void scheduleJob(Context context) {
        if (!SettingsGuard.isProtectionEnabled(context)) return;
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            long period = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    ? 15L * 60L * 1000L
                    : 60L * 60L * 1000L;
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, GuardJobService.class))
                    .setPeriodic(period)
                    .setPersisted(true)
                    .build();
            js.schedule(job);
        } catch (Throwable ignored) {}
    }

    public static void cancelJob(Context context) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
        } catch (Throwable ignored) {}
    }
}
