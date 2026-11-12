package com.reed.fcmguard;

import android.content.Context;
import android.content.res.Configuration;

public final class ThemeHelper {
    private static final String PREFS = "guard_state";
    private static final String KEY_MODE = "appearance_mode";
    private static final String KEY_GLASS = "hyperos_glass_material";
    private static final String KEY_PALETTE = "ui_palette";

    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";

    private ThemeHelper() {}

    /**
     * Applies only a local Configuration override. No service, timer, receiver, or
     * background task is involved; system mode remains the default.
     */
    public static Context apply(Context context) {
        String mode = getMode(context);
        if (MODE_SYSTEM.equals(mode)) return context;

        Configuration config = new Configuration(context.getResources().getConfiguration());
        int night = MODE_DARK.equals(mode)
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return context.createConfigurationContext(config);
    }

    public static String getMode(Context context) {
        String mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODE, MODE_SYSTEM);
        if (MODE_LIGHT.equals(mode) || MODE_DARK.equals(mode)) return mode;
        return MODE_SYSTEM;
    }

    public static void setMode(Context context, String mode) {
        String normalized = MODE_LIGHT.equals(mode) || MODE_DARK.equals(mode)
                ? mode
                : MODE_SYSTEM;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, normalized)
                .apply();
    }

    /** Id of the selected colour palette (see {@link Palettes}). */
    public static String getPalette(Context context) {
        String id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PALETTE, Palettes.DEFAULT);
        return Palettes.IDS[Palettes.indexOf(id)];
    }

    public static void setPalette(Context context, String id) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PALETTE, Palettes.IDS[Palettes.indexOf(id)])
                .apply();
    }

    /** HyperOS advanced material; on by default and only effective where supported. */
    public static boolean isGlassEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_GLASS, true);
    }

    public static void setGlassEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_GLASS, enabled)
                .apply();
    }

    public static boolean isDark(Context context) {
        int current = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return current == Configuration.UI_MODE_NIGHT_YES;
    }
}
