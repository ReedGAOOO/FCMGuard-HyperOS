package com.reed.fcmguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.LinkedHashSet;

public final class SettingsGuard {
    private static final String PREFS = "guard_state";
    private static final String PREF_KEY = "setting_key";
    private static final String PREF_TOKEN = "required_token";
    private static final String LAST_GOOD = "last_good_value";

    private SettingsGuard() {}

    public static void configure(Context context, String key, String token) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_KEY, key.trim())
                .putString(PREF_TOKEN, token.trim())
                .apply();
    }

    public static String getKey(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_KEY, "");
    }

    public static String getToken(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_TOKEN, "");
    }

    public static String read(Context context) {
        String key = getKey(context);
        if (key.isEmpty()) return null;
        return Settings.System.getString(context.getContentResolver(), key);
    }

    public static boolean containsToken(Context context, String value) {
        String token = getToken(context);
        if (token.isEmpty() || value == null) return false;
        for (String part : value.split(",")) {
            if (token.equals(part.trim())) return true;
        }
        return false;
    }

    public static synchronized Result repair(Context context) {
        String key = getKey(context);
        String token = getToken(context);
        if (key.isEmpty() || token.isEmpty()) {
            return new Result(false, null, "Configure a setting key and required item first");
        }
        if (!Settings.System.canWrite(context)) {
            return new Result(false, read(context), "Modify system settings permission is not granted");
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String current = Settings.System.getString(context.getContentResolver(), key);

        // If HyperOS has not removed the required item, do not rewrite the setting.
        // Rewriting the same value repeatedly can itself create unnecessary policy churn.
        if (containsToken(context, current)) {
            if (current != null && !current.trim().isEmpty()) {
                prefs.edit().putString(LAST_GOOD, current).apply();
            }
            return new Result(true, current, "Already protected");
        }

        LinkedHashSet<String> items = parse(current);
        if (items.isEmpty()) items.addAll(parse(prefs.getString(LAST_GOOD, null)));
        items.add(token);
        String repaired = join(items);

        try {
            boolean ok = Settings.System.putString(context.getContentResolver(), key, repaired);
            if (ok) {
                prefs.edit().putString(LAST_GOOD, repaired).apply();
                // The whitelist change prevents another freeze, but an already-dead MCS
                // connection may need a nudge to reconnect.
                FcmReconnect.kick(context);
                return new Result(true, repaired, "Repaired and reconnect requested");
            }
            return new Result(false, current, "System rejected the write");
        } catch (Throwable t) {
            return new Result(false, current, "Write failed: " + t.getClass().getSimpleName());
        }
    }

    private static LinkedHashSet<String> parse(String value) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (value == null) return out;
        for (String part : value.split(",")) {
            String v = part.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    private static String join(LinkedHashSet<String> items) {
        StringBuilder out = new StringBuilder();
        for (String item : items) {
            if (out.length() > 0) out.append(',');
            out.append(item);
        }
        return out.toString();
    }

    public static final class Result {
        public final boolean success;
        public final String value;
        public final String message;

        Result(boolean success, String value, String message) {
            this.success = success;
            this.value = value;
            this.message = message;
        }
    }
}
