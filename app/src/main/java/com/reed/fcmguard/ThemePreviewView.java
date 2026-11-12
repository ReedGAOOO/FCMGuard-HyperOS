package com.reed.fcmguard;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * A miniature of the dashboard in one theme: hero with its two capsules, the status card
 * with tinted tiles, and a card with a switch and the primary button. Drawn from the
 * theme's own colours, so what it shows is what the page will look like.
 */
final class ThemePreviewView extends View {
    /** Height / width. */
    static final float ASPECT = 1.36f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final String appName;
    private final String headline;
    private final RectF rect = new RectF();
    private final RectF page = new RectF();
    private final Path clip = new Path();
    private final Path check = new Path();
    private final float dp;

    private int bg, surface, tonal, border, text, text2, pill, accent, fill, tonalButton, tonalButtonText;
    private int heroStart, heroCenter, heroEnd;
    private int tileGood, tileWarn, tileBad, tileNeutral, good, warn, bad, neutral;
    private LinearGradient heroShader;
    private float heroShaderLeft = Float.NaN;
    private boolean checked;

    ThemePreviewView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        appName = context.getString(R.string.app_name);
        headline = context.getString(R.string.status_protected);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setTheme(Resources.Theme theme) {
        bg = AppColors.get(theme, R.attr.pal_bg);
        surface = AppColors.get(theme, R.attr.pal_surface);
        tonal = AppColors.get(theme, R.attr.pal_surface_alt);
        border = AppColors.get(theme, R.attr.pal_floating_border);
        text = AppColors.get(theme, R.attr.pal_text_primary);
        text2 = AppColors.get(theme, R.attr.pal_text_secondary);
        pill = AppColors.get(theme, R.attr.pal_hero_pill);
        accent = AppColors.get(theme, R.attr.pal_blue);
        fill = AppColors.get(theme, R.attr.pal_primary_fill);
        tonalButton = over(AppColors.get(theme, R.attr.pal_button_tonal), surface);
        tonalButtonText = AppColors.get(theme, R.attr.pal_button_tonal_text);
        heroStart = AppColors.get(theme, R.attr.pal_hero_start);
        heroCenter = AppColors.get(theme, R.attr.pal_hero_center);
        heroEnd = AppColors.get(theme, R.attr.pal_hero_end);
        tileGood = over(AppColors.get(theme, R.attr.pal_status_value_green_bg), surface);
        tileWarn = over(AppColors.get(theme, R.attr.pal_status_value_yellow_bg), surface);
        tileBad = over(AppColors.get(theme, R.attr.pal_status_value_red_bg), surface);
        tileNeutral = over(AppColors.get(theme, R.attr.pal_status_value_neutral_bg), surface);
        good = AppColors.get(theme, R.attr.pal_badge_text_green);
        warn = AppColors.get(theme, R.attr.pal_badge_text_yellow);
        bad = AppColors.get(theme, R.attr.pal_badge_text_red);
        neutral = AppColors.get(theme, R.attr.pal_badge_text_neutral);
        heroShader = null;
        invalidate();
    }

    void setChecked(boolean checked) {
        if (this.checked == checked) return;
        this.checked = checked;
        invalidate();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, resolveSize(Math.round(width * ASPECT), heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;

        // Selection ring around the page, in the theme's own accent.
        float ring = 2.5f * dp;
        float inset = ring + 3f * dp;
        if (checked) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ring);
            paint.setColor(accent);
            rect.set(ring / 2f, ring / 2f, w - ring / 2f, h - ring / 2f);
            canvas.drawRoundRect(rect, 17f * dp, 17f * dp, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        page.set(inset, inset, w - inset, h - inset);
        float pw = page.width();
        float ph = page.height();
        float pageRadius = 13f * dp;
        clip.reset();
        clip.addRoundRect(page, pageRadius, pageRadius, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clip);
        canvas.drawColor(bg);

        // Hero card with its gradient, title lines and the two capsules.
        rect.set(page.left + pw * 0.05f, page.top + ph * 0.04f, page.right - pw * 0.05f, page.top + ph * 0.38f);
        if (heroShader == null || heroShaderLeft != rect.left) {
            heroShader = new LinearGradient(rect.left, 0f, rect.right, 0f,
                    new int[] {heroStart, heroCenter, heroEnd}, null, Shader.TileMode.CLAMP);
            heroShaderLeft = rect.left;
        }
        paint.setShader(heroShader);
        canvas.drawRoundRect(rect, 9f * dp, 9f * dp, paint);
        paint.setShader(null);
        float heroLeft = rect.left;
        float heroTop = rect.top;
        float heroRight = rect.right;
        drawAppName(canvas, heroLeft + pw * 0.07f, heroTop + ph * 0.1f, pw * 0.5f, ph * 0.062f);
        bar(canvas, heroLeft + pw * 0.07f, heroTop + ph * 0.135f, pw * 0.34f, ph * 0.02f, text2);
        float capW = pw * 0.21f;
        float capH = ph * 0.05f;
        for (int i = 0; i < 2; i++) {
            float top = heroTop + ph * 0.04f + i * (capH + ph * 0.014f);
            bar(canvas, heroRight - pw * 0.05f - capW, top, capW, capH, pill);
            paint.setColor(accent);
            canvas.drawCircle(heroRight - pw * 0.05f - capW + capH * 0.6f, top + capH / 2f, capH * 0.22f, paint);
            bar(canvas, heroRight - pw * 0.05f - capW + capH * 1.05f, top + capH * 0.38f, capW * 0.42f, capH * 0.24f, accent);
        }

        // Floating status card: headline, four tinted tiles and the tonal whitelist strip.
        float gap = pw * 0.03f;
        float tileH = ph * 0.105f;
        float cardTop = page.top + ph * 0.24f;
        float tilesTop = cardTop + ph * 0.11f;
        float stripTop = tilesTop + 2f * tileH + gap + ph * 0.025f;
        float stripH = ph * 0.055f;
        float cardBottom = stripTop + stripH + ph * 0.03f;
        rect.set(page.left + pw * 0.09f, cardTop, page.right - pw * 0.09f, cardBottom);
        paint.setColor(surface);
        canvas.drawRoundRect(rect, 8f * dp, 8f * dp, paint);
        stroke(canvas, rect, 8f * dp, border);
        float cardLeft = rect.left + pw * 0.05f;
        float cardRight = rect.right - pw * 0.05f;
        // Headline row: status on the left, the tonal "Protect now" pill on the right.
        float pillW = pw * 0.27f;
        float pillH = ph * 0.046f;
        float pillLeft = cardRight - pillW;
        float pillTop = cardTop + ph * 0.06f - pillH / 2f;
        paint.setColor(good);
        canvas.drawCircle(cardLeft + ph * 0.017f, cardTop + ph * 0.06f, ph * 0.017f, paint);
        drawText(canvas, headline, cardLeft + ph * 0.045f, cardTop + ph * 0.075f,
                pillLeft - pw * 0.03f - (cardLeft + ph * 0.045f), ph * 0.042f,
                android.graphics.Typeface.DEFAULT_BOLD, good);
        bar(canvas, pillLeft, pillTop, pillW, pillH, tonalButton);
        paint.setColor(tonalButtonText);
        canvas.drawCircle(pillLeft + pillH * 0.62f, pillTop + pillH / 2f, pillH * 0.2f, paint);
        bar(canvas, pillLeft + pillH * 1.05f, pillTop + pillH * 0.38f, pillW * 0.5f, pillH * 0.24f, tonalButtonText);
        float tileW = (cardRight - cardLeft - gap) / 2f;
        int[] tiles = {tileBad, tileNeutral, tileWarn, tileGood};
        int[] values = {bad, neutral, warn, good};
        for (int i = 0; i < 4; i++) {
            float left = cardLeft + (i % 2) * (tileW + gap);
            float top = tilesTop + (i / 2) * (tileH + gap);
            rect.set(left, top, left + tileW, top + tileH);
            paint.setColor(tiles[i]);
            canvas.drawRoundRect(rect, 4.5f * dp, 4.5f * dp, paint);
            bar(canvas, left + tileW * 0.12f, top + tileH * 0.24f, tileW * 0.62f, tileH * 0.16f, withAlpha(text2, 0.75f));
            bar(canvas, left + tileW * 0.12f, top + tileH * 0.56f, tileW * 0.42f, tileH * 0.2f, values[i]);
        }
        rect.set(cardLeft, stripTop, cardRight, stripTop + stripH);
        paint.setColor(tonal);
        canvas.drawRoundRect(rect, 4.5f * dp, 4.5f * dp, paint);
        bar(canvas, cardLeft + tileW * 0.12f, stripTop + stripH * 0.36f, (cardRight - cardLeft) * 0.6f,
                stripH * 0.28f, withAlpha(text, 0.7f));

        // Protection card (cut off by the page edge): title, then the switch row.
        // bar() reuses rect, so the card's bounds are kept in locals.
        float nextLeft = page.left + pw * 0.05f;
        float nextRight = page.right - pw * 0.05f;
        float nextTop = cardBottom + ph * 0.035f;
        rect.set(nextLeft, nextTop, nextRight, page.bottom + 12f * dp);
        paint.setColor(surface);
        canvas.drawRoundRect(rect, 9f * dp, 9f * dp, paint);
        float innerLeft = nextLeft + pw * 0.07f;
        float innerRight = nextRight - pw * 0.07f;
        float headerTop = nextTop + ph * 0.04f;
        bar(canvas, innerLeft, headerTop, pw * 0.3f, ph * 0.032f, text);
        float rowTop = headerTop + ph * 0.07f;
        float switchW = pw * 0.17f;
        float switchH = ph * 0.05f;
        float switchLeft = innerRight - switchW;
        bar(canvas, innerLeft, rowTop + (switchH - ph * 0.026f) / 2f, pw * 0.4f, ph * 0.026f, withAlpha(text, 0.85f));
        bar(canvas, switchLeft, rowTop, switchW, switchH, fill);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(switchLeft + switchW - switchH / 2f, rowTop + switchH / 2f, switchH * 0.38f, paint);
        bar(canvas, innerLeft, rowTop + switchH + ph * 0.025f, (innerRight - innerLeft) * 0.82f, ph * 0.02f, withAlpha(text2, 0.6f));

        canvas.restoreToCount(save);
        stroke(canvas, page, pageRadius, border);

        if (checked) {
            // Check badge in the top-right corner of the page.
            float r = 8.5f * dp;
            float cx = page.right - r * 0.55f;
            float cy = page.top + r * 0.55f;
            paint.setColor(accent);
            canvas.drawCircle(cx, cy, r + 1.5f * dp, paint);
            paint.setColor(contrastOn(accent));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f * dp);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            check.reset();
            check.moveTo(cx - r * 0.45f, cy + r * 0.02f);
            check.lineTo(cx - r * 0.1f, cy + r * 0.36f);
            check.lineTo(cx + r * 0.48f, cy - r * 0.3f);
            canvas.drawPath(check, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    /** Single line of text with its baseline at {@code baseline}, shrunk to fit {@code maxWidth}. */
    private void drawText(Canvas canvas, String value, float left, float baseline, float maxWidth,
            float size, android.graphics.Typeface typeface, int color) {
        textPaint.setTypeface(typeface);
        textPaint.setColor(color);
        textPaint.setTextSize(size);
        float width = textPaint.measureText(value);
        if (width > maxWidth) textPaint.setTextSize(size * maxWidth / width);
        canvas.drawText(value, left, baseline, textPaint);
    }

    /** The app name as in the hero: "FCM" in the Google colours, the rest in the text colour. */
    private void drawAppName(Canvas canvas, float left, float baseline, float maxWidth, float size) {
        textPaint.setTypeface(Fonts.title());
        textPaint.setTextSize(size);
        float width = textPaint.measureText(appName);
        if (width > maxWidth) textPaint.setTextSize(size * maxWidth / width);
        int mark = appName.indexOf(BrandText.MARK);
        if (mark < 0) {
            textPaint.setColor(text);
            canvas.drawText(appName, left, baseline, textPaint);
            return;
        }
        String prefix = appName.substring(0, mark);
        String suffix = appName.substring(mark + BrandText.MARK.length());
        textPaint.setColor(text);
        if (!prefix.isEmpty()) canvas.drawText(prefix, left, baseline, textPaint);
        float markLeft = left + textPaint.measureText(prefix);
        textPaint.setShader(BrandText.gradient(textPaint, "", markLeft));
        textPaint.setColor(0xFF000000);
        canvas.drawText(BrandText.MARK, markLeft, baseline, textPaint);
        textPaint.setShader(null);
        textPaint.setColor(text);
        canvas.drawText(suffix, markLeft + textPaint.measureText(BrandText.MARK), baseline, textPaint);
    }

    private void bar(Canvas canvas, float left, float top, float width, float height, int color) {
        paint.setColor(color);
        rect.set(left, top, left + width, top + height);
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint);
    }

    private void stroke(Canvas canvas, RectF bounds, float radius, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, dp * 0.75f));
        paint.setColor(color);
        canvas.drawRoundRect(bounds, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private static int over(int tint, int base) {
        float a = Color.alpha(tint) / 255f;
        return Color.rgb(
                Math.round(Color.red(tint) * a + Color.red(base) * (1f - a)),
                Math.round(Color.green(tint) * a + Color.green(base) * (1f - a)),
                Math.round(Color.blue(tint) * a + Color.blue(base) * (1f - a)));
    }

    private static int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(Color.alpha(color) * alpha),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    /** White or near-black, whichever reads better on {@code color}. */
    private static int contrastOn(int color) {
        double l = 0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color);
        return l > 160 ? 0xFF111111 : Color.WHITE;
    }
}
