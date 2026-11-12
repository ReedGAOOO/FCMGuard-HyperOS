package com.reed.fcmguard;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * Rows of status tiles that can collapse into a stack, like stacked notifications: as the
 * stack progress goes from 0 to 1, every row after the first slides up behind the row
 * above it, shrinks slightly and fades, until only thin edges peek out below the first row.
 *
 * Only RenderNode properties (translation, scale, alpha) change, so stacking costs no
 * layout pass. The first row is drawn last and receives touches first. Tiles must have
 * opaque backgrounds so the rows behind are hidden by the row in front.
 */
public final class StatusTileStack extends LinearLayout {
    private static final float SCALE_STEP = 0.05f;
    private static final float ALPHA_STEP = 0.25f;

    private final float peekPx;
    private float progress = -1f;

    public StatusTileStack(Context context) {
        this(context, null);
    }

    public StatusTileStack(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StatusTileStack(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        setChildrenDrawingOrderEnabled(true);
        peekPx = 5f * getResources().getDisplayMetrics().density;
    }

    /** First row on top: draw rows in reverse order (touch dispatch follows this order). */
    @Override protected int getChildDrawingOrder(int childCount, int drawingPosition) {
        return childCount - 1 - drawingPosition;
    }

    /** How much shorter the stack is when fully stacked than when laid out. */
    int collapsibleHeight() {
        int count = getChildCount();
        if (count < 2) return 0;
        View first = getChildAt(0);
        View last = getChildAt(count - 1);
        float stackedBottom = first.getBottom() + peekPx * (count - 1);
        return Math.max(0, Math.round(last.getBottom() - stackedBottom));
    }

    /** How much shorter the stack currently looks than its laid-out height. */
    float currentShrink() {
        int count = getChildCount();
        if (count < 2) return 0f;
        return Math.max(0f, -getChildAt(count - 1).getTranslationY());
    }

    /** 0 = rows laid out normally, 1 = rows stacked behind the first one. */
    void setStackProgress(float value) {
        float p = Math.max(0f, Math.min(1f, value));
        if (p == progress) return;
        progress = p;

        int count = getChildCount();
        if (count < 2) return;
        View first = getChildAt(0);
        // Text of the rows going behind fades out quickly; only their edges remain.
        float contentAlpha = Math.max(0f, 1f - p * 1.6f);
        for (int i = 1; i < count; i++) {
            View row = getChildAt(i);
            float stackedBottom = first.getBottom() + peekPx * i;
            float scale = 1f - SCALE_STEP * i * p;
            row.setPivotX(row.getWidth() / 2f);
            row.setPivotY(row.getHeight());
            row.setScaleX(scale);
            row.setScaleY(scale);
            row.setTranslationY((stackedBottom - row.getBottom()) * p);
            row.setAlpha(1f - ALPHA_STEP * i * p);
            setTileContentAlpha(row, contentAlpha);
        }
    }

    /** Re-applies the current progress after the rows were laid out again. */
    void reapply() {
        float p = Math.max(0f, progress);
        progress = -1f;
        setStackProgress(p);
    }

    private static void setTileContentAlpha(View row, float alpha) {
        if (!(row instanceof ViewGroup)) return;
        ViewGroup tiles = (ViewGroup) row;
        for (int t = 0; t < tiles.getChildCount(); t++) {
            View tile = tiles.getChildAt(t);
            if (!(tile instanceof ViewGroup)) continue;
            ViewGroup content = (ViewGroup) tile;
            for (int c = 0; c < content.getChildCount(); c++) {
                View child = content.getChildAt(c);
                if (child.getAlpha() != alpha) child.setAlpha(alpha);
            }
        }
    }
}
