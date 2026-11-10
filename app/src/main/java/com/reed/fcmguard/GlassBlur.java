package com.reed.fcmguard;

import android.annotation.TargetApi;
import android.graphics.Canvas;
import android.graphics.Outline;
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
    private final float[] targetOffset = new float[2];
    private final float[] sourceOffset = new float[2];
    private final View target;
    private final View root;
    private final View[] sources;
    private final int baseColor;

    GlassBlur(View target, View root, float radiusPx, int baseColor, View... sources) {
        this.target = target;
        this.root = root;
        this.baseColor = baseColor;
        this.sources = sources;
        node.setRenderEffect(RenderEffect.createBlurEffect(
                radiusPx, radiusPx, Shader.TileMode.CLAMP));
    }

    /**
     * Draws the blurred backdrop into the target's canvas, covering [0, width] x
     * [0, height] in target coordinates, with optional rounded corners.
     */
    void draw(Canvas canvas, int width, int height, float cornerRadius) {
        if (width <= 0 || height <= 0 || !canvas.isHardwareAccelerated()) return;

        node.setPosition(0, 0, width, height);
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
        } finally {
            node.endRecording();
        }
        canvas.drawRenderNode(node);
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
