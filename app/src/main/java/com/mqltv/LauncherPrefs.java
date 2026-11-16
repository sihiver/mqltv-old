package com.mqltv;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public final class LauncherPrefs {
    private LauncherPrefs() {}

    public static final String PREF_CLOCK_24H = "pref_launcher_clock_24h";
    public static final String PREF_SHOW_RECENT = "pref_launcher_show_recent";
    public static final String PREF_WALLPAPER_DIM = "pref_launcher_wallpaper_dim";

    public static final int WALLPAPER_DIM_LIGHT = 0;
    public static final int WALLPAPER_DIM_NORMAL = 1;
    public static final int WALLPAPER_DIM_DARK = 2;

    private static SharedPreferences sp(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public static boolean isClock24Hour(Context context) {
        return sp(context).getBoolean(PREF_CLOCK_24H, true);
    }

    public static void setClock24Hour(Context context, boolean is24h) {
        sp(context).edit().putBoolean(PREF_CLOCK_24H, is24h).apply();
    }

    public static boolean isShowRecentChannels(Context context) {
        return sp(context).getBoolean(PREF_SHOW_RECENT, true);
    }

    public static void setShowRecentChannels(Context context, boolean show) {
        sp(context).edit().putBoolean(PREF_SHOW_RECENT, show).apply();
    }

    public static int getWallpaperDimLevel(Context context) {
        int val = sp(context).getInt(PREF_WALLPAPER_DIM, WALLPAPER_DIM_NORMAL);
        if (val < WALLPAPER_DIM_LIGHT || val > WALLPAPER_DIM_DARK) {
            return WALLPAPER_DIM_NORMAL;
        }
        return val;
    }

    public static void setWallpaperDimLevel(Context context, int level) {
        int clamped = Math.max(WALLPAPER_DIM_LIGHT, Math.min(WALLPAPER_DIM_DARK, level));
        sp(context).edit().putInt(PREF_WALLPAPER_DIM, clamped).apply();
    }
}

