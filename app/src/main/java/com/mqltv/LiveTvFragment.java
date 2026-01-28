package com.mqltv;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LiveTvFragment extends Fragment {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ChannelListAdapter adapter;
    private ChannelCardAdapter recentAdapter;
    private RecyclerView recentList;
    private TextView recentTitle;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_live_tv, container, false);

        final Context appContext = v.getContext().getApplicationContext();

        recentTitle = v.findViewById(R.id.recent_title);
        recentList = v.findViewById(R.id.recent_list);
        recentList.setLayoutManager(new LinearLayoutManager(v.getContext(), LinearLayoutManager.HORIZONTAL, false));
        recentAdapter = new ChannelCardAdapter();
        recentList.setAdapter(recentAdapter);

        RecyclerView list = v.findViewById(R.id.channel_list);
        list.setLayoutManager(new LinearLayoutManager(v.getContext()));
        adapter = new ChannelListAdapter();
        list.setAdapter(adapter);

        load(appContext);
        refreshRecent(appContext);

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        Context ctx = getContext();
        if (ctx != null) {
            refreshRecent(ctx.getApplicationContext());
        }
    }

    private void load(Context context) {
        executor.execute(() -> {
            List<Channel> channels = new PlaylistRepository().loadDefault(context);
            mainHandler.post(() -> {
                if (adapter != null) adapter.submit(channels);
            });
        });
    }

    private void refreshRecent(Context context) {
        executor.execute(() -> {
            List<Channel> recent = RecentChannelsStore.load(context);
            mainHandler.post(() -> {
                boolean has = recent != null && !recent.isEmpty();
                if (recentTitle != null) recentTitle.setVisibility(has ? View.VISIBLE : View.GONE);
                if (recentList != null) recentList.setVisibility(has ? View.VISIBLE : View.GONE);
                if (recentAdapter != null && has) {
                    recentAdapter.submit(recent);
                }
            });
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        adapter = null;
        recentAdapter = null;
        recentList = null;
        recentTitle = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
