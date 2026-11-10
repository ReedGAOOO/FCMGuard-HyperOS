package com.reed.fcmguard;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

/**
 * Full-width frosted band behind the fixed dashboard header: blurred scroll content with a
 * translucent page-colour tint on top. Draws nothing while glass is off.
 */
public final class GlassBackdropView extends View {
    private GlassBlur blur;
    private int tintColor;

    public GlassBackdropView(Context context) {
        super(context);
    }

    public GlassBackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GlassBackdropView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    void enableGlass(View root, float radiusPx, int baseColor, int tint, View... sources) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        disableGlass();
        blur = new GlassBlur(this, root, radiusPx, baseColor, sources);
        tintColor = tint;
        invalidate();
    }

    void disableGlass() {
        if (blur != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) blur.release();
        blur = null;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (blur == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        blur.draw(canvas, getWidth(), getHeight(), 0f);
        canvas.drawColor(tintColor);
    }
}
