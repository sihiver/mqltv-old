package com.mqltv;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeFragment extends Fragment {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HomeSectionAdapter adapter;
    private ProgressBar progress;
    private TextView errorText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_home, container, false);
        final Context appContext = v.getContext().getApplicationContext();

        progress = v.findViewById(R.id.home_progress);
        errorText = v.findViewById(R.id.home_error);

        RecyclerView list = v.findViewById(R.id.home_sections);
        list.setLayoutManager(new LinearLayoutManager(v.getContext(), RecyclerView.VERTICAL, false));
        adapter = new HomeSectionAdapter();
        list.setAdapter(adapter);

        load(appContext);
        return v;
    }

    private void load(Context appContext) {
        setLoading(true);
        executor.execute(() -> {
            PlaylistRepository repo = new PlaylistRepository();
            List<Channel> channels = repo.loadFromUrl(appContext, Constants.HOME_PLAYLIST_URL);
            if (channels == null || channels.isEmpty()) {
                channels = repo.loadDefault(appContext);
            }

            List<HomeSection> sections = buildSections(channels);

            mainHandler.post(() -> {
                if (adapter == null) return;
                setLoading(false);
                if (sections.isEmpty()) {
                    if (errorText != null) {
                        errorText.setText("Playlist kosong atau gagal dimuat");
                        errorText.setVisibility(View.VISIBLE);
                    }
                } else {
                    if (errorText != null) errorText.setVisibility(View.GONE);
                }
                adapter.submit(sections);
            });
        });
    }

    private void setLoading(boolean loading) {
        if (progress != null) progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (errorText != null && loading) errorText.setVisibility(View.GONE);
    }

    private static List<HomeSection> buildSections(List<Channel> channels) {
        List<HomeSection> result = new ArrayList<>();
        if (channels == null || channels.isEmpty()) return result;

        int recentCount = Math.min(5, channels.size());
        result.add(new HomeSection("Recent", new ArrayList<>(channels.subList(0, recentCount))));

        Map<String, List<Channel>> byGroup = new LinkedHashMap<>();
        for (Channel c : channels) {
            String group = c.getGroupTitle();
            if (group == null || group.trim().isEmpty()) group = "Other";
            List<Channel> list = byGroup.get(group);
            if (list == null) {
                list = new ArrayList<>();
                byGroup.put(group, list);
            }
            list.add(c);
        }

        for (Map.Entry<String, List<Channel>> e : byGroup.entrySet()) {
            if (!"Other".equals(e.getKey())) {
                result.add(new HomeSection(e.getKey(), e.getValue()));
            }
        }
        if (byGroup.containsKey("Other")) {
            result.add(new HomeSection("Other", byGroup.get("Other")));
        }
        return result;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        adapter = null;
        progress = null;
        errorText = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
