package com.alofcm;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Autostart state the user marked by hand.
 *
 * OriginOS 7 lets an app read only its own row of iManager's Autostart table, so the state
 * of other apps cannot be read. The user marks each app after checking it in iManager;
 * the UI always labels these values as marked by the user, never as read from the system.
 */
final class ManualAutostart {
    private static final String PREFS = "manual_autostart";

    private ManualAutostart() {}

    /** TRUE / FALSE when marked, null when never marked. */
    static Boolean get(Context context, String packageName) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.contains(packageName)) return null;
        return prefs.getBoolean(packageName, false);
    }

    static void set(Context context, String packageName, boolean enabled) {
        prefs(context).edit().putBoolean(packageName, enabled).apply();
    }

    /** Real state when readable, otherwise the user's mark (unmarked counts as off). */
    static AutostartStatusReader.Status effective(
            Context context, String packageName, AutostartStatusReader.Status read) {
        if (read != AutostartStatusReader.Status.UNKNOWN) return read;
        Boolean marked = get(context, packageName);
        return marked != null && marked
                ? AutostartStatusReader.Status.ENABLED : AutostartStatusReader.Status.DISABLED;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
