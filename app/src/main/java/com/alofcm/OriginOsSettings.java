package com.alofcm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * vivo OriginOS / Funtouch OS (iManager) settings deep links with safe Android fallbacks.
 *
 * vivo does not document these components and renames them between OS releases, so every
 * entry is tried in order and the first one that resolves wins. The final fallback is
 * always a public Android settings page, so a button never does nothing.
 */
public final class OriginOsSettings {
    private OriginOsSettings() {}

    /** iManager → App manager → Autostart (自启动 / 后台启动管理). */
    private static final String[][] AUTOSTART_COMPONENTS = {
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity"},
    };

    /** Battery → Background power consumption management (后台耗电管理 / 高耗电). */
    private static final String[][] BACKGROUND_POWER_COMPONENTS = {
            {"com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"},
            {"com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.SoftwareManagerActivity"},
    };

    /** Per-app permission page in iManager (holds Autostart and background pop-up). */
    private static final String[][] APP_PERMISSION_COMPONENTS = {
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.safeguard.SoftPermissionDetailActivity"},
    };

    public static boolean openAutoStartManager(Context context) {
        for (String[] c : AUTOSTART_COMPONENTS) {
            if (launch(context, explicit(c, null))) return true;
        }
        return launch(context, new Intent(Settings.ACTION_APPLICATION_SETTINGS));
    }

    /**
     * Opens the place where an app is allowed to run in the background: vivo's background
     * power page first, then Android's "ignore battery optimisations" request/list, then the
     * app details page (which on OriginOS links to Battery → Background power use).
     */
    public static boolean openBatterySettings(Context context, String packageName, CharSequence label) {
        for (String[] c : BACKGROUND_POWER_COMPONENTS) {
            if (launch(context, explicit(c, packageName))) return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isIgnoringBatteryOptimizations(context, packageName)) {
                Intent request = new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + packageName));
                if (launch(context, request)) return true;
            }
            if (launch(context, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
                return true;
            }
        }
        return launch(context, appDetails(packageName));
    }

    public static boolean openAppPermissionEditor(Context context, String packageName) {
        for (String[] c : APP_PERMISSION_COMPONENTS) {
            if (launch(context, explicit(c, packageName))) return true;
        }
        return launch(context, appDetails(packageName));
    }

    /** True on Android 6+ when Doze exempts the package; false (unknown) below Android 6. */
    public static boolean isIgnoringBatteryOptimizations(Context context, String packageName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(packageName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True when this phone looks like vivo / iQOO (OriginOS or Funtouch OS). */
    public static boolean isVivo() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        String b = Build.BRAND == null ? "" : Build.BRAND.toLowerCase(java.util.Locale.ROOT);
        return m.contains("vivo") || b.contains("vivo") || b.contains("iqoo");
    }

    private static Intent explicit(String[] component, String packageName) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(component[0], component[1]));
        if (packageName != null) {
            intent.putExtra("packagename", packageName);
            intent.putExtra("package_name", packageName);
            intent.putExtra("pkgname", packageName);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static Intent appDetails(String packageName) {
        Intent details = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + packageName));
        details.addCategory(Intent.CATEGORY_DEFAULT);
        return details;
    }

    private static boolean launch(Context context, Intent intent) {
        try {
            if (intent.resolveActivity(context.getPackageManager()) == null) return false;
            context.startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
