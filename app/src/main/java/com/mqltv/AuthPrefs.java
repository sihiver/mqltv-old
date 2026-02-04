package com.mqltv;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public final class AuthPrefs {
    private AuthPrefs() {}

    private static final String KEY_BASE_URL = "auth_base_url";
    private static final String KEY_USERNAME = "auth_username";
    private static final String KEY_APP_KEY = "auth_app_key";
    private static final String KEY_PLAYLIST_URL = "auth_playlist_url";

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

    public static String getAppKey(Context context) {
        String v = sp(context).getString(KEY_APP_KEY, "");
        return v == null ? "" : v;
    }

    public static String getPlaylistUrl(Context context) {
        String v = sp(context).getString(KEY_PLAYLIST_URL, "");
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

    public static void setLogin(Context context, String username, String appKey, String fullPlaylistUrl) {
        if (username == null) username = "";
        if (appKey == null) appKey = "";
        if (fullPlaylistUrl == null) fullPlaylistUrl = "";
        sp(context).edit()
                .putString(KEY_USERNAME, username.trim())
                .putString(KEY_APP_KEY, appKey.trim())
                .putString(KEY_PLAYLIST_URL, fullPlaylistUrl.trim())
                .apply();
    }

    public static void clear(Context context) {
        sp(context).edit()
                .remove(KEY_USERNAME)
                .remove(KEY_APP_KEY)
                .remove(KEY_PLAYLIST_URL)
                .apply();
    }
}
