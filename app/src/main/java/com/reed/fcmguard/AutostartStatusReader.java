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
 *
 * Thread-safe: callers run it on a background executor so a long FCM app list never
 * blocks the UI thread.
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

    private static final Object METHOD_LOCK = new Object();
    private static Method checkOpNoThrow;
    private static boolean checkOpLookupDone;

    private AutostartStatusReader() {}

    public static Status check(Context context, String packageName) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            return check(context, packageName, info.uid);
        } catch (Throwable ignored) {
            return Status.UNKNOWN;
        }
    }

    /** Same as {@link #check(Context, String)} but reuses a uid the caller already knows. */
    public static Status check(Context context, String packageName, int uid) {
        try {
            AppOpsManager manager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            Method method = checkOpMethod();
            if (manager == null || method == null) return Status.UNKNOWN;

            Integer primary = checkOp(method, manager, OP_MIUI_AUTOSTART, uid, packageName);
            Integer switchOp = checkOp(method, manager, OP_MIUI_AUTOSTART_SWITCH, uid, packageName);

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

    private static Integer checkOp(Method method, AppOpsManager manager, int op, int uid, String packageName) {
        try {
            Object result = method.invoke(manager, op, uid, packageName);
            return result instanceof Integer ? (Integer) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Xiaomi keeps these vendor AppOps outside the public SDK constants, so the int-op
     * overload is reached through reflection. The lookup is done once per process instead
     * of twice per scanned app.
     */
    private static Method checkOpMethod() {
        synchronized (METHOD_LOCK) {
            if (checkOpLookupDone) return checkOpNoThrow;
            checkOpLookupDone = true;
            try {
                checkOpNoThrow = AppOpsManager.class.getMethod(
                        "checkOpNoThrow", int.class, int.class, String.class);
            } catch (Throwable ignored) {
                try {
                    Method method = AppOpsManager.class.getDeclaredMethod(
                            "checkOpNoThrow", int.class, int.class, String.class);
                    method.setAccessible(true);
                    checkOpNoThrow = method;
                } catch (Throwable alsoIgnored) {
                    checkOpNoThrow = null;
                }
            }
            return checkOpNoThrow;
        }
    }
}
