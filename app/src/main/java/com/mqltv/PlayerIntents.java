package com.mqltv;

import android.content.Context;
import android.content.Intent;

public final class PlayerIntents {
    private PlayerIntents() {}

    // Backward-compatible constants (used by Settings UI and callers)
    public static final String PREF_PLAYER_MODE = PlaybackPrefs.PREF_PLAYER_MODE;
    public static final int PLAYER_MODE_AUTO = PlaybackPrefs.PLAYER_MODE_AUTO;
    public static final int PLAYER_MODE_EXO = PlaybackPrefs.PLAYER_MODE_EXO;
    public static final int PLAYER_MODE_VLC = PlaybackPrefs.PLAYER_MODE_VLC;
    public static final int PLAYER_MODE_EXO_LEGACY = PlaybackPrefs.PLAYER_MODE_EXO_LEGACY;

    public static Intent createPlayIntent(Context context, String title, String url) {
        Class<?> target = getTargetPlayerActivity(context);
        Intent intent = new Intent(context, target);
        intent.putExtra(Constants.EXTRA_TITLE, title);
        intent.putExtra(Constants.EXTRA_URL, url);
        return intent;
    }

    public static Class<?> getTargetPlayerActivity(Context context) {
        int mode = PlaybackPrefs.getPlayerMode(context);
        if (mode == PlaybackPrefs.PLAYER_MODE_VLC) return VlcPlayerActivity.class;
        if (mode == PlaybackPrefs.PLAYER_MODE_EXO_LEGACY) return LegacyExoPlayerActivity.class;
        if (mode == PlaybackPrefs.PLAYER_MODE_EXO) return PlayerActivity.class;

        // AUTO: prefer legacy Exo on older Android (matches STB troubleshooting)
        if (android.os.Build.VERSION.SDK_INT <= 19) return LegacyExoPlayerActivity.class;
        return PlayerActivity.class;
    }

    public static boolean shouldUseVlc(Context context) {
        int mode = PlaybackPrefs.getPlayerMode(context);
        if (mode == PlaybackPrefs.PLAYER_MODE_VLC) return true;
        if (mode == PlaybackPrefs.PLAYER_MODE_EXO) return false;

        // Explicit legacy Exo means no VLC.
        if (mode == PlaybackPrefs.PLAYER_MODE_EXO_LEGACY) return false;

        // AUTO: keep old behavior for callers that still check this.
        return false;
    }

    public static int getPlayerMode(Context context) {
        return PlaybackPrefs.getPlayerMode(context);
    }

    public static void setPlayerMode(Context context, int mode) {
        PlaybackPrefs.setPlayerMode(context, mode);
    }
}
