package com.mqltv;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ChannelCardAdapter extends RecyclerView.Adapter<ChannelCardAdapter.VH> {

    public interface Listener {
        void onChannelClicked(Channel channel, int position);

        void onChannelFocused(Channel channel, int position);
    }

    private static final String TAG = "ChannelLogo";

    private static final ExecutorService IMAGE_EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
            return value.getByteCount();
        }
    };

    private final List<Channel> items = new ArrayList<>();

    @Nullable
    private Listener listener;

    public ChannelCardAdapter() {
        setHasStableIds(true);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void submit(List<Channel> channels) {
        items.clear();
        if (channels != null) items.addAll(channels);
        notifyDataSetChanged();
    }

    public int findPositionByUrl(String url) {
        if (TextUtils.isEmpty(url)) return -1;
        for (int i = 0; i < items.size(); i++) {
            Channel c = items.get(i);
            if (c == null) continue;
            String u = c.getUrl();
            if (url.equals(u)) return i;
        }
        return -1;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_channel_card, parent, false);
        return new VH(v);
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= items.size()) return RecyclerView.NO_ID;
        Channel c = items.get(position);
        String url = c != null ? c.getUrl() : null;
        return url == null ? RecyclerView.NO_ID : url.hashCode();
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Channel c = items.get(position);
        if (c == null) return;
        holder.name.setText(c.getTitle());

        String logoUrl = c.getLogoUrl();
        holder.logo.setImageDrawable(null);
        holder.logo.setVisibility(TextUtils.isEmpty(logoUrl) ? View.GONE : View.VISIBLE);
        holder.logo.setTag(logoUrl);

        if (!TextUtils.isEmpty(logoUrl)) {
            Bitmap cached = CACHE.get(logoUrl);
            if (cached != null) {
                holder.logo.setImageBitmap(cached);
            } else {
                loadLogoAsync(holder.logo, logoUrl);
            }
        }

        View clickTarget = holder.card != null ? holder.card : holder.itemView;
        clickTarget.setOnClickListener(v -> {
            if (!LoginGuard.ensureLoggedIn(v.getContext())) return;
            if (!SubscriptionGuard.ensureNotExpired(v.getContext())) return;

            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || pos < 0 || pos >= items.size()) return;
            Channel fresh = items.get(pos);
            if (fresh == null) return;

            if (listener != null) {
                listener.onChannelClicked(fresh, pos);
            }

            RecentChannelsStore.record(v.getContext(), fresh);
            PresenceReporter.reportOnlineLaunch(v.getContext(), fresh.getTitle(), fresh.getUrl());
            Intent intent = PlayerIntents.createPreferredPlayIntent(v.getContext(), fresh.getTitle(), fresh.getUrl());
            try {
                v.getContext().startActivity(intent);
            } catch (Exception e) {
                // Fallback to internal player if external launch fails for any reason.
                v.getContext().startActivity(PlayerIntents.createPlayIntent(v.getContext(), fresh.getTitle(), fresh.getUrl()));
            }
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            float s = hasFocus ? 1.05f : 1.0f;
            v.animate().scaleX(s).scaleY(s).setDuration(120).start();
            v.setActivated(hasFocus);

            if (hasFocus && listener != null) {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || pos < 0 || pos >= items.size()) return;
                Channel fresh = items.get(pos);
                if (fresh == null) return;
                listener.onChannelFocused(fresh, pos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final LinearLayout card;
        final ImageView logo;
        final TextView name;

        VH(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.channel_card);
            logo = itemView.findViewById(R.id.channel_logo);
            name = itemView.findViewById(R.id.channel_name);
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
