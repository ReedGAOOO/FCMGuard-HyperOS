package com.reed.fcmguard;

import android.annotation.TargetApi;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewParent;

/**
 * Frosted-glass backdrop for one view, built on Android's public RenderNode/RenderEffect
 * API (Android 12+). The views behind the target are re-recorded into a RenderNode — which
 * only references their existing display lists — and the system blurs that node on the
 * RenderThread/GPU. No bitmaps are captured and no blur runs on the CPU.
 *
 * Sources must be drawn earlier in the view tree than the target (true for the dashboard:
 * scroll content, then header band, then hero, then status card), so their display lists
 * are already current when the target records them.
 */
@TargetApi(Build.VERSION_CODES.S)
final class GlassBlur {
    private final RenderNode node = new RenderNode("FCMGuardGlass");
    private final Outline outline = new Outline();
    private final Paint flatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] targetOffset = new float[2];
    private final float[] sourceOffset = new float[2];
    private final View target;
    private final View root;
    private final View[] sources;
    private final int baseColor;
    private final RenderEffect blurEffect;
    private int tintColor;
    private float bottomFadePx;
    private int effectHeight = -1;

    GlassBlur(View target, View root, float radiusPx, int baseColor, View... sources) {
        this.target = target;
        this.root = root;
        this.baseColor = baseColor;
        this.sources = sources;
        blurEffect = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP);
        node.setRenderEffect(blurEffect);
    }

    /** Uniform tint laid over the backdrop inside the node (a flat colour is unchanged by blur). */
    void setTint(int color) {
        tintColor = color;
    }

    /**
     * Fades the whole glass layer to transparent over the bottom {@code fadePx}, so the
     * frosted area blends into the sharp content below instead of ending in a hard line.
     * Done inside the node's effect chain (blur, then DST_IN with a gradient), so it needs
     * no extra offscreen layer.
     */
    void setBottomFade(float fadePx) {
        bottomFadePx = Math.max(0f, fadePx);
        effectHeight = -1;
    }

    private void updateEffect(int height) {
        if (bottomFadePx <= 0f || height == effectHeight) return;
        effectHeight = height;
        float fadeStart = Math.max(0f, height - bottomFadePx);
        LinearGradient mask = new LinearGradient(
                0f, fadeStart, 0f, height,
                0xFF000000, 0x00000000, Shader.TileMode.CLAMP);
        node.setRenderEffect(RenderEffect.createBlendModeEffect(
                blurEffect, RenderEffect.createShaderEffect(mask), BlendMode.DST_IN));
    }

    /**
     * Draws the blurred backdrop into the target's canvas, covering [0, width] x
     * [0, height] in target coordinates, with optional rounded corners.
     */
    void draw(Canvas canvas, int width, int height, float cornerRadius) {
        if (width <= 0 || height <= 0) return;
        if (!canvas.isHardwareAccelerated()) {
            drawFlat(canvas, width, height, cornerRadius);
            return;
        }

        node.setPosition(0, 0, width, height);
        updateEffect(height);
        if (cornerRadius > 0f) {
            outline.setRoundRect(0, 0, width, height, cornerRadius);
            node.setOutline(outline);
            node.setClipToOutline(true);
        } else {
            node.setOutline(null);
            node.setClipToOutline(false);
        }

        offsetInRoot(target, targetOffset);
        RecordingCanvas recording = node.beginRecording(width, height);
        try {
            recording.drawColor(baseColor);
            for (View source : sources) {
                if (source == null || source.getVisibility() != View.VISIBLE) continue;
                offsetInRoot(source, sourceOffset);
                int save = recording.save();
                recording.translate(
                        sourceOffset[0] - targetOffset[0],
                        sourceOffset[1] - targetOffset[1]);
                source.draw(recording);
                recording.restoreToCount(save);
            }
            if (tintColor != 0) recording.drawColor(tintColor);
        } finally {
            node.endRecording();
        }
        canvas.drawRenderNode(node);
    }

    /**
     * Software canvases (the page-transition snapshot taken while Android recreates the
     * page) cannot run the GPU blur, so the frosted layer is drawn as its flat base and
     * tint, with the same bottom fade, instead of leaving a hole.
     */
    private void drawFlat(Canvas canvas, int width, int height, float cornerRadius) {
        int color = tintColor != 0 ? over(tintColor, baseColor) : baseColor;
        flatPaint.setColor(color);
        if (bottomFadePx > 0f) {
            float fadeStart = Math.max(0f, height - bottomFadePx);
            flatPaint.setShader(new LinearGradient(0f, fadeStart, 0f, height,
                    color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        } else {
            flatPaint.setShader(null);
        }
        if (cornerRadius > 0f) {
            canvas.drawRoundRect(0f, 0f, width, height, cornerRadius, cornerRadius, flatPaint);
        } else {
            canvas.drawRect(0f, 0f, width, height, flatPaint);
        }
    }

    /** {@code top} (with alpha) laid over the opaque {@code base}. */
    private static int over(int top, int base) {
        float a = (top >>> 24) / 255f;
        int r = Math.round(((top >> 16) & 0xFF) * a + ((base >> 16) & 0xFF) * (1f - a));
        int g = Math.round(((top >> 8) & 0xFF) * a + ((base >> 8) & 0xFF) * (1f - a));
        int b = Math.round((top & 0xFF) * a + (base & 0xFF) * (1f - a));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    void release() {
        node.discardDisplayList();
    }

    /** Position of {@code view}'s top-left corner in {@link #root} coordinates. */
    private void offsetInRoot(View view, float[] out) {
        float x = 0f;
        float y = 0f;
        View current = view;
        while (current != null && current != root) {
            x += current.getLeft() + current.getTranslationX();
            y += current.getTop() + current.getTranslationY();
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            View parentView = (View) parent;
            x -= parentView.getScrollX();
            y -= parentView.getScrollY();
            current = parentView;
        }
        out[0] = x;
        out[1] = y;
    }
}
