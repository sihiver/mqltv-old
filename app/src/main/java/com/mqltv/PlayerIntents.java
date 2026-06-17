package com.mqltv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class PlayerIntents {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private PlayerIntents() {}

    public static final int PLAYER_MODE_AUTO = PlaybackPrefs.PLAYER_MODE_AUTO;
    public static final int PLAYER_MODE_EXO = PlaybackPrefs.PLAYER_MODE_EXO;
    public static final int PLAYER_MODE_VLC = PlaybackPrefs.PLAYER_MODE_VLC;
    public static final int PLAYER_MODE_NATIVE = PlaybackPrefs.PLAYER_MODE_NATIVE;

    public static void launchPlayer(Context context, Channel channel) {
        launchPlayer(context, channel, null);
    }

    public static void launchPlayer(Context context, Channel channel, Runnable onLaunched) {
        if (channel == null || context == null) return;

        EXECUTOR.execute(() -> {
            try {
                String baseUrl = AuthPrefs.getBaseUrl(context);
                String url = baseUrl + "/api/channels/" + channel.getId() + "/stream";
                Request req = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "MQLTV/1.0")
                        .build();

                try (Response resp = NetworkClient.getClient().newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        MAIN_HANDLER.post(() -> Toast.makeText(context, "Gagal memuat stream (HTTP " + resp.code() + ")", Toast.LENGTH_LONG).show());
                        return;
                    }
                    ResponseBody body = resp.body();
                    if (body == null) return;
                    
                    JSONObject json = new JSONObject(body.string());
                    String streamUrl = json.optString("streamUrl", "");
                    if (streamUrl.isEmpty()) {
                        MAIN_HANDLER.post(() -> Toast.makeText(context, "Stream tidak tersedia", Toast.LENGTH_SHORT).show());
                        return;
                    }

                    // Map MQLTV2 response to old ChannelPlaybackMeta format
                    JSONObject fakeMeta = new JSONObject();
                    fakeMeta.put("drm_type", json.optString("drmType", ""));
                    fakeMeta.put("drm_key", json.optString("drmKey", ""));
                    fakeMeta.put("user_agent", json.optString("userAgent", ""));
                    fakeMeta.put("referer", json.optString("referer", ""));
                    
                    ChannelPlaybackMeta meta = ChannelPlaybackMeta.fromVisionPlusObject(fakeMeta);

                    MAIN_HANDLER.post(() -> {
                        Intent intent = createPreferredPlayIntent(context, channel.getTitle(), streamUrl, meta);
                        if (!(context instanceof Activity)) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        }
                        context.startActivity(intent);
                        if (onLaunched != null) onLaunched.run();
                    });
                }
            } catch (Exception e) {
                MAIN_HANDLER.post(() -> Toast.makeText(context, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    public static Intent createPlayIntent(Context context, String title, String url) {
        return createPlayIntent(context, title, url, null);
    }

    public static Intent createPlayIntent(Context context, String title, String url,
                                          ChannelPlaybackMeta meta) {
        Class<?> target = getTargetPlayerActivity(context, meta);
        Intent intent = new Intent(context, target);
        intent.putExtra(Constants.EXTRA_TITLE, title);
        intent.putExtra(Constants.EXTRA_URL, url);
        if (meta != null) {
            meta.putInIntent(intent);
        }
        return intent;
    }

    // Removed createPreferredPlayIntent(Context, Channel) to force usage of launchPlayer

    /**
     * Creates a play intent that respects the "Putar di MX Player" setting.
     * Falls back to the internal player if MX Player isn't installed.
     */
    public static Intent createPreferredPlayIntent(Context context, String title, String url) {
        return createPreferredPlayIntent(context, title, url, null);
    }

    public static Intent createPreferredPlayIntent(Context context, String title, String url,
                                                   ChannelPlaybackMeta meta) {
        // Vision+ JSON (headers / DRM) must stay in-app — MX Player cannot apply header_iptv.
        if (meta == null || !meta.isActive()) {
            if (PlaybackPrefs.isUseMxPlayer(context)) {
                Intent mx = createMxPlayIntent(context, title, url);
                if (mx != null) return mx;
            }
        }
        return createPlayIntent(context, title, url, meta);
    }

    @SuppressLint("QueryPermissionsNeeded")
    private static Intent createMxPlayIntent(Context context, String title, String url) {
        if (url == null) return null;

        String mime = "video/*";
        String u = url.toLowerCase();
        if (u.contains(".m3u8")) mime = "application/x-mpegURL";
        else if (u.contains(".mpd")) mime = "application/dash+xml";

        Intent base = new Intent(Intent.ACTION_VIEW);
        base.setDataAndType(Uri.parse(url), mime);
        base.putExtra(Intent.EXTRA_TITLE, title);
        base.putExtra("title", title);

        if (!(context instanceof Activity)) {
            base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        PackageManager pm = context.getPackageManager();
        String[] packages = new String[] {
                "com.mxtech.videoplayer.ad",
                "com.mxtech.videoplayer.pro",
                "com.mxtech.videoplayer.tv",
                "com.mxtech.videoplayer",
        };

        for (String pkg : packages) {
            Intent i = new Intent(base);
            i.setPackage(pkg);
            if (i.resolveActivity(pm) != null) return i;
        }

        return null;
    }

    public static Class<?> getTargetPlayerActivity(Context context) {
        return getTargetPlayerActivity(context, null);
    }

    public static Class<?> getTargetPlayerActivity(Context context, ChannelPlaybackMeta meta) {
        // Vision+ JSON: headers and/or DRM — Media3 on all API levels (incl. API 19).
        if (meta != null && meta.isActive()) {
            return PlayerActivity.class;
        }

        int mode = PlaybackPrefs.getPlayerMode(context);
        if (mode == PlaybackPrefs.PLAYER_MODE_VLC) return VlcPlayerActivity.class;
        if (mode == PlaybackPrefs.PLAYER_MODE_EXO) return PlayerActivity.class;
        if (mode == PlaybackPrefs.PLAYER_MODE_NATIVE) return NativePlayerActivity.class;

        if (android.os.Build.VERSION.SDK_INT <= 19) {
            if (DeviceQuirks.isZteB760H()) return NativePlayerActivity.class;
            return PlayerActivity.class;
        }
        return PlayerActivity.class;
    }

    public static int getPlayerMode(Context context) {
        return PlaybackPrefs.getPlayerMode(context);
    }

    public static void setPlayerMode(Context context, int mode) {
        PlaybackPrefs.setPlayerMode(context, mode);
    }
}
