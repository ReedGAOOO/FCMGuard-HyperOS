package com.reed.fcmguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** Periodic app-UID Settings.Global sync for packages with both Xiaomi Autostart AppOps allowed. */
public final class AurogonProtection {
    private static final String PREFS = "aurogon_protection";
    private static final String MANAGED = "managed_packages";
    private static final String AUTO_SYNC = "auto_sync_enabled";
    private static final Object SETTINGS_LOCK = new Object();
    private AurogonProtection() {}

    public static boolean hasSecureSettingsPermission(Context context) {
        return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public static boolean isAutoSyncEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO_SYNC, false);
    }

    public static void setAutoSyncEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(AUTO_SYNC, enabled).apply();
    }

    public static AurogonProtectionEngine.Result sync(Context context, List<String> enabledPackages) {
        synchronized (SETTINGS_LOCK) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<String> previous = readPackages(prefs.getString(MANAGED, "[]"));
        AurogonProtectionEngine engine = new AurogonProtectionEngine(new AurogonProtectionEngine.Port() {
            @Override public String read() { return Settings.Global.getString(context.getContentResolver(), AurogonConfig.KEY); }
            @Override public boolean write(String value) { return Settings.Global.putString(context.getContentResolver(), AurogonConfig.KEY, value); }
        });
        AurogonProtectionEngine.Result result = engine.sync(previous, enabledPackages);
        String error = result.error;
        if (result.verified) {
            List<String> currentManaged = AurogonConfig.managedPackages(result.finalValue);
            prefs.edit().putString(MANAGED, toJson(currentManaged)).putLong("last_sync", System.currentTimeMillis())
                    .putInt("package_count", enabledPackages == null ? 0 : enabledPackages.size())
                    .putBoolean("success", true).putString("error", "").commit();
        } else {
            prefs.edit().putLong("last_sync", System.currentTimeMillis()).putBoolean("success", false)
                    .putString("error", error).commit();
        }
        return result;
        }
    }

    /** The manual button and the 30-minute fallback share this app-UID scan and sync path. */
    public static SyncOutcome syncCurrentAutostartApps(Context context) {
        if (!hasSecureSettingsPermission(context)) {
            String error = context.getString(R.string.aurogon_status_permission);
            failure(context, error);
            return new SyncOutcome(false, 0, error);
        }
        AutostartStatusReader.ScanResult scan = AutostartStatusReader.findEnabledFcmPackages(context);
        if (!scan.readSucceeded) {
            String error = scan.error;
            failure(context, error);
            return new SyncOutcome(false, 0, error);
        }
        try {
            List<String> eligiblePackages = AurogonConfig.includeManagedUnknownPackages(
                    scan.enabledPackages, scan.unknownPackages, cachedPackages(context));
            AurogonProtectionEngine.Result result = sync(context, eligiblePackages);
            if (!result.verified) {
                failure(context, result.error);
                return new SyncOutcome(false, 0, result.error);
            }
            notice(context, "Autostart list refreshed");
            return new SyncOutcome(true, eligiblePackages.size(), "");
        } catch (Throwable error) {
            String detail = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            failure(context, detail);
            return new SyncOutcome(false, 0, detail);
        }
    }

    public static final class SyncOutcome {
        public final boolean verified;
        public final int packageCount;
        public final String error;
        SyncOutcome(boolean verified, int packageCount, String error) {
            this.verified = verified;
            this.packageCount = packageCount;
            this.error = error;
        }
    }

    public static ClearResult clearSettings(Context context) {
        synchronized (SETTINGS_LOCK) {
            ClearResult result = new ClearResult();
            if (!hasSecureSettingsPermission(context)) {
                result.error = "WRITE_SECURE_SETTINGS not granted";
                return result;
            }
            try {
                boolean wrote = Settings.Global.putString(context.getContentResolver(), AurogonConfig.KEY, null);
                String readBack = Settings.Global.getString(context.getContentResolver(), AurogonConfig.KEY);
                result.verified = wrote && readBack == null;
                if (result.verified) {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
                } else {
                    result.error = "putString returned " + wrote + "; read-back=" + String.valueOf(readBack);
                }
            } catch (Throwable error) {
                result.error = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            }
            return result;
        }
    }

    public static final class ClearResult {
        public boolean verified;
        public String error = "";
    }

    public static void failure(Context context, String reason) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("success", false).putString("error", reason).commit();
    }

    public static void notice(Context context, String message) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("notice", message).commit();
    }

    public static void clearSyncHistory(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    }

    public static List<String> cachedPackages(Context context) {
        return readPackages(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MANAGED, "[]"));
    }

    public static String summary(Context context) {
        if (!isAutoSyncEnabled(context)) return context.getString(R.string.aurogon_status_disabled);
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!hasSecureSettingsPermission(context)) return context.getString(R.string.aurogon_status_permission);
        if (p.getLong("last_sync", 0L) == 0L) return context.getString(R.string.aurogon_status_waiting);
        if (p.getBoolean("success", false)) {
            return context.getString(R.string.aurogon_status_synced, p.getInt("package_count", 0));
        }
        return context.getString(R.string.aurogon_status_failed);
    }

    private static List<String> readPackages(String json) {
        List<String> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        } catch (Exception ignored) {}
        return result;
    }
    private static String toJson(List<String> packages) {
        JSONArray array = new JSONArray();
        for (String value : packages) array.put(value);
        return array.toString();
    }
}
