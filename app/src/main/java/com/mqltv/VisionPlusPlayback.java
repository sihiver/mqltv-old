package com.mqltv;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.drm.ExoMediaDrm;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback;
import androidx.media3.exoplayer.drm.MediaDrmCallback;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Builds ExoPlayer media items/sources for Vision+ JSON channels (headers + ClearKey DRM).
 */
public final class VisionPlusPlayback {
    private static final String TAG = "VisionPlusPlayback";

    private VisionPlusPlayback() {}

    @OptIn(markerClass = UnstableApi.class)
    public static DefaultHttpDataSource.Factory media3HttpFactory(Context context, @Nullable ChannelPlaybackMeta meta) {
        DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory()
                .setUserAgent(Util.getUserAgent(context, "MQLTV"))
                .setAllowCrossProtocolRedirects(true);
        Map<String, String> headers = parseHeaderJson(meta != null ? meta.getHeaderIptvJson() : null);
        if (!headers.isEmpty()) {
            factory.setDefaultRequestProperties(headers);
        }
        return factory;
    }

    /** DRM config: UUID only — license is supplied via custom {@link DrmSessionManager}. */
    public static MediaItem buildMedia3Item(Uri uri, @Nullable ChannelPlaybackMeta meta) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(uri);
        if (meta != null && meta.requiresExoDrm()) {
            builder.setDrmConfiguration(
                    new MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build());
        }
        return builder.build();
    }

    @OptIn(markerClass = UnstableApi.class)
    @Nullable
    public static DrmSessionManager buildMedia3DrmSessionManager(@Nullable ChannelPlaybackMeta meta) {
        if (meta == null || !meta.requiresExoDrm() || TextUtils.isEmpty(meta.getUrlLicense())) {
            return null;
        }
        try {
            byte[] local = VisionPlusDrmHelper.buildLocalClearKeyLicense(meta);
            MediaDrmCallback callback;
            if (local != null) {
                callback = new LocalMediaDrmCallback(local);
                Log.d(TAG, "Using local ClearKey license");
            } else {
                callback = new MediaDrmCallback() {
                    @Override
                    public byte[] executeProvisionRequest(UUID uuid, ExoMediaDrm.ProvisionRequest request) {
                        throw new RuntimeException("Provision not supported");
                    }

                    @Override
                    public byte[] executeKeyRequest(UUID uuid, ExoMediaDrm.KeyRequest request) {
                        try {
                            return VisionPlusDrmHelper.executeKeyRequest(meta, request.getData());
                        } catch (IOException e) {
                            Log.e(TAG, "Key request failed", e);
                            throw new RuntimeException(e);
                        }
                    }
                };
                Log.d(TAG, "Using HTTP/custom ClearKey license");
            }
            return new DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .build(callback);
        } catch (Exception e) {
            Log.e(TAG, "Media3 DRM session setup failed", e);
            return null;
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    public static MediaSource buildMedia3Source(Context context, Uri uri, @Nullable ChannelPlaybackMeta meta) {
        DefaultHttpDataSource.Factory http = media3HttpFactory(context, meta);
        MediaItem item = buildMedia3Item(uri, meta);
        DrmSessionManager drm = buildMedia3DrmSessionManager(meta);

        if (meta != null && meta.preferDashSource(uri.toString())) {
            DashMediaSource.Factory factory = new DashMediaSource.Factory(http);
            if (drm != null) {
                factory.setDrmSessionManagerProvider(unused -> drm);
            }
            return factory.createMediaSource(item);
        }
        if (meta != null && meta.preferHlsSource(uri.toString())) {
            return new HlsMediaSource.Factory(http).createMediaSource(item);
        }

        String path = uri.toString().toLowerCase(Locale.US);
        if (path.contains(".m3u8")) {
            return new HlsMediaSource.Factory(http).createMediaSource(item);
        }
        if (path.contains(".mpd")) {
            DashMediaSource.Factory factory = new DashMediaSource.Factory(http);
            if (drm != null) {
                factory.setDrmSessionManagerProvider(unused -> drm);
            }
            return factory.createMediaSource(item);
        }
        return new ProgressiveMediaSource.Factory(http).createMediaSource(item);
    }

    static Map<String, String> parseHeaderJson(@Nullable String json) {
        Map<String, String> out = new java.util.HashMap<>();
        if (TextUtils.isEmpty(json) || "{}".equals(json.trim())) return out;
        try {
            JSONObject o = new JSONObject(json);
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String v = o.optString(k, "");
                if (!TextUtils.isEmpty(k) && v != null) {
                    out.put(k, v);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse header json", e);
        }
        return out;
    }
}
