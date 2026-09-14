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
 * The outer status card moves first and docks inside the hero. The whitelist panel
 * deliberately lags behind on a geometry-aware smootherstep curve, then finishes
 * collapsing after the outer card has docked. Its lower edge and the status-card
 * surface move together, while a white Status/Protected cap stays readable above
 * the sliding panel.
 *
 * All motion is driven directly by ScrollView scroll events while the Activity is
 * on screen; there are no timers, alarms, background work, or extra wakeups.
 */
public final class StickyDashboardLayout extends FrameLayout {
    private View stickyHeader;
    private View contentRoot;
    private View heroCard;
    private CollapsingStatusCard statusCard;
    private View statusHeaderPanel;
    private View statusText;
    private View currentValuePanel;
    private ScrollView scrollView;

    private int stickyBaseLeft;
    private int stickyBaseTop;
    private int stickyBaseRight;
    private int stickyBaseBottom;
    private int extraGapPx;
    private int headerOverlapPx;

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
        statusHeaderPanel = findViewById(R.id.statusHeaderPanel);
        statusText = findViewById(R.id.statusText);
        currentValuePanel = findViewById(R.id.currentValuePanel);
        scrollView = findViewById(R.id.scroll);
        extraGapPx = getResources().getDimensionPixelSize(R.dimen.card_gap);
        headerOverlapPx = getResources().getDimensionPixelSize(R.dimen.status_header_overlap);

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
                    stickyBaseTop + top,
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
     * The outer card uses direct scroll travel so it feels attached to the finger.
     * Internal collapse starts only after 38% of that travel and finishes after the
     * card docks plus roughly one panel-height of additional scroll. A quintic
     * smootherstep gives zero velocity at both ends, avoiding a visible snap.
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

        if (statusText == null || currentValuePanel == null) return;

        int collapsedPanelTop = statusText.getTop();
        if (statusHeaderPanel != null) {
            collapsedPanelTop = Math.max(0, statusHeaderPanel.getBottom() - headerOverlapPx);
        }

        int coverDistance = Math.max(0, currentValuePanel.getTop() - collapsedPanelTop);
        float panelProgress = computePanelProgress(scrollY, collapseDistance, coverDistance);
        float panelTranslation = -coverDistance * panelProgress;

        if (currentValuePanel.getTranslationY() != panelTranslation) {
            currentValuePanel.setTranslationY(panelTranslation);
        }

        // Keep the card's rounded lower edge attached to the translated value panel.
        int bottomGap = Math.max(0, statusCard.getHeight() - currentValuePanel.getBottom());
        float visualBottom = currentValuePanel.getBottom() + panelTranslation + bottomGap;
        statusCard.setVisualBottom(visualBottom);
    }

    private static float computePanelProgress(int scrollY, int collapseDistance, int coverDistance) {
        if (coverDistance <= 0) return 0f;

        float start = collapseDistance * 0.38f;
        float end = collapseDistance + coverDistance * 1.10f;
        if (end <= start) return scrollY >= end ? 1f : 0f;

        float t = clamp01((scrollY - start) / (end - start));
        return smootherStep(t);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float smootherStep(float t) {
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }
}
