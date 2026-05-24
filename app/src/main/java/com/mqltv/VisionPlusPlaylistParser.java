package com.mqltv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses Vision+ / IndiHome TV JSON playlists ({ "info": [ { "name", "hls", "jenis", ... } ] }).
 */
public final class VisionPlusPlaylistParser {
    private VisionPlusPlaylistParser() {}

    public static boolean looksLikeJson(String content) {
        if (content == null) return false;
        String t = content.trim();
        if (t.startsWith("\uFEFF")) {
            t = t.substring(1).trim();
        }
        return t.startsWith("{") || t.startsWith("[");
    }

    public static List<Channel> parse(InputStream inputStream) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return parseContent(sb.toString());
    }

    public static List<Channel> parseContent(String content) throws IOException {
        content = content != null ? content.trim() : "";
        if (content.isEmpty()) {
            throw new IOException("empty playlist");
        }

        JSONObject root;
        try {
            root = new JSONObject(content);
        } catch (Exception e) {
            // Bare array of channel objects.
            try {
                JSONArray arr = new JSONArray(content);
                return parseInfoArray(arr);
            } catch (Exception e2) {
                throw new IOException("invalid json playlist", e2);
            }
        }

        JSONArray info = root.optJSONArray("info");
        if (info == null || info.length() == 0) {
            throw new IOException("no channels in json playlist");
        }
        return parseInfoArray(info);
    }

    private static List<Channel> parseInfoArray(JSONArray info) throws IOException {
        List<Channel> out = new ArrayList<>();
        for (int i = 0; i < info.length(); i++) {
            JSONObject ch = info.optJSONObject(i);
            if (ch == null) continue;
            Channel c = channelFromJson(ch);
            if (c != null) out.add(c);
        }
        if (out.isEmpty()) {
            throw new IOException("no channels with stream url in json");
        }
        return out;
    }

    private static Channel channelFromJson(JSONObject ch) {
        String streamUrl = pickStreamUrl(ch);
        if (streamUrl == null || streamUrl.isEmpty()) return null;

        String name = ch.optString("name", "").trim();
        if (name.isEmpty()) name = ch.optString("namespace", "").trim();
        if (name.isEmpty()) name = "Channel";

        String group = ch.optString("country_name", "").trim();
        if (group.isEmpty()) group = ch.optString("namespace", "").trim();
        if (group.isEmpty()) group = ch.optString("alpha_2_code", "").trim();

        String logo = ch.optString("logo", "").trim();
        if ("-".equals(logo)) logo = "";

        String sourceId = ch.optString("id", "").trim();
        ChannelPlaybackMeta meta = ChannelPlaybackMeta.fromVisionPlusObject(ch);

        return new Channel(name, streamUrl, group, logo, sourceId, meta);
    }

    private static String pickStreamUrl(JSONObject ch) {
        String[] keys = new String[] {"hls", "url", "stream_url", "stream", "subtitle"};
        for (String key : keys) {
            String v = ch.optString(key, "").trim();
            if (isHttpUrl(v)) return v;
        }
        return null;
    }

    private static boolean isHttpUrl(String s) {
        if (s == null) return false;
        String u = s.toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }
}
