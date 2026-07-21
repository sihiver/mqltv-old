package com.mqltv;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class PlaylistRepository {
    private static final String TAG = "PlaylistRepo";

    public List<Channel> loadForUser(Context context) {
        return loadForUserInternal(context, 0);
    }

    private List<Channel> loadForUserInternal(Context context, int attempt) {
        String baseUrl = AuthPrefs.getBaseUrl(context);
        if (baseUrl == null || baseUrl.isEmpty()) {
            return Collections.emptyList();
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
                    return Collections.emptyList();
                }

                ResponseBody body = response.body();
                if (body == null) return Collections.emptyList();

                String jsonStr = body.string();
                JSONObject json = new JSONObject(jsonStr);
                JSONArray data = json.optJSONArray("data");
                if (data == null || data.length() == 0) {
                    if (attempt < 2) {
                        Log.w(TAG, "Channels array empty, retrying attempt " + (attempt + 1));
                        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                        return loadForUserInternal(context, attempt + 1);
                    }
                    return Collections.emptyList();
                }

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
        } catch (Exception e) {
            Log.e(TAG, "Error fetching channels (attempt " + (attempt + 1) + ")", e);
            if (attempt < 2) {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                return loadForUserInternal(context, attempt + 1);
            }
            return Collections.emptyList();
        }
    }

    public List<Channel> loadDefault(Context context) {
        // Fallback untuk old code yang masih memanggil loadDefault.
        // Di MQLTV2 tidak ada offline default, jadi kita return loadForUser.
        return loadForUser(context);
    }
}
