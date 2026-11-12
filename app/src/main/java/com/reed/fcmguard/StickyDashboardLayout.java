package com.reed.fcmguard;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ScrollView;

/**
 * Dashboard root with a fixed hero and a scroll-linked status card.
 *
 * The outer status card moves first and docks inside the hero. The whitelist panel
 * deliberately lags behind on a geometry-aware smootherstep curve, then slides
 * underneath the detailed four-line status panel. The detailed status panel has
 * its own white rounded surface and is drawn above the whitelist panel, so the
 * status text remains readable while the whitelist disappears beneath it.
 *
 * After that, each column of status tiles stacks behind its first tile (StatusTileColumns),
 * so the docked card keeps only its headline, the two most important tiles and their
 * peeking edges.
 *
 * All motion is driven directly by ScrollView scroll events while the Activity is
 * on screen; there are no timers, alarms, background work, or extra wakeups.
 *
 * With glass enabled ({@link #setGlassMaterial(boolean)}), the header band and the status
 * card blur whatever scrolls behind them using the system GPU blur (see {@link GlassBlur});
 * otherwise every surface stays opaque.
 */
public final class StickyDashboardLayout extends FrameLayout {
    private View stickyHeader;
    private GlassBackdropView headerBackdrop;
    private View contentRoot;
    private View heroCard;
    private CollapsingStatusCard statusCard;
    private View statusDetailsPanel;
    private View currentValuePanel;
    private StatusTileColumns tileStack;
    private ScrollView scrollView;

    private int stickyBaseLeft;
    private int stickyBaseTop;
    private int stickyBaseRight;
    private int stickyBaseBottom;
    private int extraGapPx;
    /** Whether glass surfaces are currently shown; the inflated layout starts opaque. */
    private boolean glassApplied;
    /** Receiver of the current gesture when it started on the visible status card. */
    private View cardGestureTarget;
    private final float[] touchOffset = new float[2];

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
        headerBackdrop = findViewById(R.id.headerBackdrop);
        if (headerBackdrop != null) {
            headerBackdrop.showSolid(AppColors.get(getContext(), R.attr.pal_bg), glassFadePx());
        }
        contentRoot = findViewById(R.id.contentRoot);
        heroCard = findViewById(R.id.heroCard);
        statusCard = findViewById(R.id.statusCard);
        statusDetailsPanel = findViewById(R.id.statusDetailsPanel);
        currentValuePanel = findViewById(R.id.currentValuePanel);
        tileStack = findViewById(R.id.statusTileColumns);
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
                    stickyBaseTop + top,
                    stickyBaseRight,
                    stickyBaseBottom
            );
        }

        requestLayout();
        return super.onApplyWindowInsets(insets);
    }

    /**
     * The header band covers everything down to the bottom edge of the docked status card
     * and then fades out, so content scrolling up dissolves just before it slides under
     * the card instead of being cut off. While the card is expanded or still collapsing,
     * the fade zone is hidden behind it.
     */
    private void syncHeaderBand() {
        if (headerBackdrop == null || heroCard == null || statusCard == null ||
                statusDetailsPanel == null || currentValuePanel == null) {
            return;
        }
        ViewGroup.LayoutParams rawParams = statusCard.getLayoutParams();
        int sideInset = rawParams instanceof ViewGroup.MarginLayoutParams
                ? Math.max(0, ((ViewGroup.MarginLayoutParams) rawParams).leftMargin) : 0;
        int dockedTop = heroCard.getTop() + sideInset;
        int bottomGap = Math.max(0, statusCard.getHeight() - currentValuePanel.getBottom());
        int stackedAway = tileStack != null ? tileStack.collapsibleHeight() : 0;
        int dockedVisualBottom = Math.min(statusCard.getHeight(),
                statusDetailsPanel.getBottom() + bottomGap) - stackedAway;
        int dockedBottom = dockedTop + dockedVisualBottom;
        headerBackdrop.setBandHeight(Math.max(heroCard.getBottom(), dockedBottom) + glassFadePx());
    }

    /**
     * Touches that start on the visible status card go to the card first (tiles, the
     * Protect now button, stack swipes); what the card leaves goes straight to the page
     * scroll. They never reach the hero capsules, which stay drawn (blurred) under a
     * docked card, and dragging on the card still scrolls the page as before.
     */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cardGestureTarget = null;
            if (statusCard != null && scrollView != null && onVisibleStatusCard(event)) {
                if (dispatchTo(statusCard, event)) cardGestureTarget = statusCard;
                else if (dispatchTo(scrollView, event)) cardGestureTarget = scrollView;
                else cardGestureTarget = this; // Nothing wants it: swallow the gesture.
                return true;
            }
            return super.dispatchTouchEvent(event);
        }
        if (cardGestureTarget == null) return super.dispatchTouchEvent(event);
        View target = cardGestureTarget;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            cardGestureTarget = null;
        }
        if (target != this) dispatchTo(target, event);
        return true;
    }

    private boolean onVisibleStatusCard(MotionEvent event) {
        if (statusCard.getVisibility() != VISIBLE || statusCard.getWidth() <= 0) return false;
        offsetOf(statusCard, touchOffset);
        float x = event.getX() - touchOffset[0];
        float y = event.getY() - touchOffset[1];
        return x >= 0f && x < statusCard.getWidth() && y >= 0f && y < statusCard.visualBottom();
    }

    /** Delivers {@code event} to a descendant in that view's own coordinates. */
    private boolean dispatchTo(View child, MotionEvent event) {
        offsetOf(child, touchOffset);
        MotionEvent local = MotionEvent.obtain(event);
        local.offsetLocation(-touchOffset[0], -touchOffset[1]);
        boolean handled = child.dispatchTouchEvent(local);
        local.recycle();
        return handled;
    }

    /** Top-left of {@code view} in this layout's coordinates (positions, translations, scrolls). */
    private void offsetOf(View view, float[] out) {
        float x = 0f;
        float y = 0f;
        View current = view;
        while (current != null && current != this) {
            x += current.getLeft() + current.getTranslationX();
            y += current.getTop() + current.getTranslationY();
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
            if (current != this) {
                x -= current.getScrollX();
                y -= current.getScrollY();
            }
        }
        out[0] = x;
        out[1] = y;
    }

    /**
     * Window-space bottom of everything the glass setting repaints: the header band and
     * the floating hero and status cards.
     */
    int glassRegionBottomInWindow() {
        int bottom = headerBackdrop != null ? headerBackdrop.bandBottom() : 0;
        if (stickyHeader != null) {
            bottom = Math.max(bottom, Math.round(stickyHeader.getY()) + stickyHeader.getHeight());
        }
        int[] location = new int[2];
        getLocationInWindow(location);
        return location[1] + bottom;
    }

    /**
     * Applies or removes HyperOS glass. Returns whether glass is active afterwards; when
     * the system blur cannot be enabled the dashboard keeps its opaque surfaces.
     */
    public boolean setGlassMaterial(boolean wanted) {
        if (headerBackdrop == null || heroCard == null || statusCard == null) return false;
        boolean glass = wanted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                isHardwareAccelerated() && contentRoot != null;
        if (glass == glassApplied) return glass;

        if (glass) {
            float radius = glassBlurRadiusPx();
            int base = AppColors.get(getContext(), R.attr.pal_bg);
            headerBackdrop.enableGlass(this, radius, base,
                    AppColors.get(getContext(), R.attr.pal_glass_header), glassFadePx(), contentRoot);
            statusCard.enableGlass(this, radius, base, contentRoot, heroCard);
        } else {
            headerBackdrop.showSolid(AppColors.get(getContext(), R.attr.pal_bg), glassFadePx());
            statusCard.disableGlass();
        }

        heroCard.setBackgroundResource(glass ? R.drawable.hero_glass_bg : R.drawable.hero_bg);
        // The shadow stays in glass mode: the blurred backdrop is drawn on an opaque base,
        // so the shadow cannot show through, and without it the card's lower edge
        // disappears against white content in light mode.
        if (statusDetailsPanel != null) {
            // The whitelist is already clipped at the lower edge of this panel, so the
            // opaque plate is only needed for the solid surface.
            statusDetailsPanel.setBackgroundResource(glass ? 0 : R.drawable.status_header_bg);
        }
        if (currentValuePanel != null) {
            currentValuePanel.setBackgroundResource(glass ? R.drawable.glass_tonal_bg : R.drawable.tonal_bg);
        }

        glassApplied = glass;
        syncCollapsingStatusCard();
        return glass;
    }

    /** Height of the gradient over which content fades out under the header band. */
    private int glassFadePx() {
        return Math.round(40f * getResources().getDisplayMetrics().density);
    }

    private float glassBlurRadiusPx() {
        return 30f * getResources().getDisplayMetrics().density;
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        syncScrollableTopOffset();
        syncHeaderBand();
        // Row positions may have changed: recompute the stack transforms from scratch.
        if (tileStack != null) tileStack.reapply();
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
     * card docks plus roughly one cover-distance of additional scroll. A quintic
     * smootherstep gives zero velocity at both ends, avoiding a visible snap.
     *
     * The final translation is defined by the two lower edges: the whitelist panel
     * stops exactly when its bottom reaches the bottom of the detailed status panel.
     * Because both panels are the same width and use the same 14dp corner radius,
     * the whitelist is then fully hidden under the white detailed-status surface.
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
        if (glassApplied) syncGlass();

        if (statusDetailsPanel == null || currentValuePanel == null) return;

        int coverDistance = Math.max(
                0,
                currentValuePanel.getBottom() - statusDetailsPanel.getBottom()
        );
        float panelProgress = computePanelProgress(scrollY, collapseDistance, coverDistance);
        float panelTranslation = -coverDistance * panelProgress;

        if (currentValuePanel.getTranslationY() != panelTranslation) {
            currentValuePanel.setTranslationY(panelTranslation);
        }

        // Third stage: once the whitelist is (almost) hidden, each tile column stacks behind
        // its first tile like stacked notifications.
        float stackShrink = 0f;
        if (tileStack != null) {
            int stackDistance = tileStack.collapsibleHeight();
            float start = collapseDistance + coverDistance * 0.95f;
            float end = start + stackDistance * 1.2f;
            float stackProgress = stackDistance > 0 && end > start
                    ? smootherStep(clamp01((scrollY - start) / (end - start)))
                    : 0f;
            tileStack.setStackProgress(stackProgress);
            stackShrink = tileStack.currentShrink();
        }

        // Keep the card's rounded lower edge attached to the translated value panel, and
        // then to the shrinking tile stack.
        int bottomGap = Math.max(0, statusCard.getHeight() - currentValuePanel.getBottom());
        float visualBottom = currentValuePanel.getBottom() + panelTranslation + bottomGap - stackShrink;
        statusCard.setVisualBottom(visualBottom);
    }

    /**
     * Glass backdrops show the content behind them, so they are re-drawn whenever that
     * content moves. The hero stays fully drawn: as the card docks over it, the card's
     * frosted surface blurs the hero text instead of the text fading out.
     */
    private void syncGlass() {
        headerBackdrop.invalidate();
        statusCard.invalidate();
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
