package com.mqltv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * A floating translucent glass highlight that smoothly glides and morphs between tiles.
 * Handles its own coordinates and sizing via canvas drawing, eliminating layout passes.
 */
public class GlassView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    private final RectF glowRect = new RectF();

    private final RectF currentRect = new RectF();
    private final RectF targetRect = new RectF();
    private final RectF startRect = new RectF();

    private final float density;
    private float radius;
    private int glowRgb = 0xFFFFFF;
    private int glowAlpha = 0x30;

    private float currentAlpha = 0f;
    private int currentAccentColor = 0;
    private int startAccentColor = 0;
    private int targetAccentColor = 0;

    private ValueAnimator moveAnimator;
    private ValueAnimator fadeAnimator;

    private final DecelerateInterpolator interpolator = new DecelerateInterpolator(1.4f);

    public GlassView(Context context) {
        this(context, null);
    }

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.6f * density);
        rim.setColor(0xCCFFFFFF);
        glow.setStyle(Paint.Style.STROKE);

        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            setElevation(24f * density);
        }
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void moveTo(float x, float y, float w, float h, int accentColor) {
        setVisibility(View.VISIBLE);
        targetRect.set(x, y, x + w, y + h);
        targetAccentColor = accentColor;

        if (currentAlpha < 0.05f || currentRect.isEmpty()) {
            currentRect.set(targetRect);
            currentAccentColor = accentColor;
            applyAccent(accentColor);

            if (fadeAnimator != null) fadeAnimator.cancel();
            fadeAnimator = ValueAnimator.ofFloat(currentAlpha, 1f);
            fadeAnimator.setDuration(160);
            fadeAnimator.setInterpolator(interpolator);
            fadeAnimator.addUpdateListener(animation -> {
                currentAlpha = (float) animation.getAnimatedValue();
                invalidate();
            });
            fadeAnimator.start();
            return;
        }

        startRect.set(currentRect);
        startAccentColor = currentAccentColor;

        if (moveAnimator != null) moveAnimator.cancel();
        moveAnimator = ValueAnimator.ofFloat(0f, 1f);
        moveAnimator.setDuration(170);
        moveAnimator.setInterpolator(interpolator);
        moveAnimator.addUpdateListener(animation -> {
            float f = animation.getAnimatedFraction();
            currentRect.left = startRect.left + (targetRect.left - startRect.left) * f;
            currentRect.top = startRect.top + (targetRect.top - startRect.top) * f;
            currentRect.right = startRect.right + (targetRect.right - startRect.right) * f;
            currentRect.bottom = startRect.bottom + (targetRect.bottom - startRect.bottom) * f;

            currentAccentColor = blendColor(startAccentColor, targetAccentColor, f);
            applyAccent(currentAccentColor);

            invalidate();
        });
        moveAnimator.start();
    }

    public void hide() {
        if (currentAlpha <= 0f) return;
        if (moveAnimator != null) moveAnimator.cancel();
        if (fadeAnimator != null) fadeAnimator.cancel();
        fadeAnimator = ValueAnimator.ofFloat(currentAlpha, 0f);
        fadeAnimator.setDuration(120);
        fadeAnimator.setInterpolator(interpolator);
        fadeAnimator.addUpdateListener(animation -> {
            currentAlpha = (float) animation.getAnimatedValue();
            if (currentAlpha <= 0.01f) {
                currentAlpha = 0f;
                currentRect.setEmpty();
            }
            invalidate();
        });
        fadeAnimator.start();
    }

    public void offsetBy(float dx, float dy) {
        if (currentRect.isEmpty()) return;
        currentRect.offset(dx, dy);
        targetRect.offset(dx, dy);
        startRect.offset(dx, dy);
        invalidate();
    }

    public void setAccent(int argb) {
        currentAccentColor = argb;
        targetAccentColor = argb;
        applyAccent(argb);
        invalidate();
    }

    private void applyAccent(int argb) {
        if (argb == 0) {
            glowRgb = 0xFFFFFF;
            glowAlpha = 0x30;
            rim.setColor(0xCCFFFFFF);
        } else {
            glowRgb = argb & 0xFFFFFF;
            glowAlpha = 0x60;
            int r = (((glowRgb >> 16) & 0xFF) + 255 * 2) / 3;
            int g = (((glowRgb >> 8) & 0xFF) + 255 * 2) / 3;
            int b = ((glowRgb & 0xFF) + 255 * 2) / 3;
            rim.setColor(0xE6000000 | (r << 16) | (g << 8) | b);
        }
    }

    public void setRadius(float px) {
        radius = px;
        invalidate();
    }

    private static int blendColor(int from, int to, float ratio) {
        int r1 = (from >> 16) & 0xFF;
        int g1 = (from >> 8) & 0xFF;
        int b1 = from & 0xFF;

        int r2 = (to >> 16) & 0xFF;
        int g2 = (to >> 8) & 0xFF;
        int b2 = to & 0xFF;

        int r = (int) (r1 + (r2 - r1) * ratio);
        int g = (int) (g1 + (g2 - g1) * ratio);
        int b = (int) (b1 + (b2 - b1) * ratio);

        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (currentAlpha <= 0.01f || currentRect.isEmpty()) return;

        canvas.save();
        canvas.translate(currentRect.left, currentRect.top);

        float w = currentRect.width();
        float h = currentRect.height();
        float inset = 6f * density;
        rect.set(inset, inset, w - inset, h - inset);

        int saveAlpha = (int) (currentAlpha * 255);

        // Multi-ring soft outer glow
        int rings = 6;
        float step = inset / rings;
        glow.setStrokeWidth(step + 0.5f);
        for (int i = 0; i < rings; i++) {
            float o = step * (i + 0.5f);
            float t = 1f - (float) i / rings;
            int ringAlpha = Math.round(glowAlpha * t * currentAlpha);
            glow.setColor((ringAlpha << 24) | glowRgb);
            glowRect.set(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o);
            canvas.drawRoundRect(glowRect, radius + o, radius + o, glow);
        }

        // Translucent glass body fill
        fill.setShader(new LinearGradient(0, 0, 0, h, 0x33FFFFFF, 0x0EFFFFFF, Shader.TileMode.CLAMP));
        fill.setAlpha(saveAlpha);
        canvas.drawRoundRect(rect, radius, radius, fill);

        // Specular highlight across top 45%
        specular.setShader(new LinearGradient(0, 0, 0, h * 0.45f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        specular.setAlpha(saveAlpha);
        canvas.save();
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + h * 0.45f);
        canvas.drawRoundRect(rect, radius, radius, specular);
        canvas.restore();

        // Crisp border rim
        rim.setAlpha(saveAlpha);
        canvas.drawRoundRect(rect, radius, radius, rim);

        canvas.restore();
    }
}
