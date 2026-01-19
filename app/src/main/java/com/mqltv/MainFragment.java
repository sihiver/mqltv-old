package com.mqltv;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.leanback.app.BrowseSupportFragment;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ListRowPresenter;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainFragment extends BrowseSupportFragment {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setTitle("MQLTV");
        setHeadersState(HEADERS_DISABLED);
        setBrandColor(0xFF000000);

        setOnItemViewClickedListener((itemViewHolder, item, rowViewHolder, row) -> {
            if (!(item instanceof Channel)) return;
            Channel channel = (Channel) item;

            Intent intent = new Intent(requireContext(), PlayerActivity.class);
            intent.putExtra(Constants.EXTRA_TITLE, channel.getTitle());
            intent.putExtra(Constants.EXTRA_URL, channel.getUrl());
            startActivity(intent);
        });

        loadChannels();
    }

    private void loadChannels() {
        ArrayObjectAdapter rowsAdapter = new ArrayObjectAdapter(new ListRowPresenter());
        setAdapter(rowsAdapter);

        executor.execute(() -> {
            List<Channel> channels = new PlaylistRepository().loadDefault(requireContext());

            mainHandler.post(() -> {
                ArrayObjectAdapter channelAdapter = new ArrayObjectAdapter(new ChannelPresenter());
                for (Channel c : channels) {
                    channelAdapter.add(c);
                }

                HeaderItem headerItem = new HeaderItem(0, "Channels");
                rowsAdapter.add(new ListRow(headerItem, channelAdapter));
            });
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
