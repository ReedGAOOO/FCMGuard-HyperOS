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

    /**
     * Android 13+ owns app-language configuration through LocaleManager. Older
     * versions keep the lightweight legacy fallback so the APK remains usable on
     * pre-33 devices even though HyperOS 3 is the primary target.
     */
    public static Context apply(Context context) {
        if (Build.VERSION.SDK_INT >= 33) return context;

        String code = getLegacyLanguage(context);
        if ("system".equals(code)) return context;

        Locale locale = "zh-CN".equals(code) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        Locale.setDefault(locale);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.locale = locale;
        if (Build.VERSION.SDK_INT >= 17) {
            config.setLocale(locale);
            return context.createConfigurationContext(config);
        }
        context.getResources().updateConfiguration(config, context.getResources().getDisplayMetrics());
        return context;
    }

    public static String getLanguage(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager localeManager = (LocaleManager) context.getSystemService(Context.LOCALE_SERVICE);
            if (localeManager == null) return "system";
            LocaleList locales = localeManager.getApplicationLocales();
            if (locales == null || locales.isEmpty()) return "system";
            String tag = locales.get(0).toLanguageTag();
            return tag != null && tag.toLowerCase(Locale.ROOT).startsWith("zh") ? "zh-CN" : "en";
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

    /**
     * One-time bridge for users upgrading from the old custom locale preference.
     * The migration runs before the UI is shown; after that Android's LocaleManager
     * is the single source of truth and stays synchronized with system Settings.
     */
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
        if (code == null) return "system";
        if (code.toLowerCase(Locale.ROOT).startsWith("zh")) return "zh-CN";
        if (code.toLowerCase(Locale.ROOT).startsWith("en")) return "en";
        return "system";
    }
}
