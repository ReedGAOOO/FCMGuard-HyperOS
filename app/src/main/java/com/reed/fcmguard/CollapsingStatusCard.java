package com.reed.fcmguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;

/**
 * Status-card surface whose visible lower edge can collapse without changing the
 * measured layout height. Keeping layout geometry stable avoids scroll feedback,
 * while the custom outline keeps the rounded shadow attached to the moving edge.
 */
public final class CollapsingStatusCard extends LinearLayout {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF surfaceRect = new RectF();

    private float radiusPx;
    private float strokeWidthPx;
    private float visualBottomPx = Float.NaN;
    private int detailsChildIndex = -1;
    private View detailsPanel;
    private View currentValuePanel;
    private GlassBlur glassBlur;

    public CollapsingStatusCard(Context context) {
        super(context);
        init();
    }

    public CollapsingStatusCard(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CollapsingStatusCard(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setChildrenDrawingOrderEnabled(true);

        radiusPx = getResources().getDimension(R.dimen.status_card_radius);
        strokeWidthPx = getResources().getDisplayMetrics().density;

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(AppColors.get(getContext(), R.attr.pal_surface));

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidthPx);
        strokePaint.setColor(AppColors.get(getContext(), R.attr.pal_floating_border));

        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                int width = view.getWidth();
                int bottom = Math.round(resolveVisualBottom());
                if (width <= 0 || bottom <= 0) {
                    outline.setEmpty();
                    return;
                }
                outline.setRoundRect(0, 0, width, bottom, radiusPx);
            }
        });
    }

    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        detailsPanel = findViewById(R.id.statusDetailsPanel);
        currentValuePanel = findViewById(R.id.currentValuePanel);
        detailsChildIndex = detailsPanel == null ? -1 : indexOfChild(detailsPanel);
    }

    /**
     * Turns the card into frosted glass: the views behind it ({@code sources}, in
     * {@code root} coordinates) are blurred by the system and shown through a
     * translucent fill. Geometry, outline and the whitelist clip are unchanged.
     */
    void enableGlass(View root, float radiusPx, int baseColor, View... sources) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        releaseGlass();
        glassBlur = new GlassBlur(this, root, radiusPx, baseColor, sources);
        applySurfaceColors(true);
    }

    void disableGlass() {
        releaseGlass();
        applySurfaceColors(false);
    }

    private void releaseGlass() {
        if (glassBlur != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) glassBlur.release();
        glassBlur = null;
    }

    private void applySurfaceColors(boolean glass) {
        fillPaint.setColor(AppColors.get(getContext(), glass ? R.attr.pal_glass_surface : R.attr.pal_surface));
        strokePaint.setColor(AppColors.get(getContext(), glass ? R.attr.pal_glass_border : R.attr.pal_floating_border));
        invalidate();
    }

    /**
     * Sets the visible bottom edge in this view's local coordinates. The measured
     * height intentionally remains unchanged so ScrollView geometry never jumps.
     */
    public void setVisualBottom(float bottomPx) {
        float height = getHeight();
        float clamped = height > 0f
                ? Math.max(0f, Math.min(height, bottomPx))
                : Math.max(0f, bottomPx);
        if (!Float.isNaN(visualBottomPx) && Math.abs(visualBottomPx - clamped) < 0.5f) return;

        visualBottomPx = clamped;
        invalidate();
        invalidateOutline();
    }

    /** Visible lower edge in this view's coordinates (the card is drawn and clipped to it). */
    float visualBottom() {
        return resolveVisualBottom();
    }

    private float resolveVisualBottom() {
        if (Float.isNaN(visualBottomPx)) return getHeight();
        if (getHeight() <= 0) return visualBottomPx;
        return Math.min(getHeight(), visualBottomPx);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (Float.isNaN(visualBottomPx)) {
            visualBottomPx = h;
        } else if (visualBottomPx > h) {
            visualBottomPx = h;
        }
        invalidateOutline();
    }

    @Override protected void onDraw(Canvas canvas) {
        float bottom = resolveVisualBottom();
        if (glassBlur != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            glassBlur.draw(canvas, getWidth(), (int) Math.ceil(bottom), radiusPx);
        }
        if (getWidth() > 0 && bottom > 0f) {
            float halfStroke = strokeWidthPx / 2f;
            surfaceRect.set(
                    halfStroke,
                    halfStroke,
                    getWidth() - halfStroke,
                    Math.max(halfStroke, bottom - halfStroke)
            );
            canvas.drawRoundRect(surfaceRect, radiusPx, radiusPx, fillPaint);
            canvas.drawRoundRect(surfaceRect, radiusPx, radiusPx, strokePaint);
        }
        super.onDraw(canvas);
    }

    /**
     * Nothing inside the card may draw below its visible lower edge: once the tile rows
     * stack, the card ends above the laid-out bottom of its panels.
     */
    @Override protected void dispatchDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipRect(0f, 0f, getWidth(), resolveVisualBottom());
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }

    /**
     * Mask the translated whitelist panel at the detailed-status lower edge.
     *
     * Drawing order alone leaves the whitelist rendered underneath the rounded,
     * anti-aliased top edge of the white status plate. At the fully collapsed
     * position that can show up as a faint tonal arc/strip. Clipping the whitelist
     * to the area strictly below the status plate makes the plate behave like a
     * real physical cover: as soon as the whitelist enters it, that portion is no
     * longer rendered at all. The final aligned position is therefore pixel-clean.
     */
    @Override protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (child == currentValuePanel && detailsPanel != null) {
            int saveCount = canvas.save();
            canvas.clipRect(
                    0f,
                    detailsPanel.getBottom(),
                    getWidth(),
                    resolveVisualBottom()
            );
            boolean result = super.drawChild(canvas, child, drawingTime);
            canvas.restoreToCount(saveCount);
            return result;
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    /** Draw the white detailed-status panel last so the whitelist slides underneath it. */
    @Override protected int getChildDrawingOrder(int childCount, int drawingPosition) {
        if (detailsChildIndex < 0 || detailsChildIndex >= childCount) {
            return super.getChildDrawingOrder(childCount, drawingPosition);
        }
        if (drawingPosition == childCount - 1) return detailsChildIndex;

        int childIndex = drawingPosition;
        if (childIndex >= detailsChildIndex) childIndex++;
        return childIndex;
    }
}
