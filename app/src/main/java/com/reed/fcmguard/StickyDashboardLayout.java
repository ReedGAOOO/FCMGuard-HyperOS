package com.reed.fcmguard;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ScrollView;

/**
 * Dashboard root with a fixed hero and a scroll-linked status card.
 *
 * Expanded: the white status card overlaps the lower half of the hero.
 * Scrolling: the status card follows the scroll upward until it docks inside the
 * hero with equal top / left / right inset. After docking, it stays fixed while
 * the lower cards keep scrolling underneath it.
 *
 * All motion is driven directly by ScrollView scroll events while the Activity is
 * on screen; there are no timers, alarms, background work, or extra wakeups.
 */
public final class StickyDashboardLayout extends FrameLayout {
    private View stickyHeader;
    private View contentRoot;
    private View heroCard;
    private View statusCard;
    private ScrollView scrollView;

    private int stickyBaseLeft;
    private int stickyBaseTop;
    private int stickyBaseRight;
    private int stickyBaseBottom;
    private int extraGapPx;

    private final ViewTreeObserver.OnScrollChangedListener scrollChangedListener =
            this::syncCollapsingStatusCard;

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
        heroCard = findViewById(R.id.heroCard);
        statusCard = findViewById(R.id.statusCard);
        scrollView = findViewById(R.id.scroll);
        extraGapPx = getResources().getDimensionPixelSize(R.dimen.card_gap);

        if (stickyHeader != null) {
            stickyBaseLeft = stickyHeader.getPaddingLeft();
            stickyBaseTop = stickyHeader.getPaddingTop();
            stickyBaseRight = stickyHeader.getPaddingRight();
            stickyBaseBottom = stickyHeader.getPaddingBottom();
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (scrollView != null) {
            ViewTreeObserver observer = scrollView.getViewTreeObserver();
            if (observer.isAlive()) observer.addOnScrollChangedListener(scrollChangedListener);
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (scrollView != null) {
            ViewTreeObserver observer = scrollView.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnScrollChangedListener(scrollChangedListener);
        }
        super.onDetachedFromWindow();
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
        syncCollapsingStatusCard();
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

    /**
     * Geometry is derived from the laid-out views. The status card's side inset is
     * reused as the docked top inset, preserving equal top/left/right spacing.
     */
    private void syncCollapsingStatusCard() {
        if (scrollView == null || heroCard == null || statusCard == null) return;
        if (heroCard.getHeight() <= 0 || statusCard.getHeight() <= 0) return;

        ViewGroup.LayoutParams rawParams = statusCard.getLayoutParams();
        if (!(rawParams instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) rawParams;

        int sideInset = Math.max(0, margins.leftMargin);
        int expandedTop = statusCard.getTop();
        int collapsedTop = heroCard.getTop() + sideInset;
        int collapseDistance = Math.max(0, expandedTop - collapsedTop);
        int scrollY = Math.max(0, scrollView.getScrollY());
        int travelled = Math.min(scrollY, collapseDistance);
        float translation = -travelled;

        if (statusCard.getTranslationY() != translation) {
            statusCard.setTranslationY(translation);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
