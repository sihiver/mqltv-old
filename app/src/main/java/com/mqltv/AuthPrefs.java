package com.mqltv;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public final class AuthPrefs {
    private AuthPrefs() {}

    private static final String KEY_BASE_URL = "auth_base_url";
    private static final String KEY_USERNAME = "auth_username";
    private static final String KEY_DISPLAY_NAME = "auth_display_name";
    private static final String KEY_APP_KEY = "auth_app_key";
    private static final String KEY_PLAYLIST_URL = "auth_playlist_url";
    private static final String KEY_PLAN = "auth_plan";
    private static final String KEY_PACKAGES = "auth_packages";
    private static final String KEY_EXPIRES_AT = "auth_expires_at";
    private static final String KEY_LAST_STATUS_REFRESH = "auth_last_status_refresh";

    // Sensible default for local LAN deployments; user can override in login screen.
    private static final String DEFAULT_BASE_URL = "http://192.168.15.10:8088";

    private static SharedPreferences sp(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public static boolean isLoggedIn(Context context) {
        String appKey = getAppKey(context);
        return appKey != null && !appKey.trim().isEmpty();
    }

    public static String getBaseUrl(Context context) {
        String v = sp(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL);
        if (v == null) return DEFAULT_BASE_URL;
        v = v.trim();
        return v.isEmpty() ? DEFAULT_BASE_URL : v;
    }

    public static void setBaseUrl(Context context, String baseUrl) {
        if (baseUrl == null) baseUrl = "";
        sp(context).edit().putString(KEY_BASE_URL, baseUrl.trim()).apply();
    }

    public static String getUsername(Context context) {
        String v = sp(context).getString(KEY_USERNAME, "");
        return v == null ? "" : v;
    }

    public static String getDisplayName(Context context) {
        String v = sp(context).getString(KEY_DISPLAY_NAME, "");
        return v == null ? "" : v;
    }

    public static String getPackagesRaw(Context context) {
        String v = sp(context).getString(KEY_PACKAGES, "");
        return v == null ? "" : v;
    }

    public static String getPlan(Context context) {
        String v = sp(context).getString(KEY_PLAN, "");
        return v == null ? "" : v;
    }

    public static String getPackagesDisplay(Context context) {
        String raw = getPackagesRaw(context);
        raw = raw == null ? "" : raw.trim();
        if (raw.isEmpty()) return "-";
        // Stored as "name||name2" (mirrors backend join).
        return raw.replace("||", ", ");
    }

    public static String getAppKey(Context context) {
        String v = sp(context).getString(KEY_APP_KEY, "");
        return v == null ? "" : v;
    }

    public static String getPlaylistUrl(Context context) {
        String v = sp(context).getString(KEY_PLAYLIST_URL, "");
        return v == null ? "" : v;
    }

    public static String getExpiresAt(Context context) {
        String v = sp(context).getString(KEY_EXPIRES_AT, "");
        return v == null ? "" : v;
    }

    public static String[] getPlaylistUrls(Context context) {
        String url = getPlaylistUrl(context);
        if (url != null) url = url.trim();
        if (url != null && !url.isEmpty()) {
            return new String[] { url };
        }
        return Constants.HOME_PLAYLIST_URLS;
    }

    public static void setLogin(Context context, String username, String displayName, String appKey, String fullPlaylistUrl, String plan, String packagesRaw, String expiresAt) {
        if (username == null) username = "";
        if (displayName == null) displayName = "";
        if (appKey == null) appKey = "";
        if (fullPlaylistUrl == null) fullPlaylistUrl = "";
        if (plan == null) plan = "";
        if (packagesRaw == null) packagesRaw = "";
        if (expiresAt == null) expiresAt = "";
        sp(context).edit()
                .putString(KEY_USERNAME, username.trim())
                .putString(KEY_DISPLAY_NAME, displayName.trim())
                .putString(KEY_APP_KEY, appKey.trim())
                .putString(KEY_PLAYLIST_URL, fullPlaylistUrl.trim())
                .putString(KEY_PLAN, plan.trim())
                .putString(KEY_PACKAGES, packagesRaw.trim())
                .putString(KEY_EXPIRES_AT, expiresAt.trim())
                .apply();
    }

    public static long getLastStatusRefreshMs(Context context) {
        return sp(context).getLong(KEY_LAST_STATUS_REFRESH, 0L);
    }

    public static void setLastStatusRefreshMs(Context context, long ms) {
        sp(context).edit().putLong(KEY_LAST_STATUS_REFRESH, ms).apply();
    }

    /**
     * Update only account-related fields without overwriting appKey/playlist url.
     */
    public static void updateAccountStatus(Context context, String displayName, String plan, String packagesRaw, String expiresAt) {
        if (displayName == null) displayName = "";
        if (plan == null) plan = "";
        if (packagesRaw == null) packagesRaw = "";
        if (expiresAt == null) expiresAt = "";
        sp(context).edit()
                .putString(KEY_DISPLAY_NAME, displayName.trim())
                .putString(KEY_PLAN, plan.trim())
                .putString(KEY_PACKAGES, packagesRaw.trim())
                .putString(KEY_EXPIRES_AT, expiresAt.trim())
                .apply();
    }

    public static void clear(Context context) {
        sp(context).edit()
                .remove(KEY_USERNAME)
                .remove(KEY_DISPLAY_NAME)
                .remove(KEY_APP_KEY)
                .remove(KEY_PLAYLIST_URL)
                .remove(KEY_PLAN)
                .remove(KEY_PACKAGES)
                .remove(KEY_EXPIRES_AT)
                .remove(KEY_LAST_STATUS_REFRESH)
                .apply();
    }
}
