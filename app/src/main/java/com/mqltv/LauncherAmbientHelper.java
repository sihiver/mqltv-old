package com.mqltv;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

/**
 * CleanYourTV / tvOS style ambient spotlight glow helper.
 * Renders a softened low-resolution radial gradient on the GPU, creating a cinematic
 * color glow behind the top shelf matching the currently focused card or app.
 */
public final class LauncherAmbientHelper {

    private static final int GLOW_WIDTH = 480;
    private static final int GLOW_HEIGHT = 270;

    private final ImageView ambientView;
    private int currentColor = 0;
    private ValueAnimator colorAnimator;

    public LauncherAmbientHelper(ImageView ambientView) {
        this.ambientView = ambientView;
    }

    public void setGlowColor(int targetColor) {
        if (ambientView == null) return;
        if (targetColor == currentColor && ambientView.getDrawable() != null) return;

        final int fromColor = currentColor != 0 ? currentColor : 0x00000000;
        final int toColor = targetColor;

        if (colorAnimator != null && colorAnimator.isRunning()) {
            colorAnimator.cancel();
        }

        colorAnimator = ValueAnimator.ofFloat(0f, 1f);
        colorAnimator.setDuration(220);
        colorAnimator.setInterpolator(new DecelerateInterpolator());
        colorAnimator.addUpdateListener(anim -> {
            float frac = anim.getAnimatedFraction();
            int blended = interpolateColor(fromColor, toColor, frac);
            renderGlowBitmap(blended);
        });
        colorAnimator.start();

        currentColor = targetColor;
    }

    private void renderGlowBitmap(int color) {
        try {
            Bitmap bmp = Bitmap.createBitmap(GLOW_WIDTH, GLOW_HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

            // CleanYourTV radial spotlight originating from center-upper screen
            float cx = GLOW_WIDTH * 0.50f;
            float cy = GLOW_HEIGHT * 0.35f;
            float radius = GLOW_WIDTH * 0.65f;

            int cCore = (color & 0x00FFFFFF) | (0x75 << 24); // ~46% alpha
            int cMid  = (color & 0x00FFFFFF) | (0x25 << 24); // ~15% alpha
            int cEdge = (color & 0x00FFFFFF) | (0x00 << 24); // 0% alpha

            paint.setShader(new RadialGradient(
                    cx, cy, radius,
                    new int[]{cCore, cMid, cEdge},
                    new float[]{0.0f, 0.45f, 1.0f},
                    Shader.TileMode.CLAMP));

            canvas.drawRect(0, 0, GLOW_WIDTH, GLOW_HEIGHT, paint);
            ambientView.setImageDrawable(new BitmapDrawable(ambientView.getResources(), bmp));
        } catch (Throwable ignored) {
        }
    }

    private static int interpolateColor(int a, int b, float fraction) {
        float f = Math.max(0f, Math.min(1f, fraction));
        int aA = (a >> 24) & 0xFF;
        int aR = (a >> 16) & 0xFF;
        int aG = (a >> 8) & 0xFF;
        int aB = a & 0xFF;

        int bA = (b >> 24) & 0xFF;
        int bR = (b >> 16) & 0xFF;
        int bG = (b >> 8) & 0xFF;
        int bB = b & 0xFF;

        int rA = (int) (aA + f * (bA - aA));
        int rR = (int) (aR + f * (bR - aR));
        int rG = (int) (aG + f * (bG - aG));
        int rB = (int) (aB + f * (bB - aB));

        return (rA << 24) | (rR << 16) | (rG << 8) | rB;
    }
}
