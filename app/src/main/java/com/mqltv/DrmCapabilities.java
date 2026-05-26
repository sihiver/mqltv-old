package com.mqltv;

import android.os.Build;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Runtime DRM capability checks (platform limits on legacy STBs).
 */
public final class DrmCapabilities {

    private DrmCapabilities() {}

    /** Vision+ {@code dash-clearkey} / DASH {@code .mpd} with ClearKey DRM. */
    public static boolean isDashClearKeyContent(@Nullable ChannelPlaybackMeta meta,
                                               @Nullable String streamUrl) {
        if (meta == null || !meta.preferDashSource(streamUrl)) return false;
        String j = meta.getJenis().toLowerCase(Locale.US);
        if (j.contains("clearkey")) return true;
        String lic = meta.getUrlLicense();
        return !android.text.TextUtils.isEmpty(lic)
                && !VisionPlusDrmHelper.isHttpLicenseUrl(lic);
    }

    /** ClearKey DASH needs API 21+; API 19 STBs only get DummyExoMediaDrm. */
    public static boolean isDashClearKeyUnsupportedOnThisDevice(@Nullable ChannelPlaybackMeta meta,
                                                                @Nullable String streamUrl) {
        return Build.VERSION.SDK_INT <= 19 && isDashClearKeyContent(meta, streamUrl);
    }

    public static String getDashClearKeyUnsupportedMessage() {
        return "Channel DRM DASH ClearKey butuh Android 5.0+ (API 21). "
                + "Perangkat ini API " + Build.VERSION.SDK_INT + " (Android 4.4).";
    }
}
