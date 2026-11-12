package com.reed.fcmguard;

import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;

/**
 * The "FCM" in the app name, painted with a gradient through the Google logo colours
 * (blue, red, yellow, green); the rest of the name keeps the theme's text colour. The
 * colours are the same in every theme and in light and dark mode.
 */
final class BrandText {
    static final String MARK = "FCM";
    private static final int BLUE = 0xFF4285F4;
    private static final int RED = 0xFFEA4335;
    private static final int YELLOW = 0xFFFBBC05;
    private static final int GREEN = 0xFF34A853;
    /** Held so each letter keeps a clear colour: F blue, C red, M yellow into green. */
    static final int[] COLORS = {BLUE, BLUE, RED, RED, YELLOW, GREEN, GREEN};
    static final float[] STOPS = {0f, 0.24f, 0.4f, 0.56f, 0.72f, 0.92f, 1f};

    private BrandText() {}

    /** {@code name} with its "FCM" painted in the Google colours (unchanged if absent). */
    static CharSequence appName(String name) {
        int start = name.indexOf(MARK);
        if (start < 0) return name;
        SpannableString text = new SpannableString(name);
        text.setSpan(new GradientSpan(name.substring(0, start)), start, start + MARK.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    /** Gradient spanning the mark, in the line's coordinates (the mark starts after {@code prefix}). */
    static LinearGradient gradient(Paint paint, String prefix, float left) {
        float x0 = left + (prefix.isEmpty() ? 0f : paint.measureText(prefix));
        float x1 = x0 + paint.measureText(MARK);
        return new LinearGradient(x0, 0f, x1, 0f, COLORS, STOPS, Shader.TileMode.CLAMP);
    }

    private static final class GradientSpan extends CharacterStyle implements UpdateAppearance {
        private final String prefix;
        private LinearGradient shader;
        private float shaderTextSize;

        GradientSpan(String prefix) {
            this.prefix = prefix;
        }

        @Override public void updateDrawState(TextPaint paint) {
            if (shader == null || shaderTextSize != paint.getTextSize()) {
                shader = gradient(paint, prefix, 0f);
                shaderTextSize = paint.getTextSize();
            }
            paint.setColor(0xFF000000 | paint.getColor()); // Opaque: the shader supplies the colour.
            paint.setShader(shader);
        }
    }
}
