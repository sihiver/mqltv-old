package com.mqltv;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class RecentChannelsStore {
    private static final String PREFS = "mqltv_recent";
    private static final String KEY = "recent_channels";
    private static final int MAX = 6;

    private RecentChannelsStore() {
    }

    public static void record(Context context, Channel channel) {
        if (context == null || channel == null) return;
        String url = channel.getUrl();
        if (TextUtils.isEmpty(url)) return;

        SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        JSONArray arr;
        try {
            arr = new JSONArray(sp.getString(KEY, "[]"));
        } catch (Exception e) {
            arr = new JSONArray();
        }

        JSONArray out = new JSONArray();

        out.put(toJson(channel));

        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                if (o == null) continue;
                String u = o.optString("url", "");
                if (url.equals(u)) continue;
                out.put(o);
            } catch (Exception ignored) {
            }
            if (out.length() >= MAX) break;
        }

        sp.edit().putString(KEY, out.toString()).apply();
    }

    public static List<Channel> load(Context context) {
        List<Channel> list = new ArrayList<>();
        if (context == null) return list;

        SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray arr;
        try {
            arr = new JSONArray(sp.getString(KEY, "[]"));
        } catch (Exception e) {
            return list;
        }

        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                Channel c = fromJson(o);
                if (c != null) list.add(c);
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    private static JSONObject toJson(Channel c) {
        JSONObject o = new JSONObject();
        try {
            o.put("title", nullToEmpty(c.getTitle()));
            o.put("url", nullToEmpty(c.getUrl()));
            o.put("logo", nullToEmpty(c.getLogoUrl()));
            o.put("group", nullToEmpty(c.getGroupTitle()));
            o.put("sourceId", nullToEmpty(c.getSourceId()));
            ChannelPlaybackMeta meta = c.getPlaybackMeta();
            if (meta != null && !TextUtils.isEmpty(meta.getRawJson())) {
                o.put("playbackMeta", meta.getRawJson());
            }
            o.put("ts", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        return o;
    }

    private static Channel fromJson(JSONObject o) {
        if (o == null) return null;
        String title = o.optString("title", "");
        String url = o.optString("url", "");
        String logo = o.optString("logo", "");
        String group = o.optString("group", "");
        String sourceId = o.optString("sourceId", "");
        if (TextUtils.isEmpty(url)) return null;

        ChannelPlaybackMeta meta = null;
        String metaRaw = o.optString("playbackMeta", "");
        if (!TextUtils.isEmpty(metaRaw)) {
            try {
                meta = ChannelPlaybackMeta.fromVisionPlusObject(new JSONObject(metaRaw));
            } catch (Exception ignored) {
            }
        }

        return new Channel(title, url,
                TextUtils.isEmpty(group) ? null : group,
                TextUtils.isEmpty(logo) ? null : logo,
                TextUtils.isEmpty(sourceId) ? null : sourceId,
                meta);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
