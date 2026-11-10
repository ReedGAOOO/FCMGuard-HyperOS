package com.reed.fcmguard;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

import java.lang.reflect.Method;

/**
 * Decides whether the dashboard may use its frosted-glass material.
 *
 * The blur itself is Android's public RenderEffect (see {@link GlassBlur}), rendered by
 * the system on the GPU. Xiaomi's vendor View blur (setMiBackgroundBlurMode) was tried
 * first, but HyperOS 4 accepts those calls without rendering an in-window blur.
 *
 * Glass follows HyperOS's own advanced-material device tier: where the ROM reports
 * background blur as unsupported (lower GPU tiers), the opaque design is kept. It is also
 * paused in Battery saver, like HyperOS's own system UI.
 */
final class HyperOsMaterial {
    /** Set by HyperOS; "false" on devices where Xiaomi disables advanced material. */
    private static final String PROP_BLUR_SUPPORTED = "persist.sys.background_blur_supported";

    private static final Object LOCK = new Object();
    private static boolean lookupDone;
    private static boolean supported;

    private HyperOsMaterial() {}

    /** Android 12+ (RenderEffect) and not opted out by the ROM's advanced-material tier. */
    static boolean isDeviceSupported() {
        synchronized (LOCK) {
            if (lookupDone) return supported;
            lookupDone = true;
            String tier = readProperty(PROP_BLUR_SUPPORTED);
            supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !"false".equalsIgnoreCase(tier);
            return supported;
        }
    }

    static boolean isAllowedNow(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power == null || !power.isPowerSaveMode();
    }

    /** Returns the property value, or an empty string when absent or unreadable. */
    private static String readProperty(String key) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Method get = properties.getMethod("get", String.class, String.class);
            Object value = get.invoke(null, key, "");
            return value instanceof String ? ((String) value).trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }
}
