package com.reed.fcmguard;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import java.util.Locale;

public final class LocaleHelper {
    private static final String PREFS = "guard_state";
    private static final String KEY_LANG = "ui_language";

    private LocaleHelper() {}

    public static Context apply(Context context) {
        String code = getLanguage(context);
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
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, "system");
    }

    public static void setLanguage(Context context, String code) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANG, code).apply();
    }
}
