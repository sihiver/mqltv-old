package com.mqltv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LiveTvFragment extends Fragment {

    private static final String CAT_ALL = "__ALL__";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView title;
    private TextView time;
    private LiveTvCategoryAdapter categoryAdapter;
    private LiveTvChannelGridAdapter gridAdapter;
    private RecyclerView categoryList;
    private RecyclerView grid;

    private int selectedCategoryPosition = 0;

    private final List<String> categoryKeys = new ArrayList<>();
    private final List<String> categoryLabels = new ArrayList<>();

    private volatile List<Channel> allChannels;

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
        grid.addItemDecoration(new GridSpacingItemDecoration(dpToPx(v), dpToPx(v), dpToPx(v)));
        gridAdapter = new LiveTvChannelGridAdapter();
        grid.setAdapter(gridAdapter);

        // TV UX:
        // - From the first row: DPAD_UP goes to the *active* category (not a random tab).
        // - From lower rows: DPAD_UP should move within the grid (row-1), not skip to categories.
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View view) {
                view.setOnKeyListener((v1, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        if (grid == null) return false;

                        RecyclerView.LayoutManager lm = grid.getLayoutManager();
                        int spanCount = 1;
                        if (lm instanceof GridLayoutManager) {
                            spanCount = Math.max(1, ((GridLayoutManager) lm).getSpanCount());
                        }

                        int pos = grid.getChildAdapterPosition(v1);
                        if (pos != RecyclerView.NO_POSITION && pos < spanCount) {
                            focusSelectedCategory();
                            return true;
                        }
                        return false;
                    }
                    return false;
                });
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View view) {
                view.setOnKeyListener(null);
            }
        });

        load(appContext);

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    private void load(Context context) {
        executor.execute(() -> {
            PlaylistRepository repo = new PlaylistRepository();
            List<Channel> channels = repo.loadFromUrls(context, AuthPrefs.getPlaylistUrls(context));
            AuthPrefs.getPlaylistUrl(context);
            boolean hasServerPlaylist = !AuthPrefs.getPlaylistUrl(context).trim().isEmpty();
            if ((channels == null || channels.isEmpty()) && !hasServerPlaylist) {
                channels = repo.loadDefault(context);
            }
            allChannels = channels;

            final CategoryData cats = buildCategories(channels);
            mainHandler.post(() -> {
                categoryKeys.clear();
                categoryKeys.addAll(cats.keys);
                categoryLabels.clear();
                categoryLabels.addAll(cats.labels);
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

    @SuppressLint("SetTextI18n")
    private void applyCategory(Context context, int position) {
        if (position < 0 || position >= categoryKeys.size()) return;
        if (categoryAdapter != null) categoryAdapter.setSelected(position);
        selectedCategoryPosition = position;

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

    private void focusSelectedCategory() {
        if (categoryList == null) return;

        final int pos = selectedCategoryPosition;
        if (pos < 0) return;

        categoryList.scrollToPosition(pos);
        categoryList.post(() -> {
            RecyclerView.ViewHolder vh = categoryList.findViewHolderForAdapterPosition(pos);
            if (vh != null && vh.itemView != null) {
                vh.itemView.requestFocus();
            } else {
                // If not laid out yet, try again shortly.
                categoryList.postDelayed(() -> {
                    RecyclerView.ViewHolder vh2 = categoryList.findViewHolderForAdapterPosition(pos);
                    if (vh2 != null && vh2.itemView != null) {
                        vh2.itemView.requestFocus();
                    }
                }, 40);
            }
        });
    }

    private static final class CategoryData {
        final List<String> keys;
        final List<String> labels;

        CategoryData(List<String> keys, List<String> labels) {
            this.keys = keys;
            this.labels = labels;
        }
    }

    private static CategoryData buildCategories(List<Channel> channels) {
        List<String> keys = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        keys.add(CAT_ALL);
        labels.add("ALL CHANNELS");

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

    private static int dpToPx(View v) {
        float d = v.getResources().getDisplayMetrics().density;
        return Math.round(14 * d);
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
