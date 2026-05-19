package com.ephemeral.android.ui.media;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

public final class ZoomableImageView extends ImageView {
    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 4.5f;
    private static final float ZOOM_EPSILON = 0.01f;
    private static final float PINCH_SENSITIVITY = 1.7f;
    private static final float DOUBLE_TAP_SCALE = 2f;
    private static final long DOUBLE_TAP_ANIMATION_MS = 180L;

    private final GestureDetector gestureDetector;
    private final ScaleGestureDetector scaleDetector;
    private final DecelerateInterpolator zoomInterpolator = new DecelerateInterpolator();
    private final Matrix baseMatrix = new Matrix();
    private final Matrix gestureMatrix = new Matrix();
    private final Matrix drawMatrix = new Matrix();
    private final Matrix targetMatrix = new Matrix();
    private final RectF drawableRect = new RectF();
    private final RectF displayRect = new RectF();

    private ValueAnimator zoomAnimator;
    private Drawable lastDrawable;
    private float currentScale = MIN_SCALE;
    private float lastTouchX;
    private float lastTouchY;
    private boolean zoomEnabled = true;
    private boolean matrixDirty = true;

    public ZoomableImageView(Context context) {
        this(context, null);
    }

    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        gestureDetector = new GestureDetector(context, new TapListener());
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        super.setScaleType(ScaleType.MATRIX);
    }

    @Override
    public void setScaleType(ScaleType scaleType) {
        super.setScaleType(ScaleType.MATRIX);
    }

    public boolean isZoomedIn() {
        return currentScale > MIN_SCALE + ZOOM_EPSILON;
    }

    public void setZoomEnabled(boolean zoomEnabled) {
        this.zoomEnabled = zoomEnabled;
        if (!zoomEnabled) {
            resetZoom();
        }
    }

    public void resetZoom() {
        cancelZoomAnimation();
        currentScale = MIN_SCALE;
        gestureMatrix.reset();
        updateImageMatrix();
        allowParentIntercept();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        matrixDirty = true;
        updateImageMatrix();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable drawable = getDrawable();
        if (drawable != lastDrawable) {
            lastDrawable = drawable;
            if (drawable != null) {
                drawable.setFilterBitmap(true);
            }
            matrixDirty = true;
            updateImageMatrix();
        }
        super.onDraw(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!zoomEnabled) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            cancelZoomAnimation();
        }
        gestureDetector.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                if (isZoomedIn()) {
                    disallowParentIntercept();
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                disallowParentIntercept();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                rememberRemainingPointer(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (isZoomedIn()) {
                    disallowParentIntercept();
                    if (!scaleDetector.isInProgress() && event.getPointerCount() == 1) {
                        float x = event.getX();
                        float y = event.getY();
                        translateBy(x - lastTouchX, y - lastTouchY);
                        lastTouchX = x;
                        lastTouchY = y;
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!isZoomedIn()) {
                    resetZoom();
                }
                return true;
            default:
                return true;
        }
    }

    private void applyScale(float scaleFactor, float focusX, float focusY) {
        cancelZoomAnimation();
        float accelerated = (float) Math.pow(scaleFactor, PINCH_SENSITIVITY);
        float nextScale = clamp(currentScale * accelerated, MIN_SCALE, MAX_SCALE);
        float appliedScale = nextScale / currentScale;
        if (Math.abs(appliedScale - 1f) < 0.001f) {
            return;
        }
        currentScale = nextScale;
        gestureMatrix.postScale(appliedScale, appliedScale, focusX, focusY);
        constrainTranslation();
        updateImageMatrix();
    }

    private void translateBy(float dx, float dy) {
        gestureMatrix.postTranslate(dx, dy);
        constrainTranslation();
        updateImageMatrix();
    }

    private void toggleDoubleTapZoom() {
        if (isZoomedIn()) {
            animateToScale(MIN_SCALE);
            return;
        }
        animateToScale(DOUBLE_TAP_SCALE);
        disallowParentIntercept();
    }

    private void animateToScale(float targetScale) {
        cancelZoomAnimation();
        float startScale = currentScale;
        targetMatrix.reset();
        if (targetScale > MIN_SCALE + ZOOM_EPSILON) {
            targetMatrix.postScale(targetScale, targetScale, getWidth() / 2f, getHeight() / 2f);
            constrainTranslation(targetMatrix);
        }
        float[] startValues = new float[9];
        float[] endValues = new float[9];
        float[] animatedValues = new float[9];
        gestureMatrix.getValues(startValues);
        targetMatrix.getValues(endValues);
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f);
        zoomAnimator.setDuration(DOUBLE_TAP_ANIMATION_MS);
        zoomAnimator.setInterpolator(zoomInterpolator);
        zoomAnimator.addUpdateListener(animator -> {
            float fraction = (float) animator.getAnimatedValue();
            for (int i = 0; i < animatedValues.length; i++) {
                animatedValues[i] = startValues[i] + (endValues[i] - startValues[i]) * fraction;
            }
            currentScale = startScale + (targetScale - startScale) * fraction;
            gestureMatrix.setValues(animatedValues);
            updateImageMatrix();
        });
        zoomAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
                zoomAnimator = null;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (cancelled) {
                    return;
                }
                zoomAnimator = null;
                currentScale = targetScale;
                gestureMatrix.set(targetMatrix);
                updateImageMatrix();
                if (!isZoomedIn()) {
                    allowParentIntercept();
                }
            }
        });
        zoomAnimator.start();
    }

    private void configureBaseMatrix() {
        Drawable drawable = getDrawable();
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (drawable == null || viewWidth <= 0 || viewHeight <= 0) {
            return;
        }
        int drawableWidth = Math.max(1, drawable.getIntrinsicWidth());
        int drawableHeight = Math.max(1, drawable.getIntrinsicHeight());
        drawableRect.set(0f, 0f, drawableWidth, drawableHeight);
        baseMatrix.reset();
        baseMatrix.setRectToRect(drawableRect, new RectF(0f, 0f, viewWidth, viewHeight),
                Matrix.ScaleToFit.CENTER);
        matrixDirty = false;
    }

    private void updateImageMatrix() {
        if (matrixDirty) {
            configureBaseMatrix();
        }
        drawMatrix.set(baseMatrix);
        drawMatrix.postConcat(gestureMatrix);
        setImageMatrix(drawMatrix);
    }

    private void constrainTranslation() {
        constrainTranslation(gestureMatrix);
    }

    private void constrainTranslation(Matrix matrix) {
        RectF rect = mappedDisplayRect(matrix);
        if (rect.isEmpty()) {
            return;
        }
        float deltaX = correctionDelta(rect.left, rect.right, getWidth());
        float deltaY = correctionDelta(rect.top, rect.bottom, getHeight());
        if (deltaX != 0f || deltaY != 0f) {
            matrix.postTranslate(deltaX, deltaY);
        }
    }

    private RectF mappedDisplayRect(Matrix matrix) {
        Drawable drawable = getDrawable();
        if (drawable == null) {
            displayRect.setEmpty();
            return displayRect;
        }
        drawMatrix.set(baseMatrix);
        drawMatrix.postConcat(matrix);
        displayRect.set(0f, 0f, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        drawMatrix.mapRect(displayRect);
        return displayRect;
    }

    private float correctionDelta(float start, float end, float limit) {
        float size = end - start;
        if (size <= limit) {
            return (limit - size) / 2f - start;
        }
        if (start > 0f) {
            return -start;
        }
        if (end < limit) {
            return limit - end;
        }
        return 0f;
    }

    private void rememberRemainingPointer(MotionEvent event) {
        int actionIndex = event.getActionIndex();
        int remainingIndex = actionIndex == 0 ? 1 : 0;
        if (remainingIndex >= event.getPointerCount()) {
            return;
        }
        lastTouchX = event.getX(remainingIndex);
        lastTouchY = event.getY(remainingIndex);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }

    private void disallowParentIntercept() {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    private void allowParentIntercept() {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
    }

    private void cancelZoomAnimation() {
        if (zoomAnimator != null) {
            zoomAnimator.cancel();
            zoomAnimator = null;
        }
    }

    private final class TapListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent event) {
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent event) {
            toggleDoubleTapZoom();
            return true;
        }
    }

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            disallowParentIntercept();
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            applyScale(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            if (!isZoomedIn()) {
                resetZoom();
            }
        }
    }
}
