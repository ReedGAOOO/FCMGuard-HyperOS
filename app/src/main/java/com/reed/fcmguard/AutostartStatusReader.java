package com.reed.fcmguard;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.util.ArrayList;
import java.util.List;
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
            return check(context, info);
        } catch (Throwable ignored) {
            return Status.UNKNOWN;
        }
    }

    public static Status check(Context context, ApplicationInfo info) {
        if (info == null || info.packageName == null) return Status.UNKNOWN;
        try {
            Integer primary = checkOp(context, OP_MIUI_AUTOSTART, info.uid, info.packageName);
            Integer switchOp = checkOp(context, OP_MIUI_AUTOSTART_SWITCH, info.uid, info.packageName);

            if (isAllowed(primary) && isAllowed(switchOp)) return Status.ENABLED;
            if (isIgnored(primary) && isIgnored(switchOp)) return Status.DISABLED;
            if ((isAllowed(primary) && isIgnored(switchOp)) ||
                    (isIgnored(primary) && isAllowed(switchOp))) return Status.PARTIAL;
            return Status.UNKNOWN;
        } catch (Throwable ignored) {
            return Status.UNKNOWN;
        }
    }

    /** Reads Xiaomi Autostart state directly under this app UID; no Shizuku service is used. */
    public static ScanResult findEnabledPackages(Context context) {
        ScanResult result = new ScanResult();
        try {
            List<ApplicationInfo> installed = context.getPackageManager().getInstalledApplications(0);
            for (ApplicationInfo app : installed) {
                if (app == null || app.packageName == null || !app.enabled
                        || !isUserInstalledApp(app.flags)) continue;
                result.scannedCount++;
                Status status = check(context, app);
                if (status == Status.ENABLED) result.enabledPackages.add(app.packageName);
                else if (status == Status.UNKNOWN) {
                    result.unknownCount++;
                    result.unknownPackages.add(app.packageName);
                }
            }
            result.readSucceeded = true;
        } catch (Throwable error) {
            result.error = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        }
        return result;
    }

    /** Checks Autostart only for installed, enabled user apps with an active FCM receive component. */
    public static ScanResult findEnabledFcmPackages(Context context) {
        ScanResult result = new ScanResult();
        FcmAppScanner.ScanResult fcmScan = FcmAppScanner.scanDetailed(context);
        if (!fcmScan.complete) {
            result.error = fcmScan.error;
            return result;
        }
        try {
            for (FcmAppScanner.AppEntry app : fcmScan.apps) {
                result.scannedCount++;
                Status status = check(context, app.packageName);
                if (status == Status.ENABLED) result.enabledPackages.add(app.packageName);
                else if (status == Status.UNKNOWN) {
                    result.unknownCount++;
                    result.unknownPackages.add(app.packageName);
                }
            }
            result.readSucceeded = true;
        } catch (Throwable error) {
            result.error = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        }
        return result;
    }

    /** A user-installed app has no system-image flag; updated system apps remain excluded. */
    static boolean isUserInstalledApp(int applicationFlags) {
        return (applicationFlags & ApplicationInfo.FLAG_SYSTEM) == 0;
    }

    public static final class ScanResult {
        public final List<String> enabledPackages = new ArrayList<>();
        public final List<String> unknownPackages = new ArrayList<>();
        public int scannedCount;
        public int unknownCount;
        public boolean readSucceeded;
        public String error = "";
        public boolean isComplete() { return readSucceeded && unknownCount == 0; }
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
