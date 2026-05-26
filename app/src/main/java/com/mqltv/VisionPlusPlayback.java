package com.mqltv;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.HttpDataSource;

import com.mqltv.media3.OkHttpHttpDataSource;
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
    public static HttpDataSource.Factory media3HttpFactory(Context context, @Nullable ChannelPlaybackMeta meta) {
        // OkHttp + Conscrypt (API 19) — HttpURLConnection often drops custom Referer/Origin on HLS.
        OkHttpHttpDataSource.Factory factory = new OkHttpHttpDataSource.Factory(
                NetworkClient.getClient(),
                Util.getUserAgent(context, "MQLTV"));
        Map<String, String> headers = mergedStreamHeaders(meta);
        if (!headers.isEmpty()) {
            factory.setDefaultRequestProperties(headers);
            Log.d(TAG, "Stream headers: " + headers.keySet());
        }
        return factory;
    }

    /** Headers for manifest + HLS/DASH segments. */
    public static Map<String, String> mergedStreamHeaders(@Nullable ChannelPlaybackMeta meta) {
        return parseHeaderJson(meta != null ? meta.getHeaderIptvJson() : null);
    }

    /** Apply {@code header_iptv} to LibVLC when user forces VLC mode. */
    public static void applyVlcMediaHeaders(@Nullable org.videolan.libvlc.Media media,
                                            @Nullable ChannelPlaybackMeta meta) {
        if (media == null || meta == null) return;
        Map<String, String> headers = parseHeaderJson(meta.getHeaderIptvJson());
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) continue;
            if ("User-Agent".equalsIgnoreCase(key)) {
                media.addOption(":http-user-agent=" + value);
            } else if ("Referer".equalsIgnoreCase(key)) {
                media.addOption(":http-referrer=" + value);
            } else if ("Origin".equalsIgnoreCase(key)) {
                media.addOption(":http-origin=" + value);
            }
        }
    }

    /** DRM config: UUID only — license is supplied via custom {@link DrmSessionManager}. */
    public static MediaItem buildMedia3Item(Uri uri, @Nullable ChannelPlaybackMeta meta) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(uri);
        if (meta != null && meta.requiresExoDrm()) {
            builder.setDrmConfiguration(
                    new MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build());
        }
        if (meta != null && meta.preferHlsSource(uri.toString())) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8);
        } else if (meta != null && meta.preferDashSource(uri.toString())) {
            builder.setMimeType(MimeTypes.APPLICATION_MPD);
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
        HttpDataSource.Factory http = media3HttpFactory(context, meta);
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
        String body = json.trim();
        if (body.startsWith("\uFEFF")) {
            body = body.substring(1).trim();
        }
        try {
            // Sometimes stored/exported as a JSON-encoded string.
            if (body.startsWith("\"")) {
                body = new JSONObject("{\"v\":" + body + "}").optString("v", body);
            }
            JSONObject o = new JSONObject(body);
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String v = o.optString(k, "");
                if (!TextUtils.isEmpty(k) && v != null) {
                    String trimmed = v.trim();
                    if (trimmed.isEmpty() || "none".equalsIgnoreCase(trimmed)) continue;
                    out.put(k, trimmed);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse header json: " + body, e);
        }
        return out;
    }
}
