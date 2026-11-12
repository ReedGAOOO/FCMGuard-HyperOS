package com.reed.fcmguard;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

/** HyperOS/MIUI settings deep links with safe Android fallbacks. */
public final class HyperOsSettings {
    private HyperOsSettings() {}

    public static boolean openAutoStartManager(Context context) {
        Intent intent = new Intent("miui.intent.action.OP_AUTO_START");
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        if (launch(context, intent)) return true;

        Intent explicit = new Intent();
        explicit.setComponent(new ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
        ));
        if (launch(context, explicit)) return true;

        return launch(context, new Intent(Settings.ACTION_APPLICATION_SETTINGS));
    }

    /**
     * Opens the per-app battery policy page (where "No restrictions" is chosen), falling
     * back to Android's app details page, which on HyperOS also links to battery saver.
     */
    public static boolean openBatterySettings(Context context, String packageName, CharSequence label) {
        Intent hidden = new Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY");
        hidden.putExtra("package_name", packageName);
        hidden.putExtra("package_label", label);
        if (launch(context, hidden)) return true;

        Intent explicit = new Intent();
        explicit.setComponent(new ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
        ));
        explicit.putExtra("package_name", packageName);
        explicit.putExtra("package_label", label);
        if (launch(context, explicit)) return true;

        Intent details = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + packageName)
        );
        return launch(context, details);
    }

    public static boolean openAppPermissionEditor(Context context, String packageName) {
        String[] activityNames = {
                "com.miui.permcenter.permissions.PermissionsEditorActivity",
                "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
        };

        for (String activityName : activityNames) {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.miui.securitycenter", activityName));
            intent.putExtra("extra_pkgname", packageName);
            intent.putExtra("package_name", packageName);
            if (launch(context, intent)) return true;
        }

        Intent details = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + packageName)
        );
        details.addCategory(Intent.CATEGORY_DEFAULT);
        return launch(context, details);
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
