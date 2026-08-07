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
    private static final int SEND_RETRY_COUNT = 2;
    private static final long RETRY_BACKOFF_MS = 500L;

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

        // Stop any previous heartbeat and clear old state before setting new channel.
        MAIN.removeCallbacks(HEARTBEAT);
        hbTitle = channelTitle;
        hbUrl = channelUrl;

        // Send "online" and start heartbeat for the new channel.
        send(context.getApplicationContext(), "online", channelTitle, channelUrl);
        lastOnlineAtMs = android.os.SystemClock.elapsedRealtime();

        // External player launches won't call startPlayback(), so keep the presence fresh.
        MAIN.postDelayed(HEARTBEAT, HEARTBEAT_MS);
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

        // If we recently sent an "online" (within a short handoff window),
        // skip sending an "offline" to avoid a race where the old Activity
        // sends offline after a new Activity already sent online.
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastOnlineAtMs <= 3000) {
            // Quick handoff: do not clear heartbeat/state or send offline.
            return;
        }

        MAIN.removeCallbacks(HEARTBEAT);
        hbTitle = null;
        hbUrl = null;

        send(context.getApplicationContext(), "offline", null, null);
    }

    private static boolean sleepBeforeRetry(int attempt) {
        if (attempt >= SEND_RETRY_COUNT) return false;
        try {
            Thread.sleep(RETRY_BACKOFF_MS);
            return true;
        } catch (InterruptedException ignored) {
            return false;
        }
    }

    private static void send(Context context, String status, String channelTitle, String channelUrl) {
        if (context == null) return;

        String appKey = AuthPrefs.getAccessToken(context);
        if (appKey == null || appKey.trim().isEmpty()) return;

        String baseUrl = AuthPrefs.getBaseUrl(context);
        if (baseUrl == null) return;

        String endpoint = joinUrl(baseUrl, "/public/presence").trim();
        if (endpoint.isEmpty()) return;

        // Fire-and-forget; do not block UI.
        NetworkExecutors.io().execute(() -> {
            // Retry logic: attempt up to SEND_RETRY_COUNT times if network error occurs.
            int attempt = 0;
            while (attempt < SEND_RETRY_COUNT) {
                attempt++;
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
                        if (resp.isSuccessful()) {
                            return; // Success; exit retry loop.
                        }
                        
                        // If subscription is expired (403), show Expired screen
                        if (resp.code() == 403) {
                            String bodyStr = "";
                            if (resp.body() != null) {
                                try { bodyStr = resp.body().string(); } catch (Exception ignored) {}
                            }
                            if (bodyStr.toLowerCase().contains("subscription tidak aktif") || SubscriptionGuard.isExpired(context)) {
                                MAIN.post(() -> {
                                    MAIN.removeCallbacks(HEARTBEAT);
                                    hbTitle = null;
                                    hbUrl = null;
                                    SubscriptionGuard.showExpired(context);
                                });
                                return; // Stop retrying immediately
                            }
                        }

                        if (resp.code() == 401) {
                            return; // Token issue handled automatically by Authenticator; stop retrying this tick without booting user out
                        }
                        
                        // Server returned non-2xx; back off before retrying.
                        if (!sleepBeforeRetry(attempt)) return;
                    }
                } catch (IOException e) {
                    // Network error; back off before retrying.
                    if (!sleepBeforeRetry(attempt)) return;
                } catch (Throwable ignored) {
                    // Unexpected error; keep behavior non-fatal but avoid tight retry loop.
                    if (!sleepBeforeRetry(attempt)) return;
                }
            }
            // All retries exhausted or other error; silently fail (do not break playback).
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
