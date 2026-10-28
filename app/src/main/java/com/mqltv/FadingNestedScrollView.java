package com.mqltv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

/**
 * CleanYourTV / tvOS style fading scroll container:
 * Rows smoothly fade out as they scroll up under the header/clock instead of
 * cutting off abruptly or overlapping the header text.
 * Uses DST_OUT on a hardware layer so the background wallpaper shows through.
 */
public class FadingNestedScrollView extends NestedScrollView {

    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float maxFadeLength;
    private float lastFadeHeight = -1f;
    private float lastScrollY = -1f;

    public FadingNestedScrollView(@NonNull Context context) {
        this(context, null);
    }

    public FadingNestedScrollView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FadingNestedScrollView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        maxFadeLength = 64f * density;
        fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);

        int scrollY = getScrollY();
        float fade = Math.max(0f, Math.min(maxFadeLength, scrollY));
        if (fade <= 0f) return;

        if (fade != lastFadeHeight || scrollY != lastScrollY) {
            fadePaint.setShader(new LinearGradient(
                    0f, scrollY,
                    0f, scrollY + fade,
                    0xFF000000, 0x00000000,
                    Shader.TileMode.CLAMP));
            lastFadeHeight = fade;
            lastScrollY = scrollY;
        }

        canvas.drawRect(0f, scrollY, getWidth(), scrollY + fade, fadePaint);
    }
}
