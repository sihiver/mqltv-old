package com.mqltv;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class PlaylistRepository {
    private static final String DEFAULT_ASSET = "channels.m3u";

    public List<Channel> loadDefault(Context context) {
        try (InputStream inputStream = context.getAssets().open(DEFAULT_ASSET)) {
            List<Channel> channels = PlaylistParser.parse(inputStream);
            return dedup(channels);
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unused")
    public List<Channel> loadFromUrl(Context context, String playlistUrl) {
        if (playlistUrl == null || playlistUrl.trim().isEmpty()) {
            return Collections.emptyList();
        }

        try {
            Request request = new Request.Builder()
                    .url(playlistUrl)
                    .header("User-Agent", "MQLTV/1.0")
                    .header("Accept", "application/json, application/vnd.apple.mpegurl, */*")
                    .build();
            try (Response response = NetworkClient.getClient().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return Collections.emptyList();
                }
                ResponseBody body = response.body();
                if (body == null) {
                    return Collections.emptyList();
                }
                byte[] raw = body.bytes();
                if (raw.length == 0) {
                    return Collections.emptyList();
                }
                List<Channel> channels = parsePlaylistBytes(raw);
                return dedup(channels);
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private static List<Channel> parsePlaylistBytes(byte[] raw) throws IOException {
        String head = new String(raw, 0, Math.min(raw.length, 512), StandardCharsets.UTF_8).trim();
        if (VisionPlusPlaylistParser.looksLikeJson(head)) {
            String content = new String(raw, StandardCharsets.UTF_8);
            return VisionPlusPlaylistParser.parseContent(content);
        }
        try (InputStream in = new ByteArrayInputStream(raw)) {
            return M3UParser.parse(in);
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
            String sourceId = c.getSourceId();
            String titleKey = title != null ? title.trim().toLowerCase(Locale.US) : "";
            if (sourceId != null && !sourceId.trim().isEmpty()) {
                key = "id:" + sourceId.trim();
            } else if (url != null && !url.trim().isEmpty()) {
                // Many JSON events share the same manifest URL — include title in the key.
                key = "u:" + url.trim() + "|t:" + titleKey;
            } else if (!titleKey.isEmpty()) {
                key = "t:" + titleKey;
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
