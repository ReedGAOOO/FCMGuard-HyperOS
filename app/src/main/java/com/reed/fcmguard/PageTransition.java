package com.reed.fcmguard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;

/**
 * Smooth whole-page switches (palette, light/dark, language, glass).
 *
 * The window is snapshotted, the change is applied (usually by recreating the activity),
 * and the snapshot is laid over the new page and faded out, so the page crossfades
 * instead of flashing. Costs one window-sized bitmap for the length of the fade; nothing
 * runs when animations are turned off.
 */
final class PageTransition {
    private static final long FADE_MS = 360L;

    /** Snapshot handed from the activity being recreated to its replacement. */
    private static Bitmap pending;
    private static boolean capturing;

    private interface Captured {
        void onCaptured(Bitmap snapshotOrNull);
    }

    private PageTransition() {}

    /** Recreates {@code activity}; the new instance fades in from the current page. */
    static void recreate(Activity activity) {
        if (capturing) return; // The recreate already on its way picks up every saved change.
        capture(activity, snapshot -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            pending = snapshot;
            activity.recreate();
        });
    }

    /**
     * Applies an in-place {@code change} and crossfades to it. Only the area between the
     * window top and {@code bottomPx} (window coordinates) fades; pass a large value for
     * the whole window.
     */
    static void crossfade(Activity activity, int bottomPx, Runnable change) {
        if (capturing) {
            change.run();
            return;
        }
        capture(activity, snapshot -> {
            change.run();
            if (snapshot != null && !activity.isDestroyed()) {
                fadeOut(activity, snapshot, bottomPx);
            }
        });
    }

    /** Called by a recreated activity right after {@code setContentView}. */
    static void playPending(Activity activity) {
        Bitmap snapshot = pending;
        pending = null;
        if (snapshot != null) fadeOut(activity, snapshot, Integer.MAX_VALUE);
    }

    private static void capture(Activity activity, Captured then) {
        View decor = activity.getWindow().getDecorView();
        int width = decor.getWidth();
        int height = decor.getHeight();
        if (width <= 0 || height <= 0 || !animationsEnabled(activity)) {
            then.onCaptured(null);
            return;
        }
        final Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            then.onCaptured(null);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // PixelCopy reads what is on screen, including the GPU blur of the glass header.
            try {
                capturing = true;
                PixelCopy.request(activity.getWindow(), bitmap, result -> {
                    capturing = false;
                    boolean ok = result == PixelCopy.SUCCESS || drawInto(decor, bitmap);
                    then.onCaptured(ok ? bitmap : null);
                }, new Handler(Looper.getMainLooper()));
                return;
            } catch (RuntimeException noSurface) {
                capturing = false; // Window not on screen: draw it in software instead.
            }
        }
        then.onCaptured(drawInto(decor, bitmap) ? bitmap : null);
    }

    private static boolean drawInto(View decor, Bitmap bitmap) {
        try {
            decor.draw(new Canvas(bitmap));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean animationsEnabled(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return ValueAnimator.areAnimatorsEnabled();
        return Settings.Global.getFloat(activity.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    private static void fadeOut(Activity activity, Bitmap snapshot, int bottomPx) {
        final View decor = activity.getWindow().getDecorView();
        final SnapshotDrawable drawable = new SnapshotDrawable(snapshot, bottomPx);
        decor.getOverlay().add(drawable);

        final ValueAnimator fade = ValueAnimator.ofInt(255, 0);
        fade.setDuration(FADE_MS);
        fade.setInterpolator(new DecelerateInterpolator(1.4f));
        fade.addUpdateListener(animation -> drawable.setAlpha((Integer) animation.getAnimatedValue()));
        fade.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                decor.getOverlay().remove(drawable);
            }
        });
        // Start with the first frame of the new page, so the fade runs over it rather
        // than over a window that has not drawn yet.
        decor.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                decor.getViewTreeObserver().removeOnPreDrawListener(this);
                fade.start();
                return true;
            }
        });
        decor.invalidate();
    }

    /** The snapshot, drawn at window origin and limited to the band above {@code bottomPx}. */
    private static final class SnapshotDrawable extends Drawable {
        private final Bitmap bitmap;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect clip;

        SnapshotDrawable(Bitmap bitmap, int bottomPx) {
            this.bitmap = bitmap;
            clip = new Rect(0, 0, bitmap.getWidth(), Math.min(bitmap.getHeight(), Math.max(0, bottomPx)));
            setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
        }

        @Override public void draw(Canvas canvas) {
            int save = canvas.save();
            canvas.clipRect(clip);
            canvas.drawBitmap(bitmap, 0f, 0f, paint);
            canvas.restoreToCount(save);
        }

        @Override public void setAlpha(int alpha) {
            if (paint.getAlpha() == alpha) return;
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
