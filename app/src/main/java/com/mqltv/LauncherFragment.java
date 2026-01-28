package com.mqltv;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LauncherFragment extends Fragment implements LauncherCardAdapter.Listener {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private RecyclerView cardsList;
    private LauncherCardAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_launcher, container, false);
        Context appContext = v.getContext().getApplicationContext();

        ImageView wallpaper = v.findViewById(R.id.launcher_wallpaper);
        if (wallpaper != null) {
            executor.execute(() -> {
                Bitmap bmp = LauncherWallpaper.tryLoad(appContext);
                if (bmp != null) {
                    mainHandler.post(() -> {
                        try {
                            wallpaper.setImageBitmap(bmp);
                        } catch (Exception ignored) {
                        }
                    });

                    // Derive card gradient colors from wallpaper.
                    LauncherCardStyle style = LauncherCardStyle.fromWallpaper(appContext, bmp);
                    if (style != null) {
                        mainHandler.post(() -> {
                            if (adapter != null) {
                                adapter.setCardStyle(style);
                            }
                        });
                    }
                }
            });
        }

        View search = v.findViewById(R.id.launcher_search);
        search.setOnClickListener(view -> {
            // Placeholder for future search UI.
        });

        ImageView settings = v.findViewById(R.id.launcher_settings);
        settings.setOnClickListener(view -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openSettings();
            }
        });

        cardsList = v.findViewById(R.id.launcher_cards);
        cardsList.setLayoutManager(new LinearLayoutManager(v.getContext(), LinearLayoutManager.HORIZONTAL, false));
        cardsList.setHasFixedSize(false);
        cardsList.setItemViewCacheSize(8);
        cardsList.setClipToPadding(false);
        cardsList.setClipChildren(false);

        try {
            new StartSnapHelper().attachToRecyclerView(cardsList);
        } catch (Exception ignored) {
        }

        adapter = new LauncherCardAdapter(this);
        cardsList.setAdapter(adapter);

        // Seed cards with placeholders; subtitles will be updated after loading.
        List<LauncherCard> cards = new ArrayList<>();
        cards.add(new LauncherCard("Live TV's", "+0 Channels", android.R.drawable.ic_media_play, NavDestination.LIVE_TV));
        cards.add(new LauncherCard("Movies", "+0 Items", android.R.drawable.ic_menu_slideshow, NavDestination.MOVIES));
        cards.add(new LauncherCard("Radios", "+0 Stations", android.R.drawable.ic_btn_speak_now, NavDestination.SHOWS));
        adapter.submit(cards);

        // Default focus to first card.
        v.post(() -> {
            if (cardsList != null && cardsList.getChildCount() > 0) {
                View first = cardsList.getChildAt(0);
                if (first != null) first.requestFocus();
            }
        });

        loadCounts(appContext);
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    private void loadCounts(Context appContext) {
        executor.execute(() -> {
            PlaylistRepository repo = new PlaylistRepository();
            List<Channel> channels = repo.loadFromUrl(appContext, Constants.HOME_PLAYLIST_URL);
            if (channels == null || channels.isEmpty()) channels = repo.loadDefault(appContext);
            final int liveCount = channels != null ? channels.size() : 0;

            mainHandler.post(() -> {
                if (adapter == null) return;
                List<LauncherCard> cards = new ArrayList<>();
                cards.add(new LauncherCard("Live TV's", "+" + liveCount + " Channels", android.R.drawable.ic_media_play, NavDestination.LIVE_TV));
                cards.add(new LauncherCard("Movies", "+0 Items", android.R.drawable.ic_menu_slideshow, NavDestination.MOVIES));
                cards.add(new LauncherCard("Radios", "+0 Stations", android.R.drawable.ic_btn_speak_now, NavDestination.SHOWS));
                adapter.submit(cards);
            });
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public void onCardClicked(LauncherCard card) {
        if (card == null) return;
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).navigateTo(card.getDestination());
        }
    }
}
