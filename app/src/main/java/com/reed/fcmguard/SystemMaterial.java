package com.reed.fcmguard;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

/**
 * Decides whether the dashboard may use its frosted-glass material.
 *
 * The blur itself is Android's public RenderEffect (see {@link GlassBlur}), rendered by
 * the system on the GPU. OriginOS has no public "advanced material" tier flag like
 * HyperOS, so glass is offered on Android 12+ unless the device reports itself as a
 * low-RAM device. It is also paused in Battery saver, like OriginOS's own system UI.
 */
final class SystemMaterial {
    private static final Object LOCK = new Object();
    private static boolean lookupDone;
    private static boolean supported;

    private SystemMaterial() {}

    /** Android 12+ (RenderEffect) and not a low-RAM device. */
    static boolean isDeviceSupported(Context context) {
        synchronized (LOCK) {
            if (lookupDone) return supported;
            lookupDone = true;
            boolean lowRam = false;
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                lowRam = am != null && am.isLowRamDevice();
            } catch (Throwable ignored) {}
            supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !lowRam;
            return supported;
        }
    }

    static boolean isAllowedNow(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power == null || !power.isPowerSaveMode();
    }
}
