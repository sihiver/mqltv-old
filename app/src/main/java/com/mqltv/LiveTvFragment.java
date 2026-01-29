package com.mqltv;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LiveTvFragment extends Fragment {

    private static final String CAT_ALL = "__ALL__";
    private static final String CAT_RECENT = "__RECENT__";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView title;
    private TextView time;
    private LiveTvCategoryAdapter categoryAdapter;
    private LiveTvChannelGridAdapter gridAdapter;
    private RecyclerView categoryList;
    private RecyclerView grid;

    private final List<String> categoryKeys = new ArrayList<>();
    private final List<String> categoryLabels = new ArrayList<>();

    private volatile List<Channel> allChannels;
    private volatile List<Channel> recentChannels;

    private final Runnable timeTicker = new Runnable() {
        @Override
        public void run() {
            if (time != null) {
                try {
                    SimpleDateFormat fmt = new SimpleDateFormat("hh:mm a", Locale.getDefault());
                    time.setText(fmt.format(new Date()));
                } catch (Exception ignored) {
                }
            }
            mainHandler.postDelayed(this, 30_000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_live_tv, container, false);

        final Context appContext = v.getContext().getApplicationContext();

        title = v.findViewById(R.id.live_tv_title);
        time = v.findViewById(R.id.live_tv_time);
        mainHandler.removeCallbacks(timeTicker);
        mainHandler.post(timeTicker);

        View search = v.findViewById(R.id.live_tv_search);
        if (search != null) {
            search.setOnClickListener(view -> Toast.makeText(getContext(), "Search belum tersedia", Toast.LENGTH_SHORT).show());
        }

        categoryList = v.findViewById(R.id.live_tv_categories);
        categoryList.setLayoutManager(new LinearLayoutManager(v.getContext(), LinearLayoutManager.HORIZONTAL, false));
        categoryList.setHasFixedSize(false);
        categoryList.setItemViewCacheSize(12);
        categoryAdapter = new LiveTvCategoryAdapter(position -> applyCategory(appContext, position));
        categoryList.setAdapter(categoryAdapter);

        grid = v.findViewById(R.id.live_tv_grid);
        GridLayoutManager glm = new GridLayoutManager(v.getContext(), 6);
        grid.setLayoutManager(glm);
        grid.setHasFixedSize(false);
        grid.setItemViewCacheSize(24);
        grid.setClipToPadding(false);
        grid.addItemDecoration(new GridSpacingItemDecoration(dpToPx(v, 14), dpToPx(v, 14), dpToPx(v, 14)));
        gridAdapter = new LiveTvChannelGridAdapter();
        grid.setAdapter(gridAdapter);

        load(appContext);

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        Context ctx = getContext();
        if (ctx == null) return;

        // Refresh Recent tab content if present.
        executor.execute(() -> {
            recentChannels = RecentChannelsStore.load(ctx.getApplicationContext());
            mainHandler.post(() -> {
                int idx = indexOfKey(CAT_RECENT);
                if (idx >= 0 && categoryAdapter != null) {
                    applyCategory(ctx.getApplicationContext(), idx);
                }
            });
        });
    }

    private void load(Context context) {
        executor.execute(() -> {
            PlaylistRepository repo = new PlaylistRepository();
            List<Channel> channels = repo.loadFromUrl(context, Constants.HOME_PLAYLIST_URL);
            if (channels == null || channels.isEmpty()) {
                channels = repo.loadDefault(context);
            }
            allChannels = channels;
            recentChannels = RecentChannelsStore.load(context);

            final CategoryData cats = buildCategories(channels, recentChannels);
            mainHandler.post(() -> {
                if (categoryKeys != null) {
                    categoryKeys.clear();
                    categoryKeys.addAll(cats.keys);
                }
                if (categoryLabels != null) {
                    categoryLabels.clear();
                    categoryLabels.addAll(cats.labels);
                }
                if (categoryAdapter != null) {
                    categoryAdapter.submit(categoryLabels);
                    categoryAdapter.setSelected(0);
                }
                applyCategory(context, 0);

                // Prefer focus into the grid (like typical TV channel browsers).
                if (grid != null) {
                    grid.post(() -> {
                        if (grid.getChildCount() > 0) {
                            View first = grid.getChildAt(0);
                            if (first != null) first.requestFocus();
                        }
                    });
                }
            });
        });
    }

    private void applyCategory(Context context, int position) {
        if (position < 0 || position >= categoryKeys.size()) return;
        if (categoryAdapter != null) categoryAdapter.setSelected(position);

        String key = categoryKeys.get(position);

        if (title != null && position < categoryLabels.size()) {
            String label = categoryLabels.get(position);
            if (label != null) {
                if ("ALL CHANNELS".equalsIgnoreCase(label)) {
                    title.setText("Semua Saluran");
                } else {
                    title.setText(label);
                }
            }
        }

        List<Channel> base = allChannels;
        List<Channel> out = new ArrayList<>();

        if (CAT_ALL.equals(key)) {
            if (base != null) out.addAll(base);
        } else if (CAT_RECENT.equals(key)) {
            List<Channel> recent = recentChannels;
            if (recent != null) out.addAll(recent);
        } else {
            if (base != null) {
                for (Channel c : base) {
                    if (c == null) continue;
                    String g = c.getGroupTitle();
                    if (g == null) continue;
                    if (g.equals(key) || g.equalsIgnoreCase(key)) {
                        out.add(c);
                    }
                }
            }
        }

        if (gridAdapter != null) gridAdapter.submit(out);
        if (grid != null) grid.scrollToPosition(0);
    }

    private static final class CategoryData {
        final List<String> keys;
        final List<String> labels;

        CategoryData(List<String> keys, List<String> labels) {
            this.keys = keys;
            this.labels = labels;
        }
    }

    private static CategoryData buildCategories(List<Channel> channels, List<Channel> recent) {
        List<String> keys = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        keys.add(CAT_ALL);
        labels.add("ALL CHANNELS");

        boolean hasRecent = recent != null && !recent.isEmpty();
        if (hasRecent) {
            keys.add(CAT_RECENT);
            labels.add("RECENT");
        }

        // Keep insertion order; de-dupe by display label (case-insensitive).
        Map<String, String> seen = new LinkedHashMap<>();
        if (channels != null) {
            for (Channel c : channels) {
                if (c == null) continue;
                String g = c.getGroupTitle();
                if (g == null) continue;
                g = g.trim();
                if (g.isEmpty()) continue;
                String label = g.toUpperCase(Locale.US);
                if (!seen.containsKey(label)) {
                    seen.put(label, g);
                }
            }
        }

        for (Map.Entry<String, String> e : seen.entrySet()) {
            keys.add(e.getValue());
            labels.add(e.getKey());
        }
        return new CategoryData(keys, labels);
    }

    private int indexOfKey(String key) {
        if (key == null) return -1;
        for (int i = 0; i < categoryKeys.size(); i++) {
            if (key.equals(categoryKeys.get(i))) return i;
        }
        return -1;
    }

    private static int dpToPx(View v, int dp) {
        float d = v.getResources().getDisplayMetrics().density;
        return Math.round(dp * d);
    }

    private static final class GridSpacingItemDecoration extends RecyclerView.ItemDecoration {
        private final int spacingX;
        private final int spacingY;
        private final int edge;

        GridSpacingItemDecoration(int spacingX, int spacingY, int edge) {
            this.spacingX = spacingX;
            this.spacingY = spacingY;
            this.edge = edge;
        }

        @Override
        public void getItemOffsets(@NonNull android.graphics.Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            int pos = parent.getChildAdapterPosition(view);
            if (pos == RecyclerView.NO_POSITION) return;
            outRect.left = spacingX / 2;
            outRect.right = spacingX / 2;
            outRect.top = spacingY / 2;
            outRect.bottom = spacingY / 2;

            if (pos < 6) {
                outRect.top = edge;
            }
            if (pos % 6 == 0) {
                outRect.left = edge;
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacks(timeTicker);
        title = null;
        time = null;
        categoryAdapter = null;
        gridAdapter = null;
        categoryList = null;
        grid = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
