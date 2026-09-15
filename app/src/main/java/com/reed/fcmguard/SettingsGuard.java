package com.reed.fcmguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.util.LinkedHashSet;
import java.util.Set;

public final class SettingsGuard {
    public static final String PREFS = "guard_state";
    public static final String PREF_ENABLED = "enabled";
    public static final String PREF_PERSISTENT_NOTIFICATION = "persistent_notification";
    public static final String PREF_HIDE_FROM_RECENTS = "hide_from_recents";
    private static final String LAST_GOOD_PREFIX = "last_good_";

    private SettingsGuard() {}

    public static String getConfiguredKey(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("settings_key", context.getString(R.string.default_key));
    }

    public static String getConfiguredRequiredItem(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("required_item", context.getString(R.string.default_required_item));
    }

    public static boolean isProtectionEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false);
    }

    public static boolean usePersistentNotification(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_PERSISTENT_NOTIFICATION, true);
    }

    public static void setProtectionEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    public static boolean hideFromRecents(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_HIDE_FROM_RECENTS, false);
    }

    public static void setHideFromRecents(Context context, boolean hidden) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_HIDE_FROM_RECENTS, hidden).apply();
    }

    public static void setPersistentNotification(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_PERSISTENT_NOTIFICATION, enabled).apply();
    }

    public static void saveConfig(Context context, String key, String requiredItem) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("settings_key", safe(key, context.getString(R.string.default_key)))
                .putString("required_item", safe(requiredItem, context.getString(R.string.default_required_item)))
                .apply();
    }

    private static String safe(String s, String fallback) {
        if (s == null) return fallback;
        String t = s.trim();
        return t.isEmpty() ? fallback : t;
    }

    public static String read(Context context) {
        return Settings.System.getString(context.getContentResolver(), getConfiguredKey(context));
    }

    public static boolean hasRequiredItem(Context context, String value) {
        String required = getConfiguredRequiredItem(context);
        if (required == null || required.trim().isEmpty() || value == null || value.trim().isEmpty()) return false;
        for (String part : value.split(",")) {
            if (required.equals(part.trim())) return true;
        }
        return false;
    }

    /**
     * Repairs only when necessary.  A no-op repair performs no Settings write and, unless
     * the actual package list changed, no SharedPreferences write either.  This is
     * important because the common background path should be practically idle.
     */
    public static synchronized Result repair(Context context) {
        if (!Settings.System.canWrite(context)) {
            return new Result(false, false, read(context), context.getString(R.string.permission_missing));
        }

        String key = getConfiguredKey(context);
        String required = getConfiguredRequiredItem(context);
        String current = read(context);

        if (hasRequiredItem(context, current)) {
            rememberIfUseful(context, current);
            return new Result(true, false, current, context.getString(R.string.already_protected));
        }

        LinkedHashSet<String> packages = parse(current);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (packages.isEmpty()) {
            packages.addAll(parse(prefs.getString(LAST_GOOD_PREFIX + key, null)));
        }
        if (packages.isEmpty()) {
            packages.add("com.tencent.mm");
            packages.add("com.android.vending");
        }
        if (required != null && !required.trim().isEmpty()) {
            packages.add(required.trim());
        }

        String repaired = join(packages);
        try {
            boolean ok = Settings.System.putString(context.getContentResolver(), key, repaired);
            if (ok) {
                saveLastGoodIfChanged(prefs, key, repaired);
                return new Result(true, true, repaired, context.getString(R.string.repaired));
            }
            return new Result(false, false, current, context.getString(R.string.write_rejected));
        } catch (Throwable t) {
            return new Result(false, false, current,
                    context.getString(R.string.write_failed, t.getClass().getSimpleName()));
        }
    }

    public static synchronized void rememberIfUseful(Context context) {
        rememberIfUseful(context, read(context));
    }

    private static void rememberIfUseful(Context context, String current) {
        if (current == null || current.trim().isEmpty()) return;
        String key = getConfiguredKey(context);
        LinkedHashSet<String> packages = parse(current);
        if (packages.isEmpty()) return;
        String normalized = join(packages);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        saveLastGoodIfChanged(prefs, key, normalized);
    }

    private static void saveLastGoodIfChanged(SharedPreferences prefs, String key, String value) {
        String prefKey = LAST_GOOD_PREFIX + key;
        String old = prefs.getString(prefKey, null);
        if (!value.equals(old)) {
            prefs.edit().putString(prefKey, value).apply();
        }
    }

    private static LinkedHashSet<String> parse(String value) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (value == null) return out;
        for (String part : value.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private static String join(Set<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) sb.append(',');
            sb.append(item);
        }
        return sb.toString();
    }

    public static final class Result {
        public final boolean success;
        public final boolean changed;
        public final String value;
        public final String message;

        Result(boolean success, boolean changed, String value, String message) {
            this.success = success;
            this.changed = changed;
            this.value = value;
            this.message = message;
        }
    }
}
