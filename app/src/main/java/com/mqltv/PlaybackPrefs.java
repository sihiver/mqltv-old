package com.mqltv;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public final class PlaybackPrefs {
    private PlaybackPrefs() {}

    public static final String PREF_PLAYER_MODE = "pref_player_mode";
    public static final int PLAYER_MODE_AUTO = 0;
    public static final int PLAYER_MODE_EXO = 1;
    public static final int PLAYER_MODE_VLC = 2;

    public static final String PREF_VLC_USE_TEXTURE = "pref_vlc_use_texture";
    public static final String PREF_VLC_HW_DECODER = "pref_vlc_hw_decoder"; // 0=auto, 1=on, 2=off
    public static final int VLC_HW_AUTO = 0;
    public static final int VLC_HW_ON = 1;
    public static final int VLC_HW_OFF = 2;

    public static final String PREF_EXO_LIMIT_480P = "pref_exo_limit_480p";

    private static SharedPreferences sp(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public static int getPlayerMode(Context context) {
        return sp(context).getInt(PREF_PLAYER_MODE, PLAYER_MODE_AUTO);
    }

    public static void setPlayerMode(Context context, int mode) {
        sp(context).edit().putInt(PREF_PLAYER_MODE, mode).apply();
    }

    public static boolean isVlcUseTexture(Context context) {
        return sp(context).getBoolean(PREF_VLC_USE_TEXTURE, true);
    }

    public static void setVlcUseTexture(Context context, boolean useTexture) {
        sp(context).edit().putBoolean(PREF_VLC_USE_TEXTURE, useTexture).apply();
    }

    public static int getVlcHwDecoderMode(Context context) {
        return sp(context).getInt(PREF_VLC_HW_DECODER, VLC_HW_AUTO);
    }

    public static void setVlcHwDecoderMode(Context context, int mode) {
        sp(context).edit().putInt(PREF_VLC_HW_DECODER, mode).apply();
    }

    public static boolean isExoLimit480p(Context context) {
        return sp(context).getBoolean(PREF_EXO_LIMIT_480P, android.os.Build.VERSION.SDK_INT <= 19);
    }

    public static void setExoLimit480p(Context context, boolean enabled) {
        sp(context).edit().putBoolean(PREF_EXO_LIMIT_480P, enabled).apply();
    }
}
