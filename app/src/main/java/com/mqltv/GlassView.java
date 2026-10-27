package com.mqltv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * A translucent glass slab: soft sheen fill, bright top specular, crisp rim and outer glow.
 * One instance floats over the grid and is smoothly moved with SpringAnimation between tiles,
 * creating the CleanYourTV / tvOS liquid glass focus transition.
 */
public class GlassView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float radius;
    private int glowRgb = 0xFFFFFF;
    private int glowAlpha = 0x30;

    public GlassView(Context context) {
        this(context, null);
    }

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 18f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.6f * density);
        rim.setColor(0xCCFFFFFF);
        glow.setStyle(Paint.Style.STROKE);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            setElevation(30f * density);
        }
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setAccent(int argb) {
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
        invalidate();
    }

    public void setRadius(float px) {
        radius = px;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fill.setShader(new LinearGradient(0, 0, 0, h, 0x33FFFFFF, 0x0EFFFFFF, Shader.TileMode.CLAMP));
        specular.setShader(new LinearGradient(0, 0, 0, h * 0.45f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        // Multi-ring soft outer glow
        int rings = 10;
        float step = inset / rings;
        glow.setStrokeWidth(step + 0.5f);
        for (int i = 0; i < rings; i++) {
            float o = step * (i + 0.5f);
            float t = 1f - (float) i / rings;
            glow.setColor((Math.round(glowAlpha * t) << 24) | glowRgb);
            canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o, radius + o, radius + o, glow);
        }

        // Translucent glass body fill
        canvas.drawRoundRect(rect, radius, radius, fill);

        // Specular highlight across top 45%
        canvas.save();
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.45f);
        canvas.drawRoundRect(rect, radius, radius, specular);
        canvas.restore();

        // Crisp border rim
        canvas.drawRoundRect(rect, radius, radius, rim);
    }
}
