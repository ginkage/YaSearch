package com.ginkage.yasearch;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import ru.yandex.speechkit.gui.AppConstant;
import ru.yandex.speechkit.gui.util.Utils;

public class CirclesAnimationView extends View {
    private List<Circle> circles = new LinkedList<>();
    private boolean isPlaying = false;
    private float startCircleThickness;
    private float endCircleThickness;
    private float offsetRaduis;
    private float noSoundOffsetRaduis;
    private float maxRadius;
    private float minRadius;
    private int color;
    private float animationStep;
    private long centerX;
    private long centerY;
    private final Paint circlePaint;
    Circle lastAddedCircle;
    long lastAddedTime = -1L;

    public CirclesAnimationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        color = AppConstant.CIRCLE_DEFAULT_COLOR;
        circlePaint = new Paint();
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setAntiAlias(true);
        Resources res = context.getResources();
        startCircleThickness = res.getDimension(R.dimen.yask_default_circle_thin) * 0.9F;
        endCircleThickness = res.getDimension(R.dimen.yask_default_circle_thin) * 0.2F;
        animationStep = res.getDimension(R.dimen.yask_default_circle_thin);
    }

    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        centerX = (long)(getMeasuredWidth() / 2);
        centerY = (long)(getMeasuredHeight() / 2);
        maxRadius = (float)Math.min(centerX, centerY) * 1.0F;
        offsetRaduis = (maxRadius - minRadius) * 0.4F;
        noSoundOffsetRaduis = (maxRadius - minRadius) * 0.3F;
    }

    protected void onDraw(Canvas c) {
        if (isPlaying) {
            drawCircles(c);
            postInvalidateDelayed(15L);
        }
    }

    public void setPlaying(boolean isPlaying) {
        this.isPlaying = isPlaying;
        if (isPlaying) {
            invalidate();
        }
    }

    public boolean canBeAdded(float power) {
        float threshold;
        if (power > 0.1F) {
            threshold = (maxRadius - minRadius) * 0.1F;
            if (lastAddedCircle != null && lastAddedCircle.currentRadius - minRadius < threshold) {
                return false;
            }
        } else {
            threshold = (maxRadius - minRadius) * 0.5F;
            if (lastAddedCircle != null && !lastAddedCircle.isDone() &&
                    (double)(lastAddedCircle.currentRadius - minRadius) < (double)threshold - 0.01D) {
                return false;
            }
        }

        return true;
    }

    public void addCircle(float scale) {
        Circle circle = new Circle();
        circle.minRadius = minRadius;
        circle.currentRadius = minRadius;
        float offset = scale > 0.1F?offsetRaduis:noSoundOffsetRaduis;
        float max = minRadius + (maxRadius - minRadius) * scale + offset;
        circle.maxRadius = Math.min(maxRadius, max);
        lastAddedCircle = circle;
        lastAddedTime = System.currentTimeMillis();
        circles.add(circle);
    }

    private void drawCircles(Canvas c) {
        Iterator iter = circles.iterator();

        while(iter.hasNext()) {
            CirclesAnimationView.Circle circle = (CirclesAnimationView.Circle)iter.next();
            float stepRatio = 1.5F + 1.0F * (1.0F - circle.progress);
            circle.process(animationStep * stepRatio);
            if (circle.isDone()) {
                iter.remove();
            } else {
                float opacity = Math.max(0.3F, 0.9F * circle.progress);
                float strokeWidth = Math.max(endCircleThickness, startCircleThickness * circle.progress);
                circlePaint.setColor(Utils.getColorWithOpacity(opacity, color));
                circlePaint.setStrokeWidth(strokeWidth);
                c.drawCircle((float)centerX, (float)centerY, circle.currentRadius, circlePaint);
            }
        }
    }

    public void hasNoSound(boolean hasNoSound) {
        color = hasNoSound?AppConstant.CIRCLE_NO_SOUND_COLOR:AppConstant.CIRCLE_DEFAULT_COLOR;
    }

    public void setMinRadius(float minRadius) {
        this.minRadius = minRadius;
    }

    public void setImage(final View view) {
        view.post(new Runnable() {
            public void run() {
                long width = (long)view.getMeasuredWidth();
                long height = (long)view.getMeasuredHeight();
                setMinRadius((float)(Math.max(width, height) / 2L));
            }
        });
    }

    private static class Circle {
        float maxRadius;
        float currentRadius;
        float minRadius;
        float progress;

        private Circle() {
            progress = 1.0F;
        }

        boolean isDone() {
            return (double)currentRadius >= (double)maxRadius - 0.001D;
        }

        void process(float step) {
            currentRadius += step;
            currentRadius = Math.min(currentRadius, maxRadius);
            float normalizedCurrent = currentRadius - minRadius;
            float normalizedMax = maxRadius - minRadius;
            progress = Math.max(0.1F, 1.0F - normalizedCurrent / normalizedMax);
        }
    }
}
