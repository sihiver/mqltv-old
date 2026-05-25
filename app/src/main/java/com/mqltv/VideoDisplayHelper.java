package com.mqltv;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

/**
 * Maps user video scale preference to ExoPlayer / VLC layout.
 * When preference is {@link PlaybackPrefs#VIDEO_DISPLAY_FIT} and content would letterbox on a
 * 16:9 TV (SD height or display aspect narrower than 16:9), Media3 uses zoom/fill so black bars
 * on the top and bottom are avoided.
 */
public final class VideoDisplayHelper {

    private static final String TAG = "VideoDisplay";

    /** Typical SD max display height (576p). */
    public static final int SD_MAX_DISPLAY_HEIGHT = 576;
    /** 16:9 — content narrower than this letterboxes vertically on a widescreen TV. */
    public static final float WIDESCREEN_ASPECT = 16f / 9f;
    private static final float ASPECT_EPSILON = 0.02f;

    private VideoDisplayHelper() {}

    public static float displayAspect(int width, int height, float pixelWidthHeightRatio) {
        if (width <= 0 || height <= 0) return 0f;
        float par = pixelWidthHeightRatio > 0f ? pixelWidthHeightRatio : 1f;
        return (width * par) / (float) height;
    }

    /**
     * True when the stream would show vertical letterbox bars on a 16:9 screen with FIT mode.
     */
    public static boolean isSdOrNonWideContent(int width, int height, float pixelWidthHeightRatio) {
        if (width <= 0 || height <= 0) return false;
        float ar = displayAspect(width, height, pixelWidthHeightRatio);
        if (ar > 0f && ar < WIDESCREEN_ASPECT - ASPECT_EPSILON) return true;
        return height <= SD_MAX_DISPLAY_HEIGHT;
    }

    public static boolean isSdOrNonWideContent(int width, int height) {
        return isSdOrNonWideContent(width, height, 1f);
    }

    /**
     * Effective mode after SD auto-zoom rule (only upgrades Fit &rarr; Zoom for SD/non-wide).
     */
    public static int getEffectiveDisplayMode(Context context, int videoWidth, int videoHeight,
                                              float pixelWidthHeightRatio) {
        int pref = PlaybackPrefs.getVideoDisplayMode(context);
        if (pref != PlaybackPrefs.VIDEO_DISPLAY_FIT) return pref;

        // Until dimensions are known, prefer fill/zoom for live IPTV (avoids initial letterbox flash).
        if (videoWidth <= 0 || videoHeight <= 0) {
            return PlaybackPrefs.VIDEO_DISPLAY_ZOOM;
        }
        if (isSdOrNonWideContent(videoWidth, videoHeight, pixelWidthHeightRatio)) {
            return PlaybackPrefs.VIDEO_DISPLAY_ZOOM;
        }
        return pref;
    }

    public static int getEffectiveDisplayMode(Context context, int videoWidth, int videoHeight) {
        return getEffectiveDisplayMode(context, videoWidth, videoHeight, 1f);
    }

    /** True when Fit was upgraded to Zoom solely because of SD / non-wide detection. */
    public static boolean isMedia3SdAutoZoom(Context context, int videoWidth, int videoHeight,
                                             float pixelWidthHeightRatio) {
        return PlaybackPrefs.getVideoDisplayMode(context) == PlaybackPrefs.VIDEO_DISPLAY_FIT
                && getEffectiveDisplayMode(context, videoWidth, videoHeight, pixelWidthHeightRatio)
                == PlaybackPrefs.VIDEO_DISPLAY_ZOOM;
    }

    @AspectRatioFrameLayout.ResizeMode
    public static int toMedia3ResizeMode(int effectiveMode, boolean sdAutoZoom) {
        switch (effectiveMode) {
            case PlaybackPrefs.VIDEO_DISPLAY_FILL:
                return AspectRatioFrameLayout.RESIZE_MODE_FILL;
            case PlaybackPrefs.VIDEO_DISPLAY_ZOOM:
                // Some STBs apply ZOOM inconsistently on Media3; FILL removes top/bottom bars for SD.
                if (sdAutoZoom) {
                    return AspectRatioFrameLayout.RESIZE_MODE_FILL;
                }
                return AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
            case PlaybackPrefs.VIDEO_DISPLAY_FIT:
            case PlaybackPrefs.VIDEO_DISPLAY_16_9:
            case PlaybackPrefs.VIDEO_DISPLAY_4_3:
            default:
                return AspectRatioFrameLayout.RESIZE_MODE_FIT;
        }
    }

    /** Forced container aspect for 16:9 / 4:3 modes; 0 = use stream aspect. */
    public static float forcedAspectRatio(int effectiveMode) {
        if (effectiveMode == PlaybackPrefs.VIDEO_DISPLAY_16_9) return WIDESCREEN_ASPECT;
        if (effectiveMode == PlaybackPrefs.VIDEO_DISPLAY_4_3) return 4f / 3f;
        return 0f;
    }

    public static void applyToMedia3PlayerView(PlayerView playerView, Context context) {
        applyToMedia3PlayerView(playerView, context, 0, 0, 1f);
    }

    public static void applyToMedia3PlayerView(PlayerView playerView, Context context,
                                               int videoWidth, int videoHeight,
                                               float pixelWidthHeightRatio) {
        if (playerView == null || context == null) return;

        int effective = getEffectiveDisplayMode(context, videoWidth, videoHeight, pixelWidthHeightRatio);
        boolean sdAutoZoom = isMedia3SdAutoZoom(context, videoWidth, videoHeight, pixelWidthHeightRatio);
        @AspectRatioFrameLayout.ResizeMode int resizeMode = toMedia3ResizeMode(effective, sdAutoZoom);

        float forced = forcedAspectRatio(effective);
        float displayAr = forced > 0f ? forced : displayAspect(videoWidth, videoHeight, pixelWidthHeightRatio);

        AspectRatioFrameLayout contentFrame = findContentFrame(playerView);
        if (contentFrame != null) {
            if (displayAr > 0f) {
                contentFrame.setAspectRatio(displayAr);
            }
            contentFrame.setResizeMode(resizeMode);
        }
        playerView.setResizeMode(resizeMode);
        playerView.requestLayout();

        Log.d(TAG, "Media3 resize=" + resizeMode + " effective=" + effective + " sdAuto=" + sdAutoZoom
                + " size=" + videoWidth + "x" + videoHeight + " par=" + pixelWidthHeightRatio
                + " ar=" + displayAr);
    }

    @Nullable
    private static AspectRatioFrameLayout findContentFrame(PlayerView playerView) {
        View direct = playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        if (direct instanceof AspectRatioFrameLayout) {
            return (AspectRatioFrameLayout) direct;
        }
        return findAspectRatioFrameInTree(playerView);
    }

    @Nullable
    private static AspectRatioFrameLayout findAspectRatioFrameInTree(View root) {
        if (root instanceof AspectRatioFrameLayout) {
            return (AspectRatioFrameLayout) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                AspectRatioFrameLayout found = findAspectRatioFrameInTree(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Sizes a {@link android.view.SurfaceView} / {@link android.view.TextureView} inside a container (VLC, legacy Exo).
     */
    public static void applySurfaceLayout(@Nullable FrameLayout container,
                                          @Nullable View surface,
                                          Context context,
                                          int visibleWidth,
                                          int visibleHeight,
                                          int sarNum,
                                          int sarDen) {
        if (container == null || surface == null || context == null) return;
        if (visibleWidth <= 0 || visibleHeight <= 0) return;

        int sn = sarNum <= 0 ? 1 : sarNum;
        int sd = sarDen <= 0 ? 1 : sarDen;
        int w = visibleWidth * sn / sd;
        int h = visibleHeight;

        int containerW = container.getWidth();
        int containerH = container.getHeight();
        if (containerW <= 0 || containerH <= 0) return;

        float par = (float) w / (float) Math.max(1, visibleHeight);
        int effective = getEffectiveDisplayMode(context, w, h, par);
        float forced = forcedAspectRatio(effective);
        float videoAR = forced > 0f ? forced : displayAspect(w, h, 1f);
        float containerAR = (float) containerW / (float) containerH;

        boolean cover = effective == PlaybackPrefs.VIDEO_DISPLAY_FILL
                || effective == PlaybackPrefs.VIDEO_DISPLAY_ZOOM;

        int displayW;
        int displayH;
        if (cover) {
            if (containerAR < videoAR) {
                displayH = containerH;
                displayW = (int) (containerH * videoAR);
            } else {
                displayW = containerW;
                displayH = (int) (containerW / videoAR);
            }
        } else {
            if (containerAR < videoAR) {
                displayW = containerW;
                displayH = (int) (containerW / videoAR);
            } else {
                displayH = containerH;
                displayW = (int) (containerH * videoAR);
            }
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(displayW, displayH);
        lp.leftMargin = (containerW - displayW) / 2;
        lp.topMargin = (containerH - displayH) / 2;
        surface.setLayoutParams(lp);
    }
}
