package com.mqltv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Request;
import okhttp3.Response;

/**
 * Refreshes account status (plan/packages/expiresAt) from backend using appKey.
 * This fixes the case where admin renews subscription but client still has cached old expiresAt.
 */
public final class AccountStatusRefresher {
    private static final long MIN_REFRESH_INTERVAL_MS = 30_000L;

    private AccountStatusRefresher() {}

    public static void refreshIfDue(Context context) {
        if (context == null) return;
        if (!AuthPrefs.isLoggedIn(context)) return;

        long last = AuthPrefs.getLastStatusRefreshMs(context);
        long now = System.currentTimeMillis();
        if (last > 0 && (now - last) < MIN_REFRESH_INTERVAL_MS) return;
        refresh(context, null);
    }

    public static void refresh(Context context, Runnable onDone) {
        if (context == null) {
            if (onDone != null) onDone.run();
            return;
        }
        final Context app = context.getApplicationContext();
        if (!AuthPrefs.isLoggedIn(app)) {
            if (onDone != null) onDone.run();
            return;
        }

        final String baseUrl = AuthPrefs.getBaseUrl(app);
        final String appKey = AuthPrefs.getAppKey(app);
        if (baseUrl == null || baseUrl.trim().isEmpty() || appKey.trim().isEmpty()) {
            if (onDone != null) onDone.run();
            return;
        }

        final String url = normalizeBaseUrl(baseUrl) + "/public/users/" + appKey.trim() + "/status";

        new Thread(() -> {
            try {
                Request req = new Request.Builder().url(url).get().build();
                try (Response resp = NetworkClient.getClient().newCall(req).execute()) {
                    if (resp.body() == null) return;
                    String body = resp.body().string();
                    if (!resp.isSuccessful()) return;

                    JSONObject json = new JSONObject(body);
                    JSONObject user = json.optJSONObject("user");
                    if (user == null) return;

                    String expiresAt = user.optString("expiresAt", "");
                    String plan = user.optString("plan", "");
                    String displayName = user.optString("displayName", "");

                    String packagesRaw = "";
                    JSONArray pkgs = user.optJSONArray("packages");
                    if (pkgs != null && pkgs.length() > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < pkgs.length(); i++) {
                            String p = pkgs.optString(i, "");
                            if (p == null) p = "";
                            p = p.trim();
                            if (p.isEmpty()) continue;
                            if (sb.length() > 0) sb.append("||");
                            sb.append(p);
                        }
                        packagesRaw = sb.toString();
                    }

                    AuthPrefs.updateAccountStatus(app, displayName, plan, packagesRaw, expiresAt);
                    AuthPrefs.setLastStatusRefreshMs(app, System.currentTimeMillis());
                }
            } catch (IOException ignored) {
            } catch (Throwable ignored) {
            } finally {
                if (onDone != null) {
                    new Handler(Looper.getMainLooper()).post(onDone);
                }
            }
        }).start();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
}
