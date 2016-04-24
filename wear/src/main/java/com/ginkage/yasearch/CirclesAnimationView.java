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
    private static final String TAG = CirclesAnimationView.class.getName();
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
    CirclesAnimationView.Circle lastAddedCircle;
    long lastAddedTime = -1L;

    public CirclesAnimationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.color = AppConstant.CIRCLE_DEFAULT_COLOR;
        this.circlePaint = new Paint();
        this.circlePaint.setStyle(Paint.Style.STROKE);
        this.circlePaint.setAntiAlias(true);
        Resources res = context.getResources();
        this.startCircleThickness = res.getDimension(R.dimen.yask_default_circle_thin) * 0.9F;
        this.endCircleThickness = res.getDimension(R.dimen.yask_default_circle_thin) * 0.2F;
        this.animationStep = res.getDimension(R.dimen.yask_default_circle_thin);
    }

    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        this.centerX = (long)(this.getMeasuredWidth() / 2);
        this.centerY = (long)(this.getMeasuredHeight() / 2);
        this.maxRadius = (float)Math.min(this.centerX, this.centerY) * 1.0F;
        this.offsetRaduis = (this.maxRadius - this.minRadius) * 0.4F;
        this.noSoundOffsetRaduis = (this.maxRadius - this.minRadius) * 0.3F;
    }

    protected void onDraw(Canvas c) {
        if(this.isPlaying) {
            this.drawCircles(c);
            this.postInvalidateDelayed(15L);
        }

    }

    public void setPlaying(boolean isPlaying) {
        this.isPlaying = isPlaying;
        if(isPlaying) {
            this.invalidate();
        }

    }

    public boolean canBeAdded(float power) {
        float threshold;
        if(power > 0.1F) {
            threshold = (this.maxRadius - this.minRadius) * 0.1F;
            if(this.lastAddedCircle != null && this.lastAddedCircle.currentRadius - this.minRadius < threshold) {
                return false;
            }
        } else {
            threshold = (this.maxRadius - this.minRadius) * 0.5F;
            if(this.lastAddedCircle != null && !this.lastAddedCircle.isDone() && (double)(this.lastAddedCircle.currentRadius - this.minRadius) < (double)threshold - 0.01D) {
                return false;
            }
        }

        return true;
    }

    public void addCircle(float scale) {
        CirclesAnimationView.Circle circle = new CirclesAnimationView.Circle();
        circle.minRadius = this.minRadius;
        circle.currentRadius = this.minRadius;
        float offset = scale > 0.1F?this.offsetRaduis:this.noSoundOffsetRaduis;
        float max = this.minRadius + (this.maxRadius - this.minRadius) * scale + offset;
        circle.maxRadius = Math.min(this.maxRadius, max);
        this.lastAddedCircle = circle;
        this.lastAddedTime = System.currentTimeMillis();
        this.circles.add(circle);
    }

    private void drawCircles(Canvas c) {
        Iterator iter = this.circles.iterator();

        while(iter.hasNext()) {
            CirclesAnimationView.Circle circle = (CirclesAnimationView.Circle)iter.next();
            float stepRatio = 1.5F + 1.0F * (1.0F - circle.progress);
            circle.process(this.animationStep * stepRatio);
            if(circle.isDone()) {
                iter.remove();
            } else {
                float opacity = Math.max(0.3F, 0.9F * circle.progress);
                float strokeWidth = Math.max(this.endCircleThickness, this.startCircleThickness * circle.progress);
                this.circlePaint.setColor(Utils.getColorWithOpacity(opacity, this.color));
                this.circlePaint.setStrokeWidth(strokeWidth);
                c.drawCircle((float)this.centerX, (float)this.centerY, circle.currentRadius, this.circlePaint);
            }
        }

    }

    public void hasNoSound(boolean hasNoSound) {
        this.color = hasNoSound?AppConstant.CIRCLE_NO_SOUND_COLOR:AppConstant.CIRCLE_DEFAULT_COLOR;
    }

    public void setMinRadius(float minRadius) {
        this.minRadius = minRadius;
    }

    public void setImage(final View view) {
        view.post(new Runnable() {
            public void run() {
                long width = (long)view.getMeasuredWidth();
                long height = (long)view.getMeasuredHeight();
                CirclesAnimationView.this.setMinRadius((float)(Math.max(width, height) / 2L));
            }
        });
    }

    private static class Circle {
        float maxRadius;
        float currentRadius;
        float minRadius;
        float progress;

        private Circle() {
            this.progress = 1.0F;
        }

        boolean isDone() {
            return (double)this.currentRadius >= (double)this.maxRadius - 0.001D;
        }

        void process(float step) {
            this.currentRadius += step;
            this.currentRadius = Math.min(this.currentRadius, this.maxRadius);
            float normalizedCurrent = this.currentRadius - this.minRadius;
            float normalizedMax = this.maxRadius - this.minRadius;
            this.progress = Math.max(0.1F, 1.0F - normalizedCurrent / normalizedMax);
        }
    }
}
