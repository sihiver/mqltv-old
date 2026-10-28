package com.mqltv;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * TopFade: CleanYourTV style hardware layer fading edge.
 * Rows smoothly fade out as they scroll up under the header instead of cutting off abruptly.
 */
public final class TopFade extends RecyclerView.ItemDecoration {

    private final float length;
    private final Paint paint = new Paint();
    private float shaderHeight = -1f;

    public TopFade(float length) {
        this.length = length;
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    public static void attach(RecyclerView grid, float length) {
        grid.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        grid.addItemDecoration(new TopFade(length));
    }

    public static float height(int scrolled, float length) {
        return Math.max(0f, Math.min(length, scrolled));
    }

    @Override
    public void onDrawOver(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        float fade = height(parent.computeVerticalScrollOffset(), length);
        if (fade <= 0f) return;
        if (fade != shaderHeight) {
            paint.setShader(new LinearGradient(0f, 0f, 0f, fade, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
            shaderHeight = fade;
        }
        c.drawRect(0f, 0f, parent.getWidth(), fade, paint);
    }
}
