package com.mqltv;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SearchActivity extends FragmentActivity {

    private EditText searchInput;
    private TextView emptyState;
    private RecyclerView resultsList;
    private ChannelCardAdapter adapter;

    private List<Channel> allChannels = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        if (!AuthPrefs.isLoggedIn(this)) {
            LoginGuard.ensureLoggedIn(this);
            finish();
            return;
        }

        searchInput = findViewById(R.id.search_input);
        emptyState = findViewById(R.id.search_empty_state);
        resultsList = findViewById(R.id.search_results_list);

        // Setup RecyclerView
        resultsList.setLayoutManager(new GridLayoutManager(this, 5));

        resultsList.setItemViewCacheSize(20);
        resultsList.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(android.graphics.Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
                outRect.bottom = 32; // Vertical spacing
            }
        });
        adapter = new ChannelCardAdapter();
        
        // Use default listener logic so that clicking opens the player
        adapter.setListener(new ChannelCardAdapter.Listener() {
            @Override
            public void onChannelClicked(Channel channel, int position) {
                // Focus handling is done by ChannelCardAdapter internally
            }

            @Override
            public void onChannelFocused(Channel channel, int position) {
            }
        });
        resultsList.setAdapter(adapter);

        // Request focus to search bar initially
        searchInput.requestFocus();

        // Load data in background
        loadData();

        // Listen for search queries
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                filter(s.toString());
            }
        });
    }

    private void loadData() {
        emptyState.setText("Memuat data...");
        executor.execute(() -> {
            List<Channel> data = new PlaylistRepository().loadForUser(this);
            mainHandler.post(() -> {
                allChannels.clear();
                if (data != null) {
                    allChannels.addAll(data);
                }
                // Trigger initial filter based on current text
                filter(searchInput.getText().toString());
            });
        });
    }

    private void filter(String query) {
        if (allChannels.isEmpty()) {
            emptyState.setText("Tidak ada data saluran tersedia.");
            emptyState.setVisibility(View.VISIBLE);
            resultsList.setVisibility(View.GONE);
            return;
        }

        String q = query.toLowerCase().trim();
        if (q.isEmpty()) {
            emptyState.setText("Ketik sesuatu untuk memulai pencarian.");
            emptyState.setVisibility(View.VISIBLE);
            resultsList.setVisibility(View.GONE);
            adapter.submit(new ArrayList<>());
            return;
        }

        List<Channel> filtered = new ArrayList<>();
        for (Channel c : allChannels) {
            if (c == null) continue;
            String name = c.getTitle() != null ? c.getTitle().toLowerCase() : "";
            String group = c.getGroupTitle() != null ? c.getGroupTitle().toLowerCase() : "";
            
            if (name.contains(q) || group.contains(q)) {
                filtered.add(c);
            }
        }

        if (filtered.isEmpty()) {
            emptyState.setText("Pencarian tidak ditemukan.");
            emptyState.setVisibility(View.VISIBLE);
            resultsList.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            resultsList.setVisibility(View.VISIBLE);
        }

        adapter.submit(filtered);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
