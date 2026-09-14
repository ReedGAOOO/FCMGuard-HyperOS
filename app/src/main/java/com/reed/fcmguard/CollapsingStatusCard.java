package com.reed.fcmguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
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
        fillPaint.setColor(getResources().getColor(R.color.surface));

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidthPx);
        strokePaint.setColor(getResources().getColor(R.color.floating_border));

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
        View details = findViewById(R.id.statusDetailsPanel);
        detailsChildIndex = details == null ? -1 : indexOfChild(details);
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
