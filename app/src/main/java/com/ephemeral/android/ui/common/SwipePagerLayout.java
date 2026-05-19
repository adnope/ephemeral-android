package com.ephemeral.android.ui.common;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

public final class SwipePagerLayout extends ViewGroup {
    public interface OnPageChangedListener {
        void onPageChanged(int page);
    }

    private static final long SETTLE_DURATION_MS = 240L;
    private static final float PAGE_SETTLE_THRESHOLD = 0.35f;
    private static final float HORIZONTAL_DRAG_RATIO = 1.2f;

    private final int touchSlop;
    private final int minimumFlingVelocity;
    private final DecelerateInterpolator interpolator = new DecelerateInterpolator();

    private OnPageChangedListener pageChangedListener;
    private VelocityTracker velocityTracker;
    private ValueAnimator animator;
    private int currentPage;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private float initialX;
    private float initialY;
    private float lastX;
    private boolean dragging;

    public SwipePagerLayout(Context context) {
        this(context, null);
    }

    public SwipePagerLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minimumFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        setClipToPadding(false);
    }

    public void setOnPageChangedListener(OnPageChangedListener pageChangedListener) {
        this.pageChangedListener = pageChangedListener;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void setCurrentPage(int page, boolean animate) {
        int target = clampPage(page);
        if (getWidth() == 0 || !animate) {
            cancelAnimator();
            int previous = currentPage;
            currentPage = target;
            scrollTo(pageScrollX(target), 0);
            if (previous != currentPage) {
                notifyPageChanged();
            }
            return;
        }
        animateToPage(target);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        int childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int childHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(childWidth, childHeight);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = getWidth();
        int height = getHeight();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int childLeft = i * width;
            child.layout(childLeft, 0, childLeft + width, height);
        }
        scrollTo(pageScrollX(currentPage), 0);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            finishTouch();
            return false;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            beginTouch(event);
            return false;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            handlePointerUp(event);
            return dragging;
        }
        if (action != MotionEvent.ACTION_MOVE || activePointerId == MotionEvent.INVALID_POINTER_ID) {
            return dragging;
        }
        int pointerIndex = event.findPointerIndex(activePointerId);
        if (pointerIndex < 0) {
            return false;
        }
        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        float dx = x - initialX;
        float dy = y - initialY;
        if (shouldStartDrag(dx, dy)) {
            dragging = true;
            lastX = x;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        ensureVelocityTracker();
        velocityTracker.addMovement(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            beginTouch(event);
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            handlePointerUp(event);
            return true;
        }
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
            return false;
        }
        int pointerIndex = event.findPointerIndex(activePointerId);
        if (pointerIndex < 0) {
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float x = event.getX(pointerIndex);
            float y = event.getY(pointerIndex);
            if (!dragging && shouldStartDrag(x - initialX, y - initialY)) {
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            if (dragging) {
                dragTo(x);
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP) {
            if (dragging) {
                velocityTracker.computeCurrentVelocity(1000);
                settleAfterDrag(velocityTracker.getXVelocity(activePointerId));
                finishTouch();
                return true;
            }
            finishTouch();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            if (dragging) {
                animateToPage(currentPage);
            }
            finishTouch();
            return true;
        }
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimator();
        finishTouch();
        super.onDetachedFromWindow();
    }

    private void beginTouch(MotionEvent event) {
        cancelAnimator();
        activePointerId = event.getPointerId(0);
        initialX = event.getX();
        initialY = event.getY();
        lastX = initialX;
        dragging = false;
        ensureVelocityTracker();
        velocityTracker.addMovement(event);
    }

    private void dragTo(float x) {
        float delta = lastX - x;
        lastX = x;
        int nextScrollX = Math.round(clampScroll(getScrollX() + delta));
        scrollTo(nextScrollX, 0);
    }

    private void settleAfterDrag(float velocityX) {
        int width = getWidth();
        if (width <= 0) {
            return;
        }
        float pageOffset = getScrollX() / (float) width;
        float movedPages = pageOffset - currentPage;
        int target = currentPage;
        if (Math.abs(velocityX) >= minimumFlingVelocity) {
            target = velocityX < 0 ? currentPage + 1 : currentPage - 1;
        } else if (Math.abs(movedPages) >= PAGE_SETTLE_THRESHOLD) {
            target = movedPages > 0 ? currentPage + 1 : currentPage - 1;
        }
        animateToPage(clampPage(target));
    }

    private boolean shouldStartDrag(float dx, float dy) {
        float absDx = Math.abs(dx);
        float absDy = Math.abs(dy);
        return absDx > touchSlop
                && absDx > absDy * HORIZONTAL_DRAG_RATIO
                && canDragInDirection(dx);
    }

    private boolean canDragInDirection(float fingerDeltaX) {
        if (fingerDeltaX < 0) {
            return getScrollX() < maxScrollX();
        }
        if (fingerDeltaX > 0) {
            return getScrollX() > 0;
        }
        return false;
    }

    private void animateToPage(int targetPage) {
        cancelAnimator();
        int start = getScrollX();
        int end = pageScrollX(targetPage);
        if (start == end) {
            setSettledPage(targetPage);
            return;
        }
        animator = ValueAnimator.ofInt(start, end);
        animator.setDuration(animationDuration(start, end));
        animator.setInterpolator(interpolator);
        animator.addUpdateListener(valueAnimator -> scrollTo((int) valueAnimator.getAnimatedValue(), 0));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                animator = null;
                setSettledPage(targetPage);
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                animator = null;
            }
        });
        animator.start();
    }

    private long animationDuration(int start, int end) {
        int width = Math.max(1, getWidth());
        float distancePages = Math.min(1f, Math.abs(end - start) / (float) width);
        return Math.max(120L, Math.round(SETTLE_DURATION_MS * distancePages));
    }

    private void setSettledPage(int page) {
        int target = clampPage(page);
        int previous = currentPage;
        currentPage = target;
        scrollTo(pageScrollX(target), 0);
        if (previous != currentPage) {
            notifyPageChanged();
        }
    }

    private void notifyPageChanged() {
        if (pageChangedListener != null) {
            pageChangedListener.onPageChanged(currentPage);
        }
    }

    private int clampPage(int page) {
        int maxPage = Math.max(0, getChildCount() - 1);
        return Math.max(0, Math.min(page, maxPage));
    }

    private int pageScrollX(int page) {
        return clampPage(page) * getWidth();
    }

    private float clampScroll(float scrollX) {
        return Math.max(0f, Math.min(scrollX, maxScrollX()));
    }

    private int maxScrollX() {
        return Math.max(0, (getChildCount() - 1) * getWidth());
    }

    private void cancelAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private void ensureVelocityTracker() {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
    }

    private void finishTouch() {
        dragging = false;
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private void handlePointerUp(MotionEvent event) {
        int pointerIndex = event.getActionIndex();
        if (event.getPointerId(pointerIndex) != activePointerId) {
            return;
        }
        int nextIndex = pointerIndex == 0 ? 1 : 0;
        if (nextIndex >= event.getPointerCount()) {
            activePointerId = MotionEvent.INVALID_POINTER_ID;
            return;
        }
        activePointerId = event.getPointerId(nextIndex);
        lastX = event.getX(nextIndex);
        initialX = lastX;
        initialY = event.getY(nextIndex);
        if (velocityTracker != null) {
            velocityTracker.clear();
        }
    }
}
