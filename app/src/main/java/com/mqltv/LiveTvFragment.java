package com.mqltv;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_live_tv, container, false);

        final Context appContext = v.getContext().getApplicationContext();

        RecyclerView list = v.findViewById(R.id.channel_list);
        list.setLayoutManager(new LinearLayoutManager(v.getContext()));
        adapter = new ChannelListAdapter();
        list.setAdapter(adapter);

        load(appContext);

        return v;
    }

    private void load(Context context) {
        executor.execute(() -> {
            List<Channel> channels = new PlaylistRepository().loadDefault(context);
            mainHandler.post(() -> {
                if (adapter != null) adapter.submit(channels);
            });
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        adapter = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
