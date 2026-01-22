package com.mqltv;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;

public final class PlaylistRepository {
    private static final String DEFAULT_ASSET = "channels.m3u";

    public List<Channel> loadDefault(Context context) {
        InputStream inputStream = null;
        try {
            inputStream = context.getAssets().open(DEFAULT_ASSET);
            return M3UParser.parse(inputStream);
        } catch (IOException e) {
            return Collections.emptyList();
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public List<Channel> loadFromUrl(Context context, String playlistUrl) {
        if (playlistUrl == null || playlistUrl.trim().isEmpty()) {
            return Collections.emptyList();
        }

        HttpURLConnection conn = null;
        InputStream inputStream = null;
        try {
            URL url = new URL(playlistUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "MQLTV/1.0");
            conn.connect();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return Collections.emptyList();
            }

            inputStream = new BufferedInputStream(conn.getInputStream());
            return M3UParser.parse(inputStream);
        } catch (IOException e) {
            return Collections.emptyList();
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
