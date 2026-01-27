package com.mqltv;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public final class PlaybackPrefs {
    private PlaybackPrefs() {}

    public static final String PREF_PLAYER_MODE = "pref_player_mode";
    public static final int PLAYER_MODE_AUTO = 0;
    // Media3 ExoPlayer
    public static final int PLAYER_MODE_EXO = 1;
    public static final int PLAYER_MODE_VLC = 2;
    // ExoPlayer v2.4.2 (legacy)
    public static final int PLAYER_MODE_EXO_LEGACY = 3;

    public static final String PREF_VLC_USE_TEXTURE = "pref_vlc_use_texture";
    public static final String PREF_VLC_HW_DECODER = "pref_vlc_hw_decoder"; // 0=auto, 1=on, 2=off, 3=hw+
    public static final String PREF_VLC_VOUT = "pref_vlc_vout"; // 0=auto, 1=android_display, 2=android_surface, 3=gles2
    public static final int VLC_HW_AUTO = 0;
    public static final int VLC_HW_ON = 1;
    public static final int VLC_HW_OFF = 2;
    public static final int VLC_HW_PLUS = 3;

    public static final int VLC_VOUT_AUTO = 0;
    public static final int VLC_VOUT_ANDROID_DISPLAY = 1;
    public static final int VLC_VOUT_ANDROID_SURFACE = 2;
    public static final int VLC_VOUT_GLES2 = 3;

    public static final String PREF_EXO_LIMIT_480P = "pref_exo_limit_480p";
    public static final String PREF_VLC_NETWORK_CACHING = "pref_vlc_network_caching"; // in ms: 1500/3000/5000/10000
    public static final String PREF_VLC_DEINTERLACE = "pref_vlc_deinterlace";
    public static final String PREF_VLC_HW_IMPL = "pref_vlc_hw_impl"; // 0=auto, 1=mediacodec, 2=mediacodec_ndk
    public static final String PREF_VLC_HW_FORCE_ONLY = "pref_vlc_hw_force_only";

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
        return sp(context).getBoolean(PREF_VLC_USE_TEXTURE, false);
    }

    public static void setVlcUseTexture(Context context, boolean useTexture) {
        sp(context).edit().putBoolean(PREF_VLC_USE_TEXTURE, useTexture).apply();
    }

    public static int getVlcHwDecoderMode(Context context) {
        return sp(context).getInt(PREF_VLC_HW_DECODER, VLC_HW_ON);
    }

    public static void setVlcHwDecoderMode(Context context, int mode) {
        sp(context).edit().putInt(PREF_VLC_HW_DECODER, mode).apply();
    }

    public static int getVlcVout(Context context) {
        return sp(context).getInt(PREF_VLC_VOUT, VLC_VOUT_AUTO);
    }

    public static void setVlcVout(Context context, int vout) {
        sp(context).edit().putInt(PREF_VLC_VOUT, vout).apply();
    }

    public static boolean isExoLimit480p(Context context) {
        return sp(context).getBoolean(PREF_EXO_LIMIT_480P, android.os.Build.VERSION.SDK_INT <= 19);
    }

    public static void setExoLimit480p(Context context, boolean enabled) {
        sp(context).edit().putBoolean(PREF_EXO_LIMIT_480P, enabled).apply();
    }

    public static int getVlcNetworkCaching(Context context) {
        return sp(context).getInt(PREF_VLC_NETWORK_CACHING, 1500);
    }

    public static void setVlcNetworkCaching(Context context, int cachingMs) {
        sp(context).edit().putInt(PREF_VLC_NETWORK_CACHING, cachingMs).apply();
    }

    public static boolean isVlcDeinterlaceEnabled(Context context) {
        return sp(context).getBoolean(PREF_VLC_DEINTERLACE, false);
    }

    public static void setVlcDeinterlaceEnabled(Context context, boolean enabled) {
        sp(context).edit().putBoolean(PREF_VLC_DEINTERLACE, enabled).apply();
    }

    public static final int VLC_HW_IMPL_AUTO = 0;
    public static final int VLC_HW_IMPL_MEDIACODEC = 1;
    public static final int VLC_HW_IMPL_MEDIACODEC_NDK = 2;

    public static int getVlcHwDecoderImpl(Context context) {
        return sp(context).getInt(PREF_VLC_HW_IMPL, VLC_HW_IMPL_AUTO);
    }

    public static void setVlcHwDecoderImpl(Context context, int impl) {
        sp(context).edit().putInt(PREF_VLC_HW_IMPL, impl).apply();
    }

    public static boolean isVlcHwForceOnly(Context context) {
        return sp(context).getBoolean(PREF_VLC_HW_FORCE_ONLY, false);
    }

    public static void setVlcHwForceOnly(Context context, boolean forceOnly) {
        sp(context).edit().putBoolean(PREF_VLC_HW_FORCE_ONLY, forceOnly).apply();
    }
}
