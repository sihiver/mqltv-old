package com.mqltv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class PlayerChannelOverlayController {

    public interface PlayerLauncher {
        void play(Channel channel);
    }

    private static final String TAG = "PlayerOverlay";

    private static final ExecutorService IMAGE_EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
            return value.getByteCount();
        }
    };

    private final Activity activity;
    private final Context appContext;
    private final PlayerLauncher launcher;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private FrameLayout root;
    private View header;
    private TextView categoryText;
    private RecyclerView list;

    private ImageView infoLogoImg;
    private TextView infoLogoText;
    private TextView infoNumber;
    private TextView infoTitle;
    private ProgressBar infoProgress;
    private TextView infoNowTime;
    private TextView infoNowTitle;
    private TextView infoNextTime;
    private TextView infoNextTitle;

    private final ChannelAdapter adapter = new ChannelAdapter();

    private volatile List<Channel> allChannels = Collections.emptyList();
    private final List<String> categories = new ArrayList<>();
    private final Map<String, List<Channel>> byCategory = new LinkedHashMap<>();
    private int categoryIndex = 0;

    private String currentUrl;

    public PlayerChannelOverlayController(@NonNull Activity activity, @NonNull PlayerLauncher launcher) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();
        this.launcher = launcher;

        root = activity.findViewById(R.id.player_channel_overlay_root);
        header = activity.findViewById(R.id.player_channel_overlay_header);
        categoryText = activity.findViewById(R.id.player_channel_overlay_category);
        list = activity.findViewById(R.id.player_channel_overlay_list);

        View infoLogo = activity.findViewById(R.id.player_channel_overlay_info_logo);
        if (infoLogo != null) {
            infoLogoImg = infoLogo.findViewById(R.id.player_channel_overlay_info_logo_img);
            infoLogoText = infoLogo.findViewById(R.id.player_channel_overlay_info_logo_text);
        }
        infoNumber = activity.findViewById(R.id.player_channel_overlay_info_number);
        infoTitle = activity.findViewById(R.id.player_channel_overlay_info_title);
        infoProgress = activity.findViewById(R.id.player_channel_overlay_info_progress);
        infoNowTime = activity.findViewById(R.id.player_channel_overlay_info_now_time);
        infoNowTitle = activity.findViewById(R.id.player_channel_overlay_info_now_title);
        infoNextTime = activity.findViewById(R.id.player_channel_overlay_info_next_time);
        infoNextTitle = activity.findViewById(R.id.player_channel_overlay_info_next_title);

        if (list != null) {
            list.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.VERTICAL, false));
            list.setAdapter(adapter);
            list.setHasFixedSize(false);
            list.setItemViewCacheSize(18);
        }

        if (header != null) {
            header.setOnClickListener(v -> {
                // no-op; header is mainly for category navigation
            });
        }

        View left = activity.findViewById(R.id.player_channel_overlay_arrow_left);
        View right = activity.findViewById(R.id.player_channel_overlay_arrow_right);
        if (left != null) left.setOnClickListener(v -> prevCategory());
        if (right != null) right.setOnClickListener(v -> nextCategory());

        if (root != null) {
            root.setOnClickListener(v -> hide());
        }

        adapter.setListener(new ChannelAdapter.Listener() {
            @Override
            public void onChannelFocused(Channel c, int absoluteIndex) {
                bindInfo(c, absoluteIndex);
            }

            @Override
            public void onChannelClicked(Channel c) {
                if (c == null) return;
                hide();
                launcher.play(c);
            }
        });
    }

    public void setCurrentChannel(String url) {
        currentUrl = url;
        adapter.setCurrentUrl(url);
        if (isVisible()) {
            focusCurrentChannel();
        }
    }

    public boolean isVisible() {
        return root != null && root.getVisibility() == View.VISIBLE;
    }

    public void show() {
        if (root == null) return;
        root.setVisibility(View.VISIBLE);
        root.bringToFront();
        root.requestFocus();

        ensureLoadedThenApply();
    }

    public void hide() {
        if (root == null) return;
        root.setVisibility(View.GONE);
    }

    public void destroy() {
        try {
            worker.shutdownNow();
        } catch (Throwable ignored) {
        }
    }

    public boolean handleKeyEvent(@NonNull KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK) {
            if (isVisible()) {
                hide();
                return true;
            }
            return false;
        }

        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            if (!isVisible()) {
                show();
                return true;
            }

            // If a row is focused, treat OK as "play".
            if (list != null) {
                View f = activity.getCurrentFocus();
                int pos = f != null ? list.getChildAdapterPosition(f) : RecyclerView.NO_POSITION;
                if (pos != RecyclerView.NO_POSITION) {
                    Channel c = adapter.getItem(pos);
                    if (c != null) {
                        hide();
                        launcher.play(c);
                    }
                }
            }
            return true;
        }

        if (!isVisible()) return false;

        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            prevCategory();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            nextCategory();
            return true;
        }

        return false;
    }

    private void ensureLoadedThenApply() {
        if (allChannels != null && !allChannels.isEmpty() && !categories.isEmpty()) {
            applyCategory(categoryIndex);
            focusCurrentChannel();
            return;
        }

        worker.execute(() -> {
            List<Channel> loaded = loadChannels(appContext);
            final List<Channel> loadedFinal = (loaded != null) ? loaded : Collections.emptyList();
            final CategoryState state = buildCategories(loadedFinal);

            MAIN.post(() -> {
                allChannels = loadedFinal;
                categories.clear();
                categories.addAll(state.labels);
                byCategory.clear();
                byCategory.putAll(state.map);

                int idx = pickInitialCategoryIndex(state, currentUrl);
                if (idx < 0) idx = 0;
                categoryIndex = idx;

                applyCategory(categoryIndex);
                focusCurrentChannel();
            });
        });
    }

    private static List<Channel> loadChannels(Context context) {
        PlaylistRepository repo = new PlaylistRepository();
        String[] urls = AuthPrefs.getPlaylistUrls(context);
        List<Channel> channels = repo.loadFromUrls(context, urls);

        boolean hasServerPlaylist = false;
        try {
            hasServerPlaylist = !AuthPrefs.getPlaylistUrl(context).trim().isEmpty();
        } catch (Throwable ignored) {
        }

        if ((channels == null || channels.isEmpty()) && !hasServerPlaylist) {
            channels = repo.loadDefault(context);
        }

        return channels != null ? channels : Collections.emptyList();
    }

    private void applyCategory(int index) {
        if (categories.isEmpty()) {
            adapter.submit(Collections.emptyList());
            if (categoryText != null) categoryText.setText("Category");
            return;
        }

        if (index < 0) index = 0;
        if (index >= categories.size()) index = categories.size() - 1;
        categoryIndex = index;

        String label = categories.get(index);
        if (categoryText != null) categoryText.setText(label);

        List<Channel> listForCat = byCategory.get(label);
        if (listForCat == null) listForCat = Collections.emptyList();
        adapter.submit(listForCat);

        // Update activated states.
        adapter.setCurrentUrl(currentUrl);

        // Default info binding.
        Channel c = pickChannelToBind(listForCat, currentUrl);
        if (c != null) {
            int abs = findAbsoluteIndex(allChannels, c);
            bindInfo(c, abs);
        }
    }

    private void prevCategory() {
        if (categories.isEmpty()) return;
        int next = categoryIndex - 1;
        if (next < 0) next = categories.size() - 1;
        applyCategory(next);
        focusCurrentChannel();
    }

    private void nextCategory() {
        if (categories.isEmpty()) return;
        int next = categoryIndex + 1;
        if (next >= categories.size()) next = 0;
        applyCategory(next);
        focusCurrentChannel();
    }

    private void focusCurrentChannel() {
        if (list == null) return;

        int target = adapter.findPositionByUrl(currentUrl);
        if (target < 0) target = 0;

        final int pos = target;
        list.scrollToPosition(pos);
        list.post(() -> {
            RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(pos);
            if (vh != null && vh.itemView != null) {
                vh.itemView.requestFocus();
            } else if (header != null) {
                header.requestFocus();
            }
        });
    }

    private void bindInfo(Channel c, int absoluteIndex) {
        if (c == null) return;

        if (infoNumber != null) infoNumber.setText(String.valueOf(Math.max(1, absoluteIndex + 1)));
        if (infoTitle != null) infoTitle.setText(c.getTitle() != null ? c.getTitle() : "Channel");

        // Simple schedule placeholders: 30-minute blocks based on local time.
        long now = System.currentTimeMillis();
        long block = 30L * 60L * 1000L;
        long start = (now / block) * block;
        long end = start + block;
        long nextEnd = end + block;

        String nowRange = String.format(Locale.US, "%s - %s", fmtTime(start), fmtTime(end));
        String nextRange = String.format(Locale.US, "%s - %s", fmtTime(end), fmtTime(nextEnd));

        if (infoNowTime != null) infoNowTime.setText(nowRange);
        if (infoNextTime != null) infoNextTime.setText(nextRange);

        String group = c.getGroupTitle();
        String sub = (group == null || group.trim().isEmpty()) ? "LIVE TV" : group.trim();
        if (infoNowTitle != null) infoNowTitle.setText("Now Playing Program");
        if (infoNextTitle != null) infoNextTitle.setText("Next Program");

        if (infoProgress != null) {
            float p = (now - start) / (float) block;
            int progress = (int) (Math.max(0f, Math.min(1f, p)) * 1000f);
            infoProgress.setProgress(progress);
        }

        bindLogoInto(infoLogoImg, infoLogoText, c.getLogoUrl());
    }

    private static String fmtTime(long ms) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(ms);
        int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int m = cal.get(java.util.Calendar.MINUTE);
        return String.format(Locale.US, "%02d:%02d", h, m);
    }

    private static final class CategoryState {
        final List<String> labels;
        final Map<String, List<Channel>> map;
        CategoryState(List<String> labels, Map<String, List<Channel>> map) {
            this.labels = labels;
            this.map = map;
        }
    }

    private static CategoryState buildCategories(List<Channel> channels) {
        List<String> labels = new ArrayList<>();
        Map<String, List<Channel>> map = new LinkedHashMap<>();

        String all = "ALL CHANNELS";
        labels.add(all);
        map.put(all, channels != null ? channels : Collections.emptyList());

        if (channels != null) {
            for (Channel c : channels) {
                if (c == null) continue;
                String g = c.getGroupTitle();
                g = g == null ? "" : g.trim();
                if (g.isEmpty()) continue;
                if (!map.containsKey(g)) {
                    map.put(g, new ArrayList<>());
                    labels.add(g);
                }
                List<Channel> list = map.get(g);
                if (list != null) list.add(c);
            }
        }

        return new CategoryState(labels, map);
    }

    private static int pickInitialCategoryIndex(CategoryState state, String currentUrl) {
        if (state == null || state.labels == null || state.labels.isEmpty()) return 0;
        if (currentUrl == null || currentUrl.trim().isEmpty()) return 0;

        // Prefer the category of the current channel.
        for (Map.Entry<String, List<Channel>> e : state.map.entrySet()) {
            List<Channel> list = e.getValue();
            if (list == null) continue;
            for (Channel c : list) {
                if (c != null && currentUrl.equals(c.getUrl())) {
                    String label = e.getKey();
                    int idx = state.labels.indexOf(label);
                    return idx >= 0 ? idx : 0;
                }
            }
        }
        return 0;
    }

    private static Channel pickChannelToBind(List<Channel> list, String currentUrl) {
        if (list == null || list.isEmpty()) return null;
        if (currentUrl != null) {
            for (Channel c : list) {
                if (c != null && currentUrl.equals(c.getUrl())) return c;
            }
        }
        return list.get(0);
    }

    private static int findAbsoluteIndex(List<Channel> all, Channel target) {
        if (all == null || all.isEmpty() || target == null) return 0;
        String url = target.getUrl();
        if (url != null) {
            for (int i = 0; i < all.size(); i++) {
                Channel c = all.get(i);
                if (c != null && url.equals(c.getUrl())) return i;
            }
        }
        // Fallback: try by title.
        String t = target.getTitle();
        if (t != null) {
            for (int i = 0; i < all.size(); i++) {
                Channel c = all.get(i);
                if (c != null && t.equals(c.getTitle())) return i;
            }
        }
        return 0;
    }

    private static void bindLogoInto(ImageView imageView, TextView placeholder, String logoUrl) {
        if (imageView == null || placeholder == null) return;

        placeholder.setVisibility(View.VISIBLE);
        imageView.setImageDrawable(null);
        imageView.setVisibility(View.GONE);

        if (TextUtils.isEmpty(logoUrl)) return;

        imageView.setTag(logoUrl);
        Bitmap cached = CACHE.get(logoUrl);
        if (cached != null) {
            placeholder.setVisibility(View.GONE);
            imageView.setImageBitmap(cached);
            imageView.setVisibility(View.VISIBLE);
            return;
        }

        IMAGE_EXECUTOR.execute(() -> {
            Bitmap bmp = downloadBitmap(logoUrl);
            if (bmp != null) CACHE.put(logoUrl, bmp);
            MAIN.post(() -> {
                Object tag = imageView.getTag();
                if (tag != null && tag.equals(logoUrl) && bmp != null) {
                    placeholder.setVisibility(View.GONE);
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

    private static final class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.VH> {

        interface Listener {
            void onChannelFocused(Channel c, int absoluteIndex);
            void onChannelClicked(Channel c);
        }

        private Listener listener;
        private final List<Channel> items = new ArrayList<>();
        private String currentUrl;

        void setListener(Listener l) {
            listener = l;
        }

        void setCurrentUrl(String url) {
            currentUrl = url;
            notifyDataSetChanged();
        }

        Channel getItem(int position) {
            if (position < 0 || position >= items.size()) return null;
            return items.get(position);
        }

        int findPositionByUrl(String url) {
            if (url == null) return -1;
            for (int i = 0; i < items.size(); i++) {
                Channel c = items.get(i);
                if (c != null && url.equals(c.getUrl())) return i;
            }
            return -1;
        }

        void submit(List<Channel> channels) {
            items.clear();
            if (channels != null) items.addAll(channels);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_player_channel_overlay_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Channel c = items.get(position);

            holder.number.setText(String.valueOf(position + 1));
            holder.title.setText(c != null && c.getTitle() != null ? c.getTitle() : "Channel");

            String group = c != null ? c.getGroupTitle() : null;
            group = group == null ? "" : group.trim();
            if (group.isEmpty()) group = "LIVE TV";
            holder.subtitle.setText("Program info " + group);

            boolean isCurrent = c != null && currentUrl != null && currentUrl.equals(c.getUrl());
            holder.itemView.setActivated(isCurrent);

            // Progress indicator: just a subtle animation based on time so it looks alive.
            long now = System.currentTimeMillis();
            int p = (int) ((now / 1000L) % 1000L);
            holder.progress.setProgress(p);

            String logoUrl = c != null ? c.getLogoUrl() : null;
            bindLogoInto(holder.logoImg, holder.logoText, logoUrl);

            holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && listener != null) {
                    listener.onChannelFocused(c, position);
                }
            });

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onChannelClicked(c);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final TextView number;
            final ImageView logoImg;
            final TextView logoText;
            final TextView title;
            final TextView subtitle;
            final ProgressBar progress;

            VH(@NonNull View itemView) {
                super(itemView);
                number = itemView.findViewById(R.id.player_channel_row_number);

                View logo = itemView.findViewById(R.id.player_channel_row_logo);
                logoImg = logo != null ? logo.findViewById(R.id.player_channel_row_logo_img) : null;
                logoText = logo != null ? logo.findViewById(R.id.player_channel_row_logo_text) : null;

                title = itemView.findViewById(R.id.player_channel_row_title);
                subtitle = itemView.findViewById(R.id.player_channel_row_subtitle);
                progress = itemView.findViewById(R.id.player_channel_row_progress);
            }
        }
    }
}
