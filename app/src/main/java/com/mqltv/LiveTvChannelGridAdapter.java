package com.mqltv;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class LiveTvChannelGridAdapter extends RecyclerView.Adapter<LiveTvChannelGridAdapter.VH> {

    private static final String TAG = "LiveTvLogo";

    private static final ExecutorService IMAGE_EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
            return value.getByteCount();
        }
    };

    private final List<Channel> items = new ArrayList<>();

    public void submit(List<Channel> channels) {
        items.clear();
        if (channels != null) items.addAll(channels);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_live_tv_channel, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Channel c = items.get(position);

        holder.number.setText(String.format(Locale.US, "%03d", position + 1));

        String logoUrl = c != null ? c.getLogoUrl() : null;
        holder.logo.setImageDrawable(null);
        holder.logo.setVisibility(TextUtils.isEmpty(logoUrl) ? View.INVISIBLE : View.VISIBLE);
        holder.logo.setTag(logoUrl);

        if (!TextUtils.isEmpty(logoUrl)) {
            Bitmap cached = CACHE.get(logoUrl);
            if (cached != null) {
                holder.logo.setImageBitmap(cached);
            } else {
                loadLogoAsync(holder.logo, logoUrl);
            }
        }

        holder.itemView.setOnClickListener(v -> {
            if (c == null) return;
            RecentChannelsStore.record(v.getContext(), c);
            Intent intent = PlayerIntents.createPlayIntent(v.getContext(), c.getTitle(), c.getUrl());
            v.getContext().startActivity(intent);
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            float s = hasFocus ? 1.05f : 1.0f;
            v.animate().scaleX(s).scaleY(s).setDuration(120).start();
            v.setActivated(hasFocus);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView logo;
        final TextView number;

        VH(@NonNull View itemView) {
            super(itemView);
            logo = itemView.findViewById(R.id.live_tv_channel_logo);
            number = itemView.findViewById(R.id.live_tv_channel_number);
        }
    }

    private static void loadLogoAsync(ImageView imageView, String url) {
        IMAGE_EXECUTOR.execute(() -> {
            Bitmap bmp = downloadBitmap(url);
            if (bmp != null) {
                CACHE.put(url, bmp);
            }
            MAIN.post(() -> {
                Object tag = imageView.getTag();
                if (tag != null && tag.equals(url) && bmp != null) {
                    imageView.setImageBitmap(bmp);
                    imageView.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private static Bitmap downloadBitmap(String urlString) {
        Bitmap bmp = downloadBitmapOnce(urlString);
        if (bmp == null && urlString != null && urlString.startsWith("https://")) {
            String httpUrl = "http://" + urlString.substring("https://".length());
            Log.w(TAG, "Retry logo over HTTP: " + httpUrl);
            bmp = downloadBitmapOnce(httpUrl);
        }
        return bmp;
    }

    private static Bitmap downloadBitmapOnce(String urlString) {
        try {
            String host = null;
            try {
                host = Uri.parse(urlString).getHost();
            } catch (Exception ignored) {
            }
            Request request = new Request.Builder()
                    .url(urlString)
                    .header("User-Agent", "MQLTV/1.0")
                    .build();
            try (Response response = NetworkClient.getLogoClient(host).newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.w(TAG, "Logo HTTP " + response.code() + " for " + urlString);
                    return null;
                }
                ResponseBody body = response.body();
                if (body == null) return null;
                byte[] bytes = body.bytes();
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }
        } catch (IOException e) {
            Log.w(TAG, "Logo download failed for " + urlString + ": " + e.getMessage());
            return null;
        }
    }
}
