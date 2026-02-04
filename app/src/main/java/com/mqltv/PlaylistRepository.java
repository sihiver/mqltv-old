package com.mqltv;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class PlaylistRepository {
    private static final String DEFAULT_ASSET = "channels.m3u";
    private Context context;

    public List<Channel> loadDefault(Context context) {
        try (InputStream inputStream = context.getAssets().open(DEFAULT_ASSET)) {
            return M3UParser.parse(inputStream);
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    public List<Channel> loadFromUrl(Context context, String playlistUrl) {
        this.context = context;
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

    public List<Channel> loadFromUrls(Context context, String[] playlistUrls) {
        if (playlistUrls == null || playlistUrls.length == 0) {
            return Collections.emptyList();
        }

        List<Channel> merged = new ArrayList<>();
        for (String u : playlistUrls) {
            List<Channel> part = loadFromUrl(context, u);
            if (part != null && !part.isEmpty()) {
                merged.addAll(part);
            }
        }

        return dedup(merged);
    }

    private static List<Channel> dedup(List<Channel> channels) {
        if (channels == null || channels.isEmpty()) return Collections.emptyList();

        Map<String, Channel> out = new LinkedHashMap<>();
        for (Channel c : channels) {
            if (c == null) continue;
            String url = c.getUrl();
            String title = c.getTitle();

            String key;
            if (url != null && !url.trim().isEmpty()) {
                key = "u:" + url.trim();
            } else if (title != null && !title.trim().isEmpty()) {
                key = "t:" + title.trim().toLowerCase();
            } else {
                continue;
            }

            if (!out.containsKey(key)) {
                out.put(key, c);
            }
        }
        return new ArrayList<>(out.values());
    }
}
