package com.mqltv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

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

    /**
     * Creates a play intent that respects the "Putar di MX Player" setting.
     * Falls back to the internal player if MX Player isn't installed.
     */
    public static Intent createPreferredPlayIntent(Context context, String title, String url) {
        if (PlaybackPrefs.isUseMxPlayer(context)) {
            Intent mx = createMxPlayIntent(context, title, url);
            if (mx != null) return mx;
        }
        return createPlayIntent(context, title, url);
    }

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
        // Common MX Player package names (Free/Pro/TV variants)
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
