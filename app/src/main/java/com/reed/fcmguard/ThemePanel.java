package com.reed.fcmguard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Theme picker that grows out of the Theme capsule: the capsule's outline morphs into a
 * panel holding a miniature of the page in every theme, and shrinks back into the
 * capsule when dismissed. It lives in the activity window, so a theme switch crossfades
 * with the panel open and the page behind it already recoloured.
 */
final class ThemePanel {
    interface Listener {
        void onThemePicked(int index);
    }

    private static final long OPEN_MS = 480L;
    private static final long CLOSE_MS = 280L;
    private static final float SCRIM_ALPHA = 0.32f;
    /** Cards visible across the panel; the fraction shows the next card, inviting a swipe. */
    private static final float VISIBLE_CARDS = 2.25f;

    private final Activity activity;
    private final View anchor;
    private final Listener listener;
    private final float dp;

    private FrameLayout layer;
    private MorphCard card;
    private View content;
    private ThemePreviewView[] previews;
    private TextView[] names;
    private ValueAnimator animator;
    private SnapRow carousel;
    private int pendingScrollX = -1;
    private boolean open;

    ThemePanel(Activity activity, View anchor, Listener listener) {
        this.activity = activity;
        this.anchor = anchor;
        this.listener = listener;
        dp = activity.getResources().getDisplayMetrics().density;
    }

    boolean isOpen() {
        return open;
    }

    /** Horizontal position of the theme strip, kept across the page being recreated. */
    int scrollX() {
        return carousel != null ? carousel.getScrollX() : 0;
    }

    void restoreScrollX(int x) {
        pendingScrollX = x;
    }

    /** Opens the panel; without animation when restoring it after the page was recreated. */
    void show(boolean animate) {
        if (open) return;
        open = true;
        build();
        ViewGroup host = activity.findViewById(android.R.id.content);
        host.addView(layer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!animate || !animationsEnabled()) {
            setProgress(1f);
            return;
        }
        setProgress(0f);
        card.setVisibility(View.INVISIBLE);
        card.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                card.getViewTreeObserver().removeOnPreDrawListener(this);
                card.setStart(anchorRectInCard(), anchor.getHeight() / 2f);
                card.setVisibility(View.VISIBLE);
                run(1f, OPEN_MS, new PathInterpolator(0.05f, 0.7f, 0.1f, 1f), null);
                return true;
            }
        });
    }

    /** Shrinks the panel back into the capsule and removes it. */
    void dismiss() {
        if (!open || layer == null) return;
        open = false;
        final FrameLayout closing = layer;
        Runnable remove = () -> {
            ViewParent parent = closing.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(closing);
            if (layer == closing) layer = null;
        };
        if (!animationsEnabled()) {
            if (animator != null) animator.cancel();
            remove.run();
            return;
        }
        card.setStart(anchorRectInCard(), anchor.getHeight() / 2f);
        run(0f, CLOSE_MS, new PathInterpolator(0.3f, 0f, 0.8f, 0.15f), remove);
    }

    private void run(float to, long duration, android.animation.TimeInterpolator interpolator,
            Runnable end) {
        if (animator != null) animator.cancel();
        ValueAnimator next = ValueAnimator.ofFloat(card.progress, to);
        next.setDuration(duration);
        next.setInterpolator(interpolator);
        next.addUpdateListener(a -> setProgress((Float) a.getAnimatedValue()));
        if (end != null) {
            next.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override public void onAnimationCancel(Animator animation) {
                    cancelled = true;
                }

                @Override public void onAnimationEnd(Animator animation) {
                    if (!cancelled) end.run();
                }
            });
        }
        animator = next;
        next.start();
    }

    /** 0 = the capsule, 1 = the open panel. */
    private void setProgress(float p) {
        card.setMorph(p);
        float contentIn = clamp((p - 0.35f) / 0.65f);
        content.setAlpha(contentIn);
        content.setTranslationY((1f - contentIn) * 10f * dp);
        layer.getBackground().setAlpha(Math.round(255 * SCRIM_ALPHA * clamp(p)));
    }

    /** Marks {@code index} as the current theme without rebuilding the panel. */
    void select(int index) {
        if (previews == null) return;
        int selectedText = AppColors.get(activity, R.attr.pal_blue);
        int normalText = AppColors.get(activity, R.attr.pal_text_primary);
        for (int i = 0; i < previews.length; i++) {
            boolean selected = i == index;
            previews[i].setChecked(selected);
            names[i].setTextColor(selected ? selectedText : normalText);
            names[i].setTypeface(selected ? Fonts.title() : android.graphics.Typeface.DEFAULT);
            ((View) previews[i].getParent()).setSelected(selected);
        }
    }

    private void build() {
        Context context = activity;
        layer = new FrameLayout(context);
        layer.setBackground(new ColorDrawable(Color.BLACK));
        layer.setClickable(true);
        layer.setOnClickListener(v -> dismiss());
        layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        card = new MorphCard(context, 28f * dp);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(AppColors.get(context, R.attr.pal_surface));
        surface.setCornerRadius(28f * dp);
        surface.setStroke(Math.max(1, Math.round(dp)), AppColors.get(context, R.attr.pal_floating_border));
        card.setBackground(surface);
        card.setElevation(18f * dp);
        card.setClickable(true); // Taps on the panel itself do not dismiss it.

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(18), 0, dp(16));
        content = column;

        TextView title = new TextView(context);
        title.setText(R.string.theme_title);
        title.setTextColor(AppColors.get(context, R.attr.pal_text_primary));
        title.setTextSize(22f);
        title.setTypeface(Fonts.title());
        title.setPadding(dp(22), 0, dp(22), 0);
        column.addView(title);

        TextView hint = new TextView(context);
        hint.setText(R.string.theme_preview_hint);
        hint.setTextColor(AppColors.get(context, R.attr.pal_text_secondary));
        hint.setTextSize(12.5f);
        hint.setPadding(dp(22), dp(2), dp(22), dp(14));
        column.addView(hint);

        int[] insets = systemBarInsets();
        int side = dp(12);
        int cardWidth = Math.min(activity.getResources().getDisplayMetrics().widthPixels - 2 * side, dp(440));
        int edge = dp(18);
        int gap = dp(12);
        int cellWidth = Math.round((cardWidth - edge - 2 * gap) / VISIBLE_CARDS);

        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(edge, dp(4), edge, dp(2));
        carousel = new SnapRow(context, cellWidth + gap);
        carousel.setHorizontalScrollBarEnabled(false);
        carousel.setOverScrollMode(View.OVER_SCROLL_NEVER);
        carousel.setClipToPadding(false);
        carousel.addView(strip);
        column.addView(carousel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int count = Palettes.IDS.length;
        previews = new ThemePreviewView[count];
        names = new TextView[count];
        for (int i = 0; i < count; i++) {
            LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(
                    cellWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) cellParams.leftMargin = gap;
            strip.addView(makeCell(i), cellParams);
        }
        int current = Palettes.current(activity);
        select(current);
        // Restored scroll (after a theme switch) keeps the strip where it was, so the
        // crossfade lines up; a fresh open shows the current theme second from the left.
        final int scrollX = pendingScrollX >= 0 ? pendingScrollX : Math.max(0, current - 1) * (cellWidth + gap);
        pendingScrollX = -1;
        carousel.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                carousel.getViewTreeObserver().removeOnPreDrawListener(this);
                carousel.scrollTo(Math.min(scrollX, carousel.maxScroll()), 0);
                return true;
            }
        });

        card.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cardParams.topMargin = insets[0] + dp(6);
        cardParams.bottomMargin = insets[1] + dp(12);
        layer.addView(card, cardParams);
    }

    private View makeCell(int index) {
        Context context = activity;
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setContentDescription(activity.getString(Palettes.NAMES[index]));
        cell.setOnClickListener(v -> listener.onThemePicked(index));
        cell.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(110L).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(220L)
                        .setInterpolator(new PathInterpolator(0.2f, 1.6f, 0.4f, 1f)).start();
            }
            return false;
        });

        // The theme's colours, in the current light/dark mode: AppTheme + its overlay.
        Resources.Theme theme = activity.getResources().newTheme();
        theme.applyStyle(R.style.AppTheme, true);
        theme.applyStyle(Palettes.STYLES[index], true);
        ThemePreviewView preview = new ThemePreviewView(context);
        preview.setTheme(theme);
        cell.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        previews[index] = preview;

        TextView name = new TextView(context);
        name.setText(Palettes.NAMES[index]);
        name.setTextSize(13.5f);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, dp(7), 0, 0);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cell.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        names[index] = name;
        return cell;
    }

    /** The capsule's bounds in the card's coordinates; a sliver at the top if it is hidden. */
    private RectF anchorRectInCard() {
        int[] cardLocation = new int[2];
        card.getLocationInWindow(cardLocation);
        if (anchor.isAttachedToWindow() && anchor.isShown() && visibleAlpha(anchor) > 0.3f) {
            int[] anchorLocation = new int[2];
            anchor.getLocationInWindow(anchorLocation);
            float left = anchorLocation[0] - cardLocation[0];
            float top = anchorLocation[1] - cardLocation[1];
            return new RectF(left, top, left + anchor.getWidth(), top + anchor.getHeight());
        }
        float w = card.getWidth();
        return new RectF(w * 0.3f, 0f, w * 0.7f, dp(36));
    }

    private static float visibleAlpha(View view) {
        float alpha = 1f;
        View current = view;
        while (current != null) {
            alpha *= current.getAlpha();
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return alpha;
    }

    /** Status and navigation bar heights: the page is drawn edge to edge. */
    @SuppressWarnings("deprecation")
    private int[] systemBarInsets() {
        View decor = activity.getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            WindowInsets insets = decor.getRootWindowInsets();
            if (insets != null) {
                return new int[] {insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetBottom()};
            }
        }
        return new int[] {systemDimen("status_bar_height", 24), systemDimen("navigation_bar_height", 0)};
    }

    private int systemDimen(String name, int fallbackDp) {
        Resources res = activity.getResources();
        @SuppressWarnings("DiscouragedApi")
        int id = res.getIdentifier(name, "dimen", "android");
        return id != 0 ? res.getDimensionPixelSize(id) : dp(fallbackDp);
    }

    private boolean animationsEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return ValueAnimator.areAnimatorsEnabled();
        return true;
    }

    private int dp(int value) {
        return Math.round(value * dp);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /**
     * Horizontal strip that settles with a card at its left edge: a fling moves one card
     * (two when fast), a slow drag settles on the nearest.
     */
    private static final class SnapRow extends android.widget.HorizontalScrollView {
        private final int step;
        private boolean flung;

        SnapRow(Context context, int step) {
            super(context);
            this.step = step;
        }

        int maxScroll() {
            View child = getChildAt(0);
            return child == null ? 0 : Math.max(0, child.getWidth() - getWidth());
        }

        @Override public void fling(int velocityX) {
            flung = true;
            float position = getScrollX() / (float) step;
            int skip = Math.abs(velocityX) > 6000 * getResources().getDisplayMetrics().density / 2.75f ? 2 : 1;
            int target = velocityX > 0
                    ? (int) Math.floor(position) + skip
                    : (int) Math.ceil(position) - skip;
            settle(target);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) flung = false;
            boolean handled = super.onTouchEvent(event);
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && !flung) {
                settle(Math.round(getScrollX() / (float) step));
            }
            return handled;
        }

        private void settle(int index) {
            int x = Math.max(0, Math.min(index * step, maxScroll()));
            if (x != getScrollX()) smoothScrollTo(x, 0);
        }
    }

    /** Card whose clip and shadow outline morph from the capsule's bounds to its own. */
    private static final class MorphCard extends FrameLayout {
        private final RectF start = new RectF();
        private final float endRadius;
        private float startRadius;
        float progress = 1f;

        MorphCard(Context context, float endRadius) {
            super(context);
            this.endRadius = endRadius;
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    float p = progress;
                    float w = view.getWidth();
                    float h = view.getHeight();
                    if (start.isEmpty()) start.set(0f, 0f, w, h);
                    outline.setRoundRect(
                            Math.round(lerp(start.left, 0f, p)),
                            Math.round(lerp(start.top, 0f, p)),
                            Math.round(lerp(start.right, w, p)),
                            Math.round(lerp(start.bottom, h, p)),
                            lerp(startRadius, endRadius, p));
                }
            });
        }

        void setStart(RectF rect, float radius) {
            start.set(rect);
            startRadius = radius;
            invalidateOutline();
        }

        void setMorph(float p) {
            progress = p;
            invalidateOutline();
        }

        private static float lerp(float from, float to, float t) {
            return from + (to - from) * t;
        }
    }
}
