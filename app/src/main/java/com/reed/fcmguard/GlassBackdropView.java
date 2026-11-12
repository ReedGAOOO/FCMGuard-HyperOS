package com.reed.fcmguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

/**
 * Full-width band behind the fixed dashboard header. Content scrolling up passes under it
 * and fades out over the band's bottom edge instead of being cut off by a hard line.
 *
 * Glass mode: blurred scroll content with a translucent page-colour tint (system GPU blur,
 * see {@link GlassBlur}). Solid mode: the page colour. Both fade to transparent over the
 * bottom {@code fadePx}.
 */
public final class GlassBackdropView extends View {
    private final Paint solidPaint = new Paint();
    private GlassBlur blur;
    private int solidColor;
    private float solidFadePx;
    private int solidShaderHeight = -1;
    private boolean solid;
    /** Height actually drawn, from the top; the view itself spans the whole dashboard. */
    private int bandHeight = -1;

    public GlassBackdropView(Context context) {
        super(context);
    }

    public GlassBackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GlassBackdropView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    void enableGlass(View root, float radiusPx, int baseColor, int tint, float bottomFadePx,
            View... sources) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        releaseBlur();
        solid = false;
        blur = new GlassBlur(this, root, radiusPx, baseColor, sources);
        blur.setTint(tint);
        blur.setBottomFade(bottomFadePx);
        invalidate();
    }

    /** Opaque page-colour band with the same bottom fade, used when glass is off. */
    void showSolid(int color, float bottomFadePx) {
        releaseBlur();
        solid = true;
        solidColor = color;
        solidFadePx = Math.max(0f, bottomFadePx);
        solidShaderHeight = -1;
        invalidate();
    }

    void setBandHeight(int heightPx) {
        if (heightPx == bandHeight) return;
        bandHeight = heightPx;
        solidShaderHeight = -1;
        invalidate();
    }

    private void releaseBlur() {
        if (blur != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) blur.release();
        blur = null;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = bandHeight > 0 ? Math.min(bandHeight, getHeight()) : getHeight();
        if (width <= 0 || height <= 0) return;

        // Everything is drawn within this view's bounds: the dashboard root does not clip
        // its children, so an unbounded call such as drawColor() would cover the screen.
        if (blur != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blur.draw(canvas, width, height, 0f);
        } else if (solid) {
            if (solidShaderHeight != height) {
                solidShaderHeight = height;
                float fadeStart = Math.max(0f, height - solidFadePx);
                solidPaint.setShader(new LinearGradient(
                        0f, fadeStart, 0f, height,
                        solidColor, solidColor & 0x00FFFFFF, Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0f, 0f, width, height, solidPaint);
        }
    }
}
