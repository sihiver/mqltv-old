package com.mqltv;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class PlaylistRepository {
    private static final String TAG = "PlaylistRepo";
    private static final String CACHE_FILE = "channels_cache.json";

    public List<Channel> loadForUser(Context context) {
        return loadForUserInternal(context, 0);
    }

    private List<Channel> loadForUserInternal(Context context, int attempt) {
        String baseUrl = AuthPrefs.getBaseUrl(context);
        if (baseUrl == null || baseUrl.isEmpty()) {
            return loadCache(context);
        }

        // Ambil channel dari REST API (limit 10000 untuk memuat semua seperti M3U lama)
        String url = baseUrl + "/api/channels?limit=10000";

        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "MQLTV/1.0")
                    .header("Accept", "application/json")
                    .build();

            try (Response response = NetworkClient.getClient().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Failed to load channels: " + response.code() + " (attempt " + (attempt + 1) + ")");
                    if ((response.code() == 401 || response.code() == 403) && attempt < 2) {
                        AccountStatusRefresher.refresh(context, null);
                    }
                    if (attempt < 2) {
                        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                        return loadForUserInternal(context, attempt + 1);
                    }
                    // Fallback ke cache lokal agar saluran tidak kosong saat booting atau error sementara
                    return loadCache(context);
                }

                ResponseBody body = response.body();
                if (body == null) return loadCache(context);

                String jsonStr = body.string();
                JSONObject json = new JSONObject(jsonStr);
                JSONArray data = json.optJSONArray("data");
                if (data == null || data.length() == 0) {
                    if (attempt < 2) {
                        Log.w(TAG, "Channels array empty, retrying attempt " + (attempt + 1));
                        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                        return loadForUserInternal(context, attempt + 1);
                    }
                    return loadCache(context);
                }

                List<Channel> channels = parseChannels(data);
                if (!channels.isEmpty()) {
                    saveCache(context, jsonStr);
                }
                return channels;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching channels (attempt " + (attempt + 1) + ")", e);
            if (attempt < 2) {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                return loadForUserInternal(context, attempt + 1);
            }
            return loadCache(context);
        }
    }

    private List<Channel> parseChannels(JSONArray data) {
        if (data == null) return Collections.emptyList();
        List<Channel> channels = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject obj = data.optJSONObject(i);
            if (obj == null) continue;

            int id = obj.optInt("id", 0);
            String name = obj.optString("name", "Unknown");
            String category = obj.optString("category", "");
            String logoUrl = obj.optString("logo_url", "");
            boolean isLive = obj.optBoolean("is_live", false);
            int viewerCount = obj.optInt("viewer_count", 0);

            if (id > 0) {
                channels.add(new Channel(id, name, category, logoUrl, isLive, viewerCount));
            }
        }
        return channels;
    }

    private void saveCache(Context context, String jsonStr) {
        if (context == null || jsonStr == null || jsonStr.trim().isEmpty()) return;
        try {
            File file = new File(context.getApplicationContext().getFilesDir(), CACHE_FILE);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            Log.d(TAG, "Channels cached successfully (" + file.length() + " bytes)");
        } catch (Exception e) {
            Log.w(TAG, "Failed to cache channels: " + e.getMessage());
        }
    }

    private List<Channel> loadCache(Context context) {
        if (context == null) return Collections.emptyList();
        try {
            File file = new File(context.getApplicationContext().getFilesDir(), CACHE_FILE);
            if (!file.exists() || file.length() == 0) return Collections.emptyList();
            byte[] buf = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                int read = fis.read(buf);
                if (read <= 0) return Collections.emptyList();
            }

            String jsonStr = new String(buf, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            JSONArray data = json.optJSONArray("data");
            List<Channel> cached = parseChannels(data);
            if (!cached.isEmpty()) {
                Log.i(TAG, "Loaded " + cached.size() + " channels from local cache fallback");
            }
            return cached;
        } catch (Exception e) {
            Log.w(TAG, "Failed to load cached channels: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Channel> loadDefault(Context context) {
        return loadForUser(context);
    }
}
