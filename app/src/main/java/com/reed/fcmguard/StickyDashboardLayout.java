package com.reed.fcmguard;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/**
 * Root dashboard container for a true fixed hero/status overlay above scrolling cards.
 *
 * The header is measured independently from the ScrollView. The scroll content receives
 * a dynamic top spacer equal to the real measured sticky-header height, so it starts
 * below the overlay but can subsequently scroll underneath it. This avoids hard-coded
 * offsets and stays correct across translations, font metrics, cutouts, and status bars.
 */
public final class StickyDashboardLayout extends FrameLayout {
    private View stickyHeader;
    private View contentRoot;

    private int stickyBaseLeft;
    private int stickyBaseTop;
    private int stickyBaseRight;
    private int stickyBaseBottom;
    private int extraGapPx;

    public StickyDashboardLayout(Context context) {
        super(context);
    }

    public StickyDashboardLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public StickyDashboardLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        stickyHeader = findViewById(R.id.stickyHeader);
        contentRoot = findViewById(R.id.contentRoot);
        extraGapPx = dp(10);

        if (stickyHeader != null) {
            stickyBaseLeft = stickyHeader.getPaddingLeft();
            stickyBaseTop = stickyHeader.getPaddingTop();
            stickyBaseRight = stickyHeader.getPaddingRight();
            stickyBaseBottom = stickyHeader.getPaddingBottom();
        }
    }

    @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (stickyHeader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int top = Math.max(insets.getSystemWindowInsetTop(), insets.getStableInsetTop());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) top = Math.max(top, cutout.getSafeInsetTop());
            }

            stickyHeader.setPadding(
                    stickyBaseLeft,
                    stickyBaseTop + top + dp(4),
                    stickyBaseRight,
                    stickyBaseBottom
            );
        }

        requestLayout();
        return super.onApplyWindowInsets(insets);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        syncScrollableTopOffset();
    }

    private void syncScrollableTopOffset() {
        if (stickyHeader == null || contentRoot == null) return;
        int desiredTop = stickyHeader.getHeight() + extraGapPx;
        if (desiredTop <= 0 || contentRoot.getPaddingTop() == desiredTop) return;

        contentRoot.setPadding(
                contentRoot.getPaddingLeft(),
                desiredTop,
                contentRoot.getPaddingRight(),
                contentRoot.getPaddingBottom()
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
