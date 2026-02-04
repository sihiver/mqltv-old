package com.mqltv;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Sends "what channel is being watched" + online/offline status to the backend.
 *
 * Endpoint: POST {baseUrl}/public/presence
 * Body: { appKey, status, channelTitle, channelUrl }
 */
public final class PresenceReporter {
    private PresenceReporter() {}

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // Heartbeat: keep presence fresh while internal player is open.
    private static final long HEARTBEAT_MS = 30_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile String hbTitle;
    private static volatile String hbUrl;
    private static volatile long lastOnlineAtMs;

    private static final Runnable HEARTBEAT = new Runnable() {
        @Override
        public void run() {
            // Heartbeat only makes sense if we still have a channel.
            if (hbTitle == null && hbUrl == null) return;
            // We need a context for prefs; store it as app context via helper call.
            Context ctx = AppContextHolder.get();
            if (ctx != null) {
                send(ctx, "heartbeat", hbTitle, hbUrl);
                MAIN.postDelayed(this, HEARTBEAT_MS);
            }
        }
    };

    /** Call on channel click (covers external player too). */
    public static void reportOnlineLaunch(Context context, String channelTitle, String channelUrl) {
        if (context == null) return;
        AppContextHolder.init(context);
        send(context.getApplicationContext(), "online", channelTitle, channelUrl);
        lastOnlineAtMs = android.os.SystemClock.elapsedRealtime();
    }

    /** Call from internal player onStart (starts heartbeat). */
    public static void startPlayback(Context context, String channelTitle, String channelUrl) {
        if (context == null) return;
        AppContextHolder.init(context);

        hbTitle = channelTitle;
        hbUrl = channelUrl;

        // Avoid double-send if the launcher already sent an online event seconds ago.
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastOnlineAtMs > 3000) {
            send(context.getApplicationContext(), "online", channelTitle, channelUrl);
            lastOnlineAtMs = now;
        }

        MAIN.removeCallbacks(HEARTBEAT);
        MAIN.postDelayed(HEARTBEAT, HEARTBEAT_MS);
    }

    /** Call from internal player when finishing/closing. */
    public static void stopPlayback(Context context) {
        if (context == null) return;
        AppContextHolder.init(context);

        MAIN.removeCallbacks(HEARTBEAT);
        hbTitle = null;
        hbUrl = null;

        send(context.getApplicationContext(), "offline", null, null);
    }

    private static void send(Context context, String status, String channelTitle, String channelUrl) {
        if (context == null) return;

        String appKey = AuthPrefs.getAppKey(context);
        if (appKey == null || appKey.trim().isEmpty()) return;

        String baseUrl = AuthPrefs.getBaseUrl(context);
        if (baseUrl == null) return;

        String endpoint = joinUrl(baseUrl, "/public/presence");

        // Fire-and-forget; do not block UI.
        NetworkExecutors.io().execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("appKey", appKey);
                payload.put("status", status);
                if (channelTitle != null) payload.put("channelTitle", channelTitle);
                if (channelUrl != null) payload.put("channelUrl", channelUrl);

                Request req = new Request.Builder()
                        .url(endpoint)
                        .post(RequestBody.create(JSON, payload.toString()))
                        .header("Accept", "application/json")
                        .build();

                try (Response resp = NetworkClient.getClient().newCall(req).execute()) {
                    // Ignore response body.
                    if (!resp.isSuccessful()) {
                        // Keep it silent; presence must never break playback.
                    }
                }
            } catch (IOException ignored) {
            } catch (Throwable ignored) {
            }
        });
    }

    private static String joinUrl(String base, String path) {
        if (base == null) base = "";
        if (path == null) path = "";
        base = base.trim();
        path = path.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!path.startsWith("/")) path = "/" + path;
        return base + path;
    }

    /**
     * Small helper to keep an app-context without leaking Activities.
     */
    private static final class AppContextHolder {
        private static volatile Context app;

        static void init(Context ctx) {
            if (ctx == null) return;
            if (app == null) {
                app = ctx.getApplicationContext();
            }
        }

        static Context get() {
            return app;
        }
    }

    /**
     * Shared IO executor.
     */
    private static final class NetworkExecutors {
        private static volatile java.util.concurrent.ExecutorService io;

        static java.util.concurrent.ExecutorService io() {
            if (io == null) {
                synchronized (NetworkExecutors.class) {
                    if (io == null) {
                        io = java.util.concurrent.Executors.newSingleThreadExecutor();
                    }
                }
            }
            return io;
        }
    }
}
