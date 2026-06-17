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
        String baseUrl = AuthPrefs.getBaseUrl(context);
        if (baseUrl == null || baseUrl.isEmpty()) {
            return Collections.emptyList();
        }

        // Ambil channel dari REST API (limit 500)
        String url = baseUrl + "/api/channels?limit=500";

        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "MQLTV/1.0")
                    .header("Accept", "application/json")
                    .build();

            try (Response response = NetworkClient.getClient().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Failed to load channels: " + response.code());
                    return Collections.emptyList();
                }

                ResponseBody body = response.body();
                if (body == null) return Collections.emptyList();

                String jsonStr = body.string();
                JSONObject json = new JSONObject(jsonStr);
                JSONArray data = json.optJSONArray("data");
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
        } catch (Exception e) {
            Log.e(TAG, "Error fetching channels", e);
            return Collections.emptyList();
        }
    }

    public List<Channel> loadDefault(Context context) {
        // Fallback untuk old code yang masih memanggil loadDefault.
        // Di MQLTV2 tidak ada offline default, jadi kita return loadForUser.
        return loadForUser(context);
    }
}
