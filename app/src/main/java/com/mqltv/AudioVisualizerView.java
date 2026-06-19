package com.mqltv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

public class AudioVisualizerView extends View {
    private Paint paint;
    private int barCount = 10;
    private float[] targetHeights;
    private float[] currentHeights;
    private boolean isPlaying = false;
    private Random random;
    
    private final Runnable animateRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlaying) {
                updateHeights();
                invalidate();
                postDelayed(this, 50); // ~20fps
            } else {
                // Decay heights to 0
                boolean needsUpdate = false;
                for (int i = 0; i < barCount; i++) {
                    if (currentHeights[i] > 2f) {
                        currentHeights[i] *= 0.8f;
                        needsUpdate = true;
                    } else {
                        currentHeights[i] = 0;
                    }
                }
                if (needsUpdate) {
                    invalidate();
                    postDelayed(this, 50);
                }
            }
        }
    };

    public AudioVisualizerView(Context context) {
        super(context);
        init();
    }

    public AudioVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.parseColor("#FF6B35")); // Orange brand color
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.ROUND);
        
        random = new Random();
        targetHeights = new float[barCount];
        currentHeights = new float[barCount];
    }

    public void setPlaying(boolean playing) {
        if (this.isPlaying != playing) {
            this.isPlaying = playing;
            if (playing) {
                removeCallbacks(animateRunnable);
                post(animateRunnable);
            }
        }
    }

    private void updateHeights() {
        float maxHeight = getHeight() * 0.8f;
        for (int i = 0; i < barCount; i++) {
            // Give new target rarely or if close
            if (random.nextFloat() > 0.7f || Math.abs(currentHeights[i] - targetHeights[i]) < 5f) {
                targetHeights[i] = random.nextFloat() * maxHeight;
            }
            // Move towards target
            currentHeights[i] += (targetHeights[i] - currentHeights[i]) * 0.4f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0) return;

        int width = getWidth();
        int height = getHeight();
        
        float barWidth = (width / (float) barCount) * 0.6f;
        float spacing = (width / (float) barCount) * 0.4f;
        float startX = spacing / 2f;
        
        paint.setStrokeWidth(barWidth);

        for (int i = 0; i < barCount; i++) {
            float x = startX + (i * (barWidth + spacing)) + barWidth / 2f;
            float h = Math.max(currentHeights[i], 5f); // Min height 5px
            float yStart = height;
            float yEnd = height - h;
            
            canvas.drawLine(x, yStart, x, yEnd, paint);
        }
    }
}
