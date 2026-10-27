package com.mqltv;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * A FrameLayout that enforces an exact 16:9 aspect ratio based on its width.
 */
public class RatioFrameLayout extends FrameLayout {

    private float aspectRatio = 16f / 9f;

    public RatioFrameLayout(Context context) {
        super(context);
    }

    public RatioFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RatioFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setAspectRatio(float ratio) {
        if (ratio > 0 && this.aspectRatio != ratio) {
            this.aspectRatio = ratio;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width > 0) {
            int height = Math.round(width / aspectRatio);
            int exactHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
            super.onMeasure(widthMeasureSpec, exactHeightSpec);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
