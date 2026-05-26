package com.mqltv;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.StringRes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

/**
 * Maps Vision+ quality presets to Media3 {@link DefaultTrackSelector} constraints.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlayerQualityHelper {

    public static final int QUALITY_AUTO = 0;
    public static final int QUALITY_1080 = 1;
    public static final int QUALITY_720 = 2;
    public static final int QUALITY_480 = 3;
    public static final int QUALITY_360 = 4;
    public static final int QUALITY_LOWEST = 5;

    public static final int[] ALL_QUALITIES = {
            QUALITY_AUTO,
            QUALITY_1080,
            QUALITY_720,
            QUALITY_480,
            QUALITY_360,
            QUALITY_LOWEST,
    };

    private PlayerQualityHelper() {}

    @StringRes
    public static int getLabelRes(int quality) {
        switch (quality) {
            case QUALITY_1080:
                return R.string.player_quality_1080;
            case QUALITY_720:
                return R.string.player_quality_720;
            case QUALITY_480:
                return R.string.player_quality_480;
            case QUALITY_360:
                return R.string.player_quality_360;
            case QUALITY_LOWEST:
                return R.string.player_quality_lowest;
            case QUALITY_AUTO:
            default:
                return R.string.player_quality_auto;
        }
    }

    @NonNull
    public static String getLabel(@NonNull Context context, int quality) {
        return context.getString(getLabelRes(quality));
    }

    public static void apply(@NonNull DefaultTrackSelector trackSelector, int quality) {
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters()
                .setForceLowestBitrate(false)
                .setMaxVideoBitrate(Integer.MAX_VALUE)
                .clearVideoSizeConstraints();

        switch (quality) {
            case QUALITY_1080:
                builder.setMaxVideoSize(1920, 1080);
                break;
            case QUALITY_720:
                builder.setMaxVideoSize(1280, 720);
                break;
            case QUALITY_480:
                builder.setMaxVideoSize(854, 480);
                break;
            case QUALITY_360:
                builder.setMaxVideoSize(640, 360);
                break;
            case QUALITY_LOWEST:
                builder.setForceLowestBitrate(true);
                break;
            case QUALITY_AUTO:
            default:
                break;
        }
        trackSelector.setParameters(builder.build());
    }
}
