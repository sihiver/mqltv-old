package com.mqltv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.annotation.Nullable;
import androidx.palette.graphics.Palette;

/**
 * Computes a dark, wallpaper-aware gradient for launcher cards.
 */
public final class LauncherCardStyle {
    public final int normalTop;
    public final int normalBottom;
    public final int focusTop;
    public final int focusBottom;
    public final int stroke;

    private LauncherCardStyle(int normalTop, int normalBottom, int focusTop, int focusBottom, int stroke) {
        this.normalTop = normalTop;
        this.normalBottom = normalBottom;
        this.focusTop = focusTop;
        this.focusBottom = focusBottom;
        this.stroke = stroke;
    }

    @Nullable
    public static LauncherCardStyle fromWallpaper(Context context, Bitmap wallpaper) {
        if (context == null || wallpaper == null) return null;

        try {
            Palette palette = Palette.from(wallpaper)
                    .clearFilters()
                    .maximumColorCount(16)
                    .generate();

            // Pick a base color from wallpaper.
            int base = pickBaseColor(palette);
            // Convert into dark UI-friendly colors.
            int normalTop = blend(0xFF101826, base, 0.18f);
            int normalBottom = blend(0xFF0C131F, base, 0.10f);

            int focusTop = blend(0xFF172235, base, 0.22f);
            int focusBottom = blend(0xFF0E1726, base, 0.14f);

            // Stroke: prefer accent-like vibrant color but keep it readable.
            int accent = pickAccentColor(palette, context);
            int stroke = withAlpha(ensureMinLuma(accent, 0.38f), 0xFF);

            return new LauncherCardStyle(normalTop, normalBottom, focusTop, focusBottom, stroke);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int pickBaseColor(Palette palette) {
        if (palette == null) return 0xFF243047;

        Palette.Swatch s = palette.getDarkMutedSwatch();
        if (s == null) s = palette.getDarkVibrantSwatch();
        if (s == null) s = palette.getMutedSwatch();
        if (s == null) s = palette.getVibrantSwatch();
        if (s == null) return 0xFF243047;
        return s.getRgb();
    }

    private static int pickAccentColor(Palette palette, Context context) {
        int fallback = 0xFF2A78FF;
        if (palette == null) return fallback;

        Palette.Swatch s = palette.getVibrantSwatch();
        if (s == null) s = palette.getLightVibrantSwatch();
        if (s == null) s = palette.getMutedSwatch();
        if (s == null) s = palette.getLightMutedSwatch();
        return s != null ? s.getRgb() : fallback;
    }

    /** Mix fg into bg with ratio (0..1). */
    private static int blend(int bg, int fg, float ratio) {
        ratio = clamp01(ratio);
        int r = (int) (Color.red(bg) * (1f - ratio) + Color.red(fg) * ratio);
        int g = (int) (Color.green(bg) * (1f - ratio) + Color.green(fg) * ratio);
        int b = (int) (Color.blue(bg) * (1f - ratio) + Color.blue(fg) * ratio);
        return Color.rgb(clamp255(r), clamp255(g), clamp255(b));
    }

    private static int withAlpha(int rgb, int alpha) {
        return (rgb & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /** Ensure the color isn't too dark for a stroke by enforcing a minimum luma. */
    private static int ensureMinLuma(int color, float minLuma) {
        float l = luma(color);
        if (l >= minLuma) return color;
        // Blend towards white to raise luma.
        float t = clamp01((minLuma - l) / (1f - l));
        return blend(color, Color.WHITE, t * 0.55f);
    }

    private static float luma(int c) {
        // sRGB relative luminance approximation (no gamma correction, good enough for UI tinting)
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(1f, v);
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }
}
