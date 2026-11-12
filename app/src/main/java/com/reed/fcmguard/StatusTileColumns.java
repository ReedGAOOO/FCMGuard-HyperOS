package com.reed.fcmguard;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Side-by-side columns of status tiles, each column its own {@link StatusTileStack}, so
 * the columns stack together while the card collapses but are swiped and cycled
 * independently.
 *
 * While laid out, tiles at the same index in each column share one height, so the
 * expanded card still reads as rows of two.
 */
public final class StatusTileColumns extends LinearLayout {
    private final List<StatusTileStack> stacks = new ArrayList<>();

    public StatusTileColumns(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
    }

    public StatusTileColumns(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
    }

    public StatusTileColumns(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(HORIZONTAL);
    }

    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        stacks.clear();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof StatusTileStack) stacks.add((StatusTileStack) child);
        }
    }

    /** Measures tiles naturally, then gives tiles at the same index the taller height. */
    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int rows = 0;
        for (StatusTileStack stack : stacks) {
            rows = Math.max(rows, stack.getChildCount());
            for (int i = 0; i < stack.getChildCount(); i++) {
                setTileHeight(stack.getChildAt(i), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (stacks.size() < 2) return;

        boolean changed = false;
        for (int i = 0; i < rows; i++) {
            int tallest = 0;
            for (StatusTileStack stack : stacks) {
                if (i < stack.getChildCount()) {
                    tallest = Math.max(tallest, stack.getChildAt(i).getMeasuredHeight());
                }
            }
            for (StatusTileStack stack : stacks) {
                if (i < stack.getChildCount()) {
                    View tile = stack.getChildAt(i);
                    if (tile.getMeasuredHeight() != tallest) {
                        setTileHeight(tile, tallest);
                        changed = true;
                    }
                }
            }
        }
        if (changed) super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private static void setTileHeight(View tile, int height) {
        ViewGroup.LayoutParams params = tile.getLayoutParams();
        if (params != null && params.height != height) params.height = height;
    }

    /** How much shorter the columns are when fully stacked. */
    int collapsibleHeight() {
        int height = 0;
        for (StatusTileStack stack : stacks) height = Math.max(height, stack.collapsibleHeight());
        return height;
    }

    float currentShrink() {
        float shrink = 0f;
        for (StatusTileStack stack : stacks) shrink = Math.max(shrink, stack.currentShrink());
        return shrink;
    }

    void setStackProgress(float progress) {
        for (StatusTileStack stack : stacks) stack.setStackProgress(progress);
    }

    void reapply() {
        for (StatusTileStack stack : stacks) stack.reapply();
    }
}
