package com.mqltv;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
        return loadRaw(context);
    }

    /**
     * Returns recent channels that still exist in the current playlist.
     * Also removes stale entries from persistent storage.
     */
    public static List<Channel> loadSyncedWithPlaylist(Context context, @Nullable List<Channel> playlist) {
        List<Channel> recent = loadRaw(context);
        if (playlist == null || playlist.isEmpty()) {
            if (!recent.isEmpty()) {
                saveAll(context, new ArrayList<>());
            }
            return new ArrayList<>();
        }
        List<Channel> filtered = filterByPlaylist(recent, playlist);
        if (filtered.size() != recent.size()) {
            saveAll(context, filtered);
        }
        return filtered;
    }

    /** Drops recent entries that are no longer in the playlist and persists the result. */
    public static void pruneAgainstPlaylist(Context context, @Nullable List<Channel> playlist) {
        loadSyncedWithPlaylist(context, playlist);
    }

    public static List<Channel> filterByPlaylist(List<Channel> recent, List<Channel> playlist) {
        List<Channel> out = new ArrayList<>();
        if (recent == null || recent.isEmpty()) return out;
        if (playlist == null || playlist.isEmpty()) return out;

        Set<String> playlistKeys = buildPlaylistKeys(playlist);
        for (Channel c : recent) {
            if (c == null) continue;
            String key = channelIdentityKey(c);
            if (key != null && playlistKeys.contains(key)) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<Channel> loadRaw(Context context) {
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

    private static void saveAll(Context context, List<Channel> channels) {
        if (context == null) return;
        SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray out = new JSONArray();
        if (channels != null) {
            for (Channel c : channels) {
                if (c == null) continue;
                out.put(toJson(c));
            }
        }
        sp.edit().putString(KEY, out.toString()).apply();
    }

    /** Same identity rules as {@link PlaylistRepository} dedup keys. */
    @Nullable
    private static String channelIdentityKey(Channel c) {
        if (c == null) return null;
        String sourceId = c.getSourceId();
        String url = c.getUrl();
        String title = c.getTitle();
        String titleKey = title != null ? title.trim().toLowerCase(Locale.US) : "";
        if (sourceId != null && !sourceId.trim().isEmpty()) {
            return "id:" + sourceId.trim();
        }
        if (url != null && !url.trim().isEmpty()) {
            return "u:" + url.trim() + "|t:" + titleKey;
        }
        if (!titleKey.isEmpty()) {
            return "t:" + titleKey;
        }
        return null;
    }

    private static Set<String> buildPlaylistKeys(List<Channel> playlist) {
        Set<String> keys = new HashSet<>();
        for (Channel c : playlist) {
            String key = channelIdentityKey(c);
            if (key != null) keys.add(key);
        }
        return keys;
    }

    private static JSONObject toJson(Channel c) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", c.getId());
            o.put("title", nullToEmpty(c.getTitle()));
            o.put("logo", nullToEmpty(c.getLogoUrl()));
            o.put("group", nullToEmpty(c.getGroupTitle()));
            o.put("isLive", c.isLive());
            o.put("viewerCount", c.getViewerCount());
            o.put("ts", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        return o;
    }

    private static Channel fromJson(JSONObject o) {
        if (o == null) return null;
        String title = o.optString("title", "");
        if (TextUtils.isEmpty(title)) return null;

        int id = o.optInt("id", 0);
        String url = o.optString("url", "");
        String logo = o.optString("logo", "");
        String group = o.optString("group", "");
        boolean isLive = o.optBoolean("isLive", false);
        int viewerCount = o.optInt("viewerCount", 0);

        Channel c = new Channel(id, title, group, logo, isLive, viewerCount);
        c.setUrl(url);
        return c;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
