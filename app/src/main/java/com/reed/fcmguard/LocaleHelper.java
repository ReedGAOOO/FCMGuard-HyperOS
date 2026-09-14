package com.reed.fcmguard;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

public final class LocaleHelper {
    private static final String PREFS = "guard_state";
    private static final String KEY_LANG = "ui_language";
    private static final String KEY_MIGRATED = "native_locale_migrated";

    private LocaleHelper() {}

    /** Android 13+ uses LocaleManager; older Android keeps a small compatibility path. */
    public static Context apply(Context context) {
        if (Build.VERSION.SDK_INT >= 33) return context;

        String code = getLegacyLanguage(context);
        if ("system".equals(code)) return context;

        Locale locale = Locale.forLanguageTag(code);
        Locale.setDefault(locale);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        return context.createConfigurationContext(config);
    }

    public static String getLanguage(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager localeManager = (LocaleManager) context.getSystemService(Context.LOCALE_SERVICE);
            if (localeManager == null) return "system";
            LocaleList locales = localeManager.getApplicationLocales();
            if (locales == null || locales.isEmpty()) return "system";
            return normalize(locales.get(0).toLanguageTag());
        }
        return getLegacyLanguage(context);
    }

    public static void setLanguage(Context context, String code) {
        String normalized = normalize(code);
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager localeManager = (LocaleManager) context.getSystemService(Context.LOCALE_SERVICE);
            if (localeManager == null) return;
            LocaleList locales = "system".equals(normalized)
                    ? LocaleList.getEmptyLocaleList()
                    : LocaleList.forLanguageTags(normalized);
            localeManager.setApplicationLocales(locales);
            return;
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANG, normalized)
                .apply();
    }

    /** One-time bridge from the pre-v1.4.4 custom language preference. */
    public static void migrateLegacyPreference(Context context) {
        if (Build.VERSION.SDK_INT < 33) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_MIGRATED, false)) return;

        String legacy = normalize(prefs.getString(KEY_LANG, "system"));
        prefs.edit().putBoolean(KEY_MIGRATED, true).remove(KEY_LANG).apply();

        LocaleManager localeManager = (LocaleManager) context.getSystemService(Context.LOCALE_SERVICE);
        if (localeManager == null || "system".equals(legacy)) return;
        LocaleList current = localeManager.getApplicationLocales();
        if (current == null || current.isEmpty()) {
            localeManager.setApplicationLocales(LocaleList.forLanguageTags(legacy));
        }
    }

    private static String getLegacyLanguage(Context context) {
        return normalize(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LANG, "system"));
    }

    private static String normalize(String code) {
        if (code == null || code.trim().isEmpty() || "system".equalsIgnoreCase(code)) {
            return "system";
        }

        String lower = code.toLowerCase(Locale.ROOT);
        if (lower.startsWith("zh-tw") || lower.startsWith("zh-hk") ||
                lower.startsWith("zh-mo") || lower.contains("hant")) {
            return "zh-TW";
        }
        if (lower.startsWith("zh")) return "zh-CN";
        if (lower.startsWith("fr")) return "fr";
        if (lower.startsWith("ja")) return "ja";
        if (lower.startsWith("ko")) return "ko";
        if (lower.startsWith("en")) return "en";
        return "system";
    }
}
