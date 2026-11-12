package com.reed.fcmguard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;

/**
 * One column of status tiles that collapses into a stack, like stacked notifications.
 * Each column is independent (see {@link StatusTileColumns}).
 *
 * Stacking: as the stack progress goes from 0 to 1, every tile slides into its slot in the
 * stack: the front slot at the first tile's position, the others behind it, slightly
 * smaller and fainter, with only a thin edge peeking out below.
 *
 * Swiping: once fully stacked, the front tile can be dragged in any direction. The drag
 * follows a rubber-band curve, so it takes a deliberate fling (or a long drag) to throw the
 * tile out; otherwise it springs back. A thrown-out tile goes to the back of the stack
 * while the tiles behind it move forward. When the stack unfolds completely, the original
 * order (most severe first) is restored.
 *
 * Only RenderNode properties change while stacking, dragging and animating: no layout pass.
 * Tiles must have opaque backgrounds so a tile hides the tiles behind it.
 */
public final class StatusTileStack extends LinearLayout {
    private static final float SCALE_STEP = 0.05f;
    private static final float ALPHA_STEP = 0.25f;
    /** iOS-style rubber-band constant. */
    private static final float RUBBER_BAND = 0.55f;
    private static final float MAX_ROTATION_DEG = 9f;
    private static final float FLING_DP_PER_S = 1000f;
    private static final float DISMISS_DISTANCE_FRACTION = 0.38f;

    private final float peekPx;
    private final float density;
    private final int touchSlop;
    private final TimeInterpolator spring = new SpringInterpolator();
    private final TimeInterpolator decelerate = new DecelerateInterpolator(1.6f);

    private float progress = -1f;
    /** Fractional stack slot of each child (0 = front); identity until rows are swiped. */
    private float[] slot = new float[0];
    /** Extra per-child alpha used while a thrown row re-enters at the back. */
    private float[] enterAlpha = new float[0];
    private int[] drawOrder = new int[0];

    // Drag state of the front row.
    private int dragChild = -1;
    private float dragX;
    private float dragY;
    private boolean tracking;
    private boolean dragging;
    private boolean pastThreshold;
    private float downX;
    private float downY;
    private VelocityTracker velocityTracker;
    private ValueAnimator gestureAnimator;
    private ValueAnimator slotAnimator;

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
        density = getResources().getDisplayMetrics().density;
        peekPx = 5f * density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    // ---------------------------------------------------------------- stacking

    /** How much shorter the stack is when fully stacked than when laid out. */
    int collapsibleHeight() {
        int count = getChildCount();
        if (count < 2) return 0;
        float stackedBottom = getChildAt(0).getBottom() + peekPx * (count - 1);
        return Math.max(0, Math.round(getChildAt(count - 1).getBottom() - stackedBottom));
    }

    /** How much shorter the stack currently looks (ignores a row being dragged or thrown). */
    float currentShrink() {
        return collapsibleHeight() * Math.max(0f, progress);
    }

    /** 0 = rows laid out normally, 1 = rows stacked behind the front row. */
    void setStackProgress(float value) {
        float p = Math.max(0f, Math.min(1f, value));
        if (p == progress) return;
        progress = p;
        ensureState();
        if (p < 0.999f) cancelGesture();
        if (p == 0f) resetSlots();
        applyTransforms();
    }

    /** Re-applies the current state after the rows were laid out again. */
    void reapply() {
        ensureState();
        applyTransforms();
    }

    private boolean fullyStacked() {
        return progress >= 0.999f && getChildCount() >= 2;
    }

    private void ensureState() {
        int count = getChildCount();
        if (slot.length == count) return;
        slot = new float[count];
        enterAlpha = new float[count];
        drawOrder = new int[count];
        resetSlots();
    }

    private void resetSlots() {
        for (int i = 0; i < slot.length; i++) {
            slot[i] = i;
            enterAlpha[i] = 1f;
        }
    }

    private int frontChild() {
        int front = -1;
        for (int i = 0; i < slot.length; i++) {
            if (front < 0 || slot[i] < slot[front]) front = i;
        }
        return front;
    }

    private void applyTransforms() {
        int count = getChildCount();
        if (count == 0 || slot.length != count) return;
        float p = Math.max(0f, progress);
        float frontBottom = getChildAt(0).getBottom();
        float contentFade = Math.min(1f, p * 1.6f);
        for (int i = 0; i < count; i++) {
            View row = getChildAt(i);
            float s = slot[i];
            float scale = 1f - SCALE_STEP * s * p;
            float translationY = (frontBottom + peekPx * s - row.getBottom()) * p;
            float alpha = (1f - ALPHA_STEP * s * p) * enterAlpha[i];
            float offsetX = 0f;
            float offsetY = 0f;
            float rotation = 0f;
            if (i == dragChild) {
                offsetX = dragX;
                offsetY = dragY;
                rotation = MAX_ROTATION_DEG * dragX / Math.max(1f, row.getWidth());
                float away = (float) Math.hypot(dragX, dragY) / Math.max(1f, row.getWidth());
                alpha *= Math.max(0f, 1f - Math.max(0f, away - 0.35f) * 1.2f);
            }
            row.setPivotX(row.getWidth() / 2f);
            row.setPivotY(row.getHeight());
            row.setScaleX(scale);
            row.setScaleY(scale);
            row.setTranslationX(offsetX);
            row.setTranslationY(translationY + offsetY);
            row.setRotation(rotation);
            row.setAlpha(Math.max(0f, Math.min(1f, alpha)));
            setTileContentAlpha(row, 1f - Math.max(0f, Math.min(1f, s)) * contentFade);
        }
        updateDrawOrder();
        invalidate();
    }

    /** Back slots first, front slot last: the front tile is on top and gets touches first. */
    private void updateDrawOrder() {
        int count = drawOrder.length;
        for (int i = 0; i < count; i++) drawOrder[i] = i;
        for (int i = 1; i < count; i++) {
            int value = drawOrder[i];
            int j = i - 1;
            while (j >= 0 && slot[drawOrder[j]] < slot[value]) {
                drawOrder[j + 1] = drawOrder[j];
                j--;
            }
            drawOrder[j + 1] = value;
        }
    }

    @Override protected int getChildDrawingOrder(int childCount, int drawingPosition) {
        if (drawOrder.length != childCount) return childCount - 1 - drawingPosition;
        return drawOrder[drawingPosition];
    }

    // ---------------------------------------------------------------- swiping

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!fullyStacked()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startTracking(event);
                return false;
            case MotionEvent.ACTION_MOVE:
                trackVelocity(event);
                if (tracking && !dragging && beyondSlop(event)) {
                    beginDrag();
                    return true;
                }
                return dragging;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) stopTracking();
                return dragging;
            default:
                return dragging;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!fullyStacked() && !dragging) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Reached only when no tile consumed the press (non-clickable tile).
                startTracking(event);
                return tracking;
            case MotionEvent.ACTION_MOVE:
                trackVelocity(event);
                if (tracking && !dragging && beyondSlop(event)) beginDrag();
                if (dragging) updateDrag(event);
                return tracking;
            case MotionEvent.ACTION_UP:
                trackVelocity(event);
                if (dragging) releaseDrag();
                stopTracking();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) springBack();
                stopTracking();
                return true;
            default:
                return tracking;
        }
    }

    private void startTracking(MotionEvent event) {
        tracking = false;
        dragging = false;
        if (isAnimating()) return;
        int front = frontChild();
        if (front < 0) return;
        View row = getChildAt(front);
        float x = event.getX();
        float y = event.getY();
        float top = row.getTop() + row.getTranslationY();
        if (x < row.getLeft() || x > row.getRight() || y < top || y > top + row.getHeight()) return;
        tracking = true;
        dragChild = front;
        downX = x;
        downY = y;
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.clear();
        velocityTracker.addMovement(event);
    }

    private void trackVelocity(MotionEvent event) {
        if (velocityTracker != null) velocityTracker.addMovement(event);
    }

    private boolean beyondSlop(MotionEvent event) {
        return Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop;
    }

    private void beginDrag() {
        dragging = true;
        pastThreshold = false;
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
    }

    private void updateDrag(MotionEvent event) {
        View row = getChildAt(dragChild);
        float rawX = event.getX() - downX;
        float rawY = event.getY() - downY;
        dragX = rubberBand(rawX, row.getWidth());
        dragY = rubberBand(rawY, row.getWidth());
        boolean past = Math.hypot(rawX, rawY) > row.getWidth() * DISMISS_DISTANCE_FRACTION;
        if (past != pastThreshold) {
            pastThreshold = past;
            if (past) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        // The tiles behind lean forward a little as the front tile is pulled away.
        float pull = Math.min(1f, (float) Math.hypot(rawX, rawY) / (row.getWidth() * DISMISS_DISTANCE_FRACTION));
        for (int i = 0; i < slot.length; i++) {
            if (i != dragChild) slot[i] = Math.round(slot[i]) - 0.25f * pull;
        }
        applyTransforms();
    }

    private void releaseDrag() {
        View row = getChildAt(dragChild);
        velocityTracker.computeCurrentVelocity(1000);
        float vx = velocityTracker.getXVelocity();
        float vy = velocityTracker.getYVelocity();
        float speed = (float) Math.hypot(vx, vy);
        float rawX = dragDistanceX(row);
        float rawY = dragDistanceY(row);
        boolean fling = speed > FLING_DP_PER_S * density;
        boolean far = Math.hypot(rawX, rawY) > row.getWidth() * DISMISS_DISTANCE_FRACTION;
        if (fling || far) {
            float dirX = fling ? vx : rawX;
            float dirY = fling ? vy : rawY;
            throwOut(dirX, dirY);
        } else {
            springBack();
        }
    }

    /** Inverse of the rubber band, so thresholds use the finger's real travel. */
    private float dragDistanceX(View row) {
        return inverseRubberBand(dragX, row.getWidth());
    }

    private float dragDistanceY(View row) {
        return inverseRubberBand(dragY, row.getWidth());
    }

    private void springBack() {
        final float startX = dragX;
        final float startY = dragY;
        final float[] startSlots = slot.clone();
        final float[] endSlots = roundedSlots();
        cancelAnimator(gestureAnimator);
        gestureAnimator = ValueAnimator.ofFloat(0f, 1f);
        gestureAnimator.setDuration(480);
        gestureAnimator.setInterpolator(spring);
        gestureAnimator.addUpdateListener(animation -> {
            float f = (float) animation.getAnimatedValue();
            dragX = startX * (1f - f);
            dragY = startY * (1f - f);
            for (int i = 0; i < slot.length; i++) {
                slot[i] = startSlots[i] + (endSlots[i] - startSlots[i]) * Math.min(1f, f);
            }
            applyTransforms();
        });
        gestureAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                dragX = 0f;
                dragY = 0f;
                System.arraycopy(endSlots, 0, slot, 0, slot.length);
                dragChild = -1;
                applyTransforms();
            }
        });
        gestureAnimator.start();
    }

    /** Throws the front tile out along (dirX, dirY), then files it at the back. */
    private void throwOut(float dirX, float dirY) {
        final int thrown = dragChild;
        View row = getChildAt(thrown);
        float length = (float) Math.hypot(dirX, dirY);
        if (length < 1f) {
            dirX = 1f;
            dirY = 0f;
            length = 1f;
        }
        float distance = Math.max(row.getWidth(), getHeight()) * 1.4f;
        final float startX = dragX;
        final float startY = dragY;
        final float endX = dirX / length * distance;
        final float endY = dirY / length * distance;

        cancelAnimator(gestureAnimator);
        gestureAnimator = ValueAnimator.ofFloat(0f, 1f);
        gestureAnimator.setDuration(260);
        gestureAnimator.setInterpolator(decelerate);
        gestureAnimator.addUpdateListener(animation -> {
            float f = (float) animation.getAnimatedValue();
            dragX = startX + (endX - startX) * f;
            dragY = startY + (endY - startY) * f;
            applyTransforms();
        });
        gestureAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                dragX = 0f;
                dragY = 0f;
                dragChild = -1;
                fileAtBack(thrown);
            }
        });
        gestureAnimator.start();
        advanceRows(thrown);
    }

    /** Every tile behind the thrown one moves one slot forward. */
    private void advanceRows(int thrown) {
        final float[] startSlots = slot.clone();
        final float[] endSlots = roundedSlots();
        for (int i = 0; i < endSlots.length; i++) {
            if (i != thrown) endSlots[i] = Math.max(0f, endSlots[i] - 1f);
        }
        endSlots[thrown] = startSlots[thrown];
        cancelAnimator(slotAnimator);
        slotAnimator = ValueAnimator.ofFloat(0f, 1f);
        slotAnimator.setDuration(340);
        slotAnimator.setInterpolator(spring);
        slotAnimator.addUpdateListener(animation -> {
            float f = (float) animation.getAnimatedValue();
            for (int i = 0; i < slot.length; i++) {
                // The thrown row is driven by the throw and then fileAtBack().
                if (i == thrown) continue;
                slot[i] = startSlots[i] + (endSlots[i] - startSlots[i]) * f;
            }
            applyTransforms();
        });
        slotAnimator.start();
    }

    /** The thrown tile fades in at the back of the stack. */
    private void fileAtBack(final int thrown) {
        final float backSlot = slot.length - 1;
        slot[thrown] = backSlot + 0.6f;
        enterAlpha[thrown] = 0f;
        ValueAnimator enter = ValueAnimator.ofFloat(0f, 1f);
        enter.setDuration(260);
        enter.setInterpolator(decelerate);
        enter.addUpdateListener(animation -> {
            float f = (float) animation.getAnimatedValue();
            slot[thrown] = backSlot + 0.6f * (1f - f);
            enterAlpha[thrown] = f;
            applyTransforms();
        });
        enter.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                slot[thrown] = backSlot;
                enterAlpha[thrown] = 1f;
                applyTransforms();
            }
        });
        gestureAnimator = enter;
        enter.start();
    }

    private float[] roundedSlots() {
        float[] rounded = new float[slot.length];
        for (int i = 0; i < slot.length; i++) rounded[i] = Math.round(slot[i]);
        return rounded;
    }

    private boolean isAnimating() {
        return (gestureAnimator != null && gestureAnimator.isRunning()) ||
                (slotAnimator != null && slotAnimator.isRunning());
    }

    private void cancelGesture() {
        cancelAnimator(gestureAnimator);
        cancelAnimator(slotAnimator);
        if (slot.length > 0) {
            float[] rounded = roundedSlots();
            System.arraycopy(rounded, 0, slot, 0, slot.length);
        }
        for (int i = 0; i < enterAlpha.length; i++) enterAlpha[i] = 1f;
        dragX = 0f;
        dragY = 0f;
        dragChild = -1;
        dragging = false;
        tracking = false;
    }

    private static void cancelAnimator(ValueAnimator animator) {
        if (animator == null) return;
        animator.removeAllListeners();
        animator.removeAllUpdateListeners();
        animator.cancel();
    }

    private void stopTracking() {
        tracking = false;
        dragging = false;
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    @Override protected void onDetachedFromWindow() {
        cancelGesture();
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------- helpers

    /** iOS rubber band: grows ever more slowly as the finger travels further. */
    private static float rubberBand(float offset, float dimension) {
        if (dimension <= 0f) return offset;
        float magnitude = Math.abs(offset);
        float banded = (1f - 1f / (magnitude * RUBBER_BAND / dimension + 1f)) * dimension;
        return Math.signum(offset) * banded;
    }

    private static float inverseRubberBand(float banded, float dimension) {
        if (dimension <= 0f) return banded;
        float magnitude = Math.min(Math.abs(banded), dimension * 0.999f);
        float offset = (1f / (1f - magnitude / dimension) - 1f) * dimension / RUBBER_BAND;
        return Math.signum(banded) * offset;
    }

    /** Fades a tile's text (its direct children) while keeping its background. */
    private static void setTileContentAlpha(View tile, float alpha) {
        if (!(tile instanceof ViewGroup)) return;
        ViewGroup content = (ViewGroup) tile;
        for (int c = 0; c < content.getChildCount(); c++) {
            View child = content.getChildAt(c);
            if (child.getAlpha() != alpha) child.setAlpha(alpha);
        }
    }

    /** Damped spring: a slight overshoot that settles, like iOS card snaps. */
    private static final class SpringInterpolator implements TimeInterpolator {
        @Override public float getInterpolation(float t) {
            return (float) (1.0 - Math.exp(-6.5 * t) * Math.cos(9.0 * t));
        }
    }
}
