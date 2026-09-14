package com.reed.fcmguard;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.lang.reflect.Method;

/**
 * Best-effort, read-only probe for Xiaomi/HyperOS Autostart AppOps.
 *
 * This class never changes AppOps. HyperOS may block these vendor-specific queries on
 * some builds; in that case callers must present UNKNOWN rather than guessing.
 */
public final class AutostartStatusReader {
    private static final int OP_MIUI_AUTOSTART = 10008;
    private static final int OP_MIUI_AUTOSTART_SWITCH = 10053;

    public enum Status {
        ENABLED,
        PARTIAL,
        DISABLED,
        UNKNOWN
    }

    private AutostartStatusReader() {}

    public static Status check(Context context, String packageName) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            Integer primary = checkOp(context, OP_MIUI_AUTOSTART, info.uid, packageName);
            Integer switchOp = checkOp(context, OP_MIUI_AUTOSTART_SWITCH, info.uid, packageName);

            if (isAllowed(primary) && isAllowed(switchOp)) return Status.ENABLED;
            if (isIgnored(primary) && isIgnored(switchOp)) return Status.DISABLED;
            if ((isAllowed(primary) && isIgnored(switchOp)) ||
                    (isIgnored(primary) && isAllowed(switchOp))) {
                return Status.PARTIAL;
            }
            return Status.UNKNOWN;
        } catch (Throwable ignored) {
            return Status.UNKNOWN;
        }
    }

    private static boolean isAllowed(Integer mode) {
        return mode != null && mode == AppOpsManager.MODE_ALLOWED;
    }

    private static boolean isIgnored(Integer mode) {
        return mode != null && mode == AppOpsManager.MODE_IGNORED;
    }

    private static Integer checkOp(Context context, int op, int uid, String packageName) {
        AppOpsManager manager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (manager == null) return null;

        // Xiaomi keeps these vendor AppOps outside the public SDK constants. Reflection
        // lets the app remain compileSdk-clean while preserving a strictly read-only path.
        try {
            Method method = AppOpsManager.class.getMethod(
                    "checkOpNoThrow", int.class, int.class, String.class);
            Object result = method.invoke(manager, op, uid, packageName);
            return result instanceof Integer ? (Integer) result : null;
        } catch (Throwable ignored) {}

        try {
            Method method = AppOpsManager.class.getDeclaredMethod(
                    "checkOpNoThrow", int.class, int.class, String.class);
            method.setAccessible(true);
            Object result = method.invoke(manager, op, uid, packageName);
            return result instanceof Integer ? (Integer) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
