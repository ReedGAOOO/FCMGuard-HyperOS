package com.reed.fcmguard;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.view.View;

import java.lang.reflect.Method;

/**
 * Bridge to HyperOS "advanced material" (高级材质): the background blur that Xiaomi's
 * framework adds to {@link View} and renders on the system RenderThread. FCM Guard only
 * asks the system to blur what is already drawn behind a view, so there is no bitmap
 * capture, CPU blur or extra render pass in the app itself.
 *
 * Everything here is reflective and best-effort. On other ROMs, on HyperOS builds that do
 * not report blur support, when a vendor method is missing or blocked, or while Battery
 * saver is on, callers get {@code false} and keep the regular opaque surfaces.
 */
final class HyperOsMaterial {
    /** Set by HyperOS on devices whose GPU tier enables advanced material. */
    private static final String PROP_BLUR_SUPPORTED = "persist.sys.background_blur_supported";
    private static final int BLUR_MODE_OFF = 0;
    private static final int BLUR_MODE_BACKGROUND = 1;

    private static final Object LOCK = new Object();
    private static boolean lookupDone;
    private static boolean supported;
    private static Method setBackgroundBlurMode;
    private static Method setBackgroundBlurRadius;

    private HyperOsMaterial() {}

    /** True when this ROM exposes the vendor blur and reports the device as capable. */
    static boolean isDeviceSupported() {
        synchronized (LOCK) {
            if (lookupDone) return supported;
            lookupDone = true;
            supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    readBooleanProperty(PROP_BLUR_SUPPORTED) &&
                    lookUpViewMethods();
            return supported;
        }
    }

    /** Blur is paused while Battery saver is on, mirroring HyperOS's own system UI. */
    static boolean isAllowedNow(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power == null || !power.isPowerSaveMode();
    }

    /**
     * Blurs the window content behind {@code view}, clipped to the view's outline.
     * Returns false (and leaves the view un-blurred) if the vendor call fails.
     */
    static boolean enableBackgroundBlur(View view, int radiusPx) {
        if (view == null || !isDeviceSupported()) return false;
        try {
            setBackgroundBlurMode.invoke(view, BLUR_MODE_BACKGROUND);
            setBackgroundBlurRadius.invoke(view, radiusPx);
            return true;
        } catch (Throwable ignored) {
            disableBackgroundBlur(view);
            return false;
        }
    }

    static void disableBackgroundBlur(View view) {
        if (view == null || setBackgroundBlurMode == null) return;
        try {
            setBackgroundBlurMode.invoke(view, BLUR_MODE_OFF);
        } catch (Throwable ignored) {}
    }

    private static boolean lookUpViewMethods() {
        try {
            setBackgroundBlurMode = View.class.getMethod("setMiBackgroundBlurMode", int.class);
            setBackgroundBlurRadius = View.class.getMethod("setMiBackgroundBlurRadius", int.class);
            return true;
        } catch (Throwable ignored) {
            setBackgroundBlurMode = null;
            setBackgroundBlurRadius = null;
            return false;
        }
    }

    private static boolean readBooleanProperty(String key) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Method getBoolean = properties.getMethod("getBoolean", String.class, boolean.class);
            Object value = getBoolean.invoke(null, key, false);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
