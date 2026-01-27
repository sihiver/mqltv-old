package com.mqltv;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

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

        InputStream inputStream = null;
        try {
            Request request = new Request.Builder()
                    .url(playlistUrl)
                    .header("User-Agent", "MQLTV/1.0")
                    .build();
            try (Response response = NetworkClient.getClient().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return Collections.emptyList();
                }
                ResponseBody body = response.body();
                if (body == null) {
                    return Collections.emptyList();
                }
                inputStream = new BufferedInputStream(body.byteStream());
                return M3UParser.parse(inputStream);
            }
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
}
