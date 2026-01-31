package com.mqltv;

public final class Constants {
    private Constants() {}

    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_URL = "extra_url";

        // Default playlists (loaded in order and merged).
        public static final String HOME_PLAYLIST_URL_1 = "http://192.168.15.1:5140/playlist.m3u";
        public static final String HOME_PLAYLIST_URL_2 = "http://192.168.15.10:8080/mql/dindin.m3u";

        public static final String[] HOME_PLAYLIST_URLS = new String[] {
            HOME_PLAYLIST_URL_1,
            HOME_PLAYLIST_URL_2
        };

    // Background video for the Live TV card on the launcher (muted + looped).
    public static final String LAUNCHER_LIVETV_CARD_VIDEO_URL = "https://sihiver.github.io/videos/videoplayback.h264.mp4";
}
