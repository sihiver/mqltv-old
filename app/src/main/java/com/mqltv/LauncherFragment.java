package com.mqltv;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LauncherFragment extends Fragment implements LauncherCardAdapter.Listener {

    private static final String ARG_INITIAL_FOCUS_POSITION = "initial_focus_position";
    private static final String ARG_INITIAL_APPS_INDEX = "initial_apps_index";
    private static final String ARG_INITIAL_RECENT_URL = "initial_recent_url";
    private static final String ARG_INITIAL_RECENT_INDEX = "initial_recent_index";

    private static final int LIVE_TV_CARD_POSITION = 0;
    private static final int RADIO_CARD_POSITION = 1;
    private static final int SETTINGS_BUTTON_POSITION = 2;
    private static final int PROFILE_BUTTON_POSITION = 3;
    private static final int APPS_ROW_POSITION = 4;
    private static final int RECENT_ROW_POSITION = 5;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private RecyclerView cardsList;
    private LauncherCardAdapter adapter;
    private View searchButton;
    private View settingsButton;
    private View profileButton;
    private int lastSelectedCardPosition = LIVE_TV_CARD_POSITION;
    private int lastSelectedAppIndex = 0;

    private int lastSelectedRecentIndex = 0;
    @Nullable
    private String lastSelectedRecentUrl;

    private RecyclerView appsList;

    static LauncherFragment newInstance(int initialFocusPosition, int initialAppsIndex, @Nullable String initialRecentUrl, int initialRecentIndex) {
        LauncherFragment fragment = new LauncherFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_INITIAL_FOCUS_POSITION, initialFocusPosition);
        args.putInt(ARG_INITIAL_APPS_INDEX, Math.max(0, initialAppsIndex));
        args.putString(ARG_INITIAL_RECENT_URL, initialRecentUrl);
        args.putInt(ARG_INITIAL_RECENT_INDEX, Math.max(0, initialRecentIndex));
        fragment.setArguments(args);
        return fragment;
    }

    private LauncherAppsAdapter appsAdapter;
    private List<LauncherAppEntry> allLaunchableAppsCache;

    private TextView recentTitle;
    private RecyclerView recentList;
    private ChannelCardAdapter recentAdapter;

    private TextView headerTime;
    private ImageView headerNet;

    private final Runnable headerTicker = new Runnable() {
        @Override
        public void run() {
            try {
                if (getContext() != null) {
                    Context appContext = getContext().getApplicationContext();
                    updateHeaderTime();
                    updateNetworkIcon(appContext);
                }
            } finally {
                mainHandler.postDelayed(this, 10_000);
            }
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            lastSelectedCardPosition = args.getInt(ARG_INITIAL_FOCUS_POSITION, LIVE_TV_CARD_POSITION);
            lastSelectedAppIndex = Math.max(0, args.getInt(ARG_INITIAL_APPS_INDEX, 0));
            lastSelectedRecentUrl = args.getString(ARG_INITIAL_RECENT_URL, null);
            lastSelectedRecentIndex = Math.max(0, args.getInt(ARG_INITIAL_RECENT_INDEX, 0));
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_launcher, container, false);
        Context appContext = v.getContext().getApplicationContext();

        headerTime = v.findViewById(R.id.launcher_header_time);
        headerNet = v.findViewById(R.id.launcher_header_net);
        updateHeaderTime();
        updateNetworkIcon(appContext);
        mainHandler.removeCallbacks(headerTicker);
        mainHandler.post(headerTicker);

        ImageView wallpaper = v.findViewById(R.id.launcher_wallpaper);
        if (wallpaper != null) {
            Bitmap cachedBmp = LauncherWallpaper.getCachedBitmap();
            if (cachedBmp != null) {
                try {
                    wallpaper.setImageBitmap(cachedBmp);
                    wallpaper.setAlpha(1f);
                } catch (Exception ignored) {
                }
            } else {
                try {
                    // Avoid showing a temporary fallback image immediately to prevent blackscreen.
                    // Just leave whatever was there (transparent if new, or previous image).
                } catch (Exception ignored) {
                }
            }

            executor.execute(() -> {
                Bitmap bmp = LauncherWallpaper.tryLoad(appContext);
                if (bmp != null) {
                    if (bmp != cachedBmp) {
                        mainHandler.post(() -> {
                            try {
                                wallpaper.setImageBitmap(bmp);
                                if (wallpaper.getAlpha() < 1f) {
                                    wallpaper.animate().alpha(1f).setDuration(250).start();
                                }
                            } catch (Exception ignored) {
                            }
                        });
                    }

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

        searchButton = v.findViewById(R.id.launcher_search);
        if (searchButton != null) {
            // Enable search bar functionality
            searchButton.setVisibility(View.VISIBLE);
            searchButton.setOnClickListener(v1 -> {
                Intent i = new Intent(appContext, SearchActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(i);
            });
        }

        settingsButton = v.findViewById(R.id.launcher_settings);
        if (settingsButton != null) settingsButton.setOnClickListener(view -> {
            syncHomeFocusState(SETTINGS_BUTTON_POSITION);
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openSettings();
            }
        });
        if (settingsButton != null) {
            settingsButton.setOnFocusChangeListener((view, hasFocus) -> {
                if (hasFocus) syncHomeFocusState(SETTINGS_BUTTON_POSITION);
            });
        }

        profileButton = v.findViewById(R.id.launcher_profile);
        TextView profileLetter = v.findViewById(R.id.launcher_profile_letter);
        if (profileLetter != null) {
            String name = AuthPrefs.getDisplayName(appContext);
            if (name.trim().isEmpty()) name = AuthPrefs.getUsername(appContext);
            String letter = "?";
            name = name.trim();
            if (!name.isEmpty()) {
                letter = String.valueOf(Character.toUpperCase(name.charAt(0)));
            }
            profileLetter.setText(letter);
        }
        if (profileButton != null) {
            profileButton.setOnClickListener(view -> {
                syncHomeFocusState(PROFILE_BUTTON_POSITION);
                if (AuthPrefs.isLoggedIn(appContext)) {
                    Intent i = new Intent(appContext, AccountActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appContext.startActivity(i);
                } else {
                    // For launcher: use profile icon as login entry.
                    LoginGuard.ensureLoggedIn(appContext);
                }
            });
            profileButton.setOnFocusChangeListener((view, hasFocus) -> {
                if (hasFocus) syncHomeFocusState(PROFILE_BUTTON_POSITION);
            });
        }

        cardsList = v.findViewById(R.id.launcher_cards);
        cardsList.setLayoutManager(new LinearLayoutManager(v.getContext(), LinearLayoutManager.HORIZONTAL, false));
        cardsList.setHasFixedSize(false);
        cardsList.setItemViewCacheSize(8);
        cardsList.setClipToPadding(false);
        cardsList.setClipChildren(false);
        cardsList.setPreserveFocusAfterLayout(true);
        androidx.recyclerview.widget.RecyclerView.ItemAnimator animator1 = cardsList.getItemAnimator();
        if (animator1 instanceof androidx.recyclerview.widget.SimpleItemAnimator) {
            ((androidx.recyclerview.widget.SimpleItemAnimator) animator1).setSupportsChangeAnimations(false);
        }

        try {
            new StartSnapHelper().attachToRecyclerView(cardsList);
        } catch (Exception ignored) {
        }

        adapter = new LauncherCardAdapter(this);
        cardsList.setAdapter(adapter);
        // Pre-warm SSL client + ExoPlayer in background so video is ready by the time
        // the user scrolls to the Live TV card (no blocking on first bind).
        adapter.preWarm(v.getContext());

        appsList = v.findViewById(R.id.launcher_apps);
        if (appsList != null) {
            appsList.setLayoutManager(new LinearLayoutManager(v.getContext(), LinearLayoutManager.HORIZONTAL, false));
            appsList.setHasFixedSize(false);
            appsList.setItemViewCacheSize(16);
        appsList.setClipToPadding(false);
            appsList.setClipChildren(false);
            androidx.recyclerview.widget.RecyclerView.ItemAnimator animator2 = appsList.getItemAnimator();
            if (animator2 instanceof androidx.recyclerview.widget.SimpleItemAnimator) {
                ((androidx.recyclerview.widget.SimpleItemAnimator) animator2).setSupportsChangeAnimations(false);
            }

            appsAdapter = new LauncherAppsAdapter(new LauncherAppsAdapter.Listener() {
                @Override
                public void onAppClicked(LauncherAppEntry entry) {
                    if (entry == null) return;
                    Intent intent = entry.buildLaunchIntent();
                    if (intent == null) return;
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(getContext(), "Gagal buka app", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onAppLongPressed(LauncherAppEntry entry) {
                    if (entry == null || entry.component == null) return;
                    showPinnedAppActions(appContext, entry);
                }

                @Override
                public void onAddClicked() {
                    showAddAppDialog(appContext);
                }

                @Override
                public void onAppFocused(LauncherAppEntry entry, int position) {
                    syncAppsFocusState(position);
                }
            });
            appsList.setAdapter(appsAdapter);
        }

        recentTitle = v.findViewById(R.id.launcher_recent_title);
        recentList = v.findViewById(R.id.launcher_recent_live);
        if (recentList != null) {
            recentList.setLayoutManager(new LinearLayoutManager(v.getContext(), LinearLayoutManager.HORIZONTAL, false));
            recentList.setHasFixedSize(false);
            recentList.setItemViewCacheSize(16);
        recentList.setClipToPadding(false);
            recentList.setClipChildren(false);
            androidx.recyclerview.widget.RecyclerView.ItemAnimator animator3 = recentList.getItemAnimator();
            if (animator3 instanceof androidx.recyclerview.widget.SimpleItemAnimator) {
                ((androidx.recyclerview.widget.SimpleItemAnimator) animator3).setSupportsChangeAnimations(false);
            }
            recentAdapter = new ChannelCardAdapter();
            recentAdapter.setListener(new ChannelCardAdapter.Listener() {
                @Override
                public void onChannelClicked(Channel channel, int position) {
                    if (channel == null) return;
                    syncRecentFocusState(channel.getUrl(), position);
                }

                @Override
                public void onChannelFocused(Channel channel, int position) {
                    if (channel == null) return;
                    syncRecentFocusState(channel.getUrl(), position);
                }
            });
            recentList.setAdapter(recentAdapter);

            // Prevent layout jumping by predicting visibility synchronously.
            List<Channel> cachedRecent = RecentChannelsStore.load(appContext);
            boolean hasRecent = cachedRecent != null && !cachedRecent.isEmpty();
            if (recentTitle != null) recentTitle.setVisibility(hasRecent ? View.VISIBLE : View.GONE);
            recentList.setVisibility(hasRecent ? View.VISIBLE : View.GONE);
        }

        // Seed cards with placeholders; subtitles will be updated after loading.
        List<LauncherCard> cards = new ArrayList<>();
        cards.add(new LauncherCard("Live TV's", "+0 Channels", R.drawable.tv_play_icon, NavDestination.LIVE_TV));
        cards.add(new LauncherCard("Radios", "+0 Stations", R.drawable.internet_radio_icon, NavDestination.SHOWS));
        adapter.submit(cards);

        loadCounts(appContext);
        loadLauncherApps(appContext);
        loadRecentLive(appContext);
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) adapter.setHostActive(true);
        if (getContext() != null) {
            loadLauncherApps(getContext().getApplicationContext());
            loadRecentLive(getContext().getApplicationContext());

            updateHeaderTime();
            updateNetworkIcon(getContext().getApplicationContext());
            mainHandler.removeCallbacks(headerTicker);
            mainHandler.post(headerTicker);

            // Restore focus based on last visited position
            if (lastSelectedCardPosition == SETTINGS_BUTTON_POSITION) {
                if (settingsButton != null) {
                    mainHandler.post(() -> {
                        setHeaderButtonsFocusable(true);
                        settingsButton.setFocusable(true);
                        settingsButton.setFocusableInTouchMode(true);
                        settingsButton.requestFocus();
                    });
                }
            } else if (lastSelectedCardPosition == PROFILE_BUTTON_POSITION) {
                if (profileButton != null) {
                    mainHandler.post(() -> {
                        setHeaderButtonsFocusable(true);
                        profileButton.setFocusable(true);
                        profileButton.setFocusableInTouchMode(true);
                        profileButton.requestFocus();
                    });
                }
            } else if (lastSelectedCardPosition == APPS_ROW_POSITION) {
                lockFocusForRestoration(lastSelectedCardPosition);
                requestFocusToApp(lastSelectedAppIndex);
            } else if (lastSelectedCardPosition == RECENT_ROW_POSITION) {
                lockFocusForRestoration(lastSelectedCardPosition);
                requestFocusToRecent(lastSelectedRecentUrl, lastSelectedRecentIndex);
            } else {
                // Lock header focus while card focus is being restored to prevent bouncing.
                lockFocusForRestoration(lastSelectedCardPosition);
                requestFocusToCard(lastSelectedCardPosition);
            }
        }
    }

    private void requestFocusToRecent(@Nullable String url, int indexFallback) {
        if (recentList == null || recentAdapter == null) return;
        int target = -1;
        if (url != null) {
            try {
                target = recentAdapter.findPositionByUrl(url);
            } catch (Exception ignored) {
            }
        }
        if (target < 0) target = Math.max(0, indexFallback);
        final int finalTarget = target;
        recentList.post(() -> {
            if (recentList == null) return;
            recentList.scrollToPosition(finalTarget);
            requestRecentFocusWithRetry(finalTarget, 0);
        });
    }

    private void requestRecentFocusWithRetry(int index, int attempt) {
        if (recentList == null) return;
        RecyclerView.ViewHolder vh = recentList.findViewHolderForAdapterPosition(index);
        boolean focused = false;
        if (vh != null && vh.itemView != null) {
            focused = vh.itemView.requestFocus();
        }
        if (focused) {
            mainHandler.postDelayed(() -> restoreAllFocusability(), 120);
            return;
        }
        if (attempt >= 8) {
            mainHandler.postDelayed(() -> restoreAllFocusability(), 120);
            return;
        }
        recentList.postDelayed(() -> requestRecentFocusWithRetry(index, attempt + 1), 24);
    }

    private void requestFocusToApp(int index) {
        if (appsList == null) return;
        final int target = Math.max(0, index);
        appsList.post(() -> {
            if (appsList == null) return;
            appsList.scrollToPosition(target);
            requestAppFocusWithRetry(target, 0);
        });
    }

    private void requestAppFocusWithRetry(int index, int attempt) {
        if (appsList == null) return;
        RecyclerView.ViewHolder vh = appsList.findViewHolderForAdapterPosition(index);
        boolean focused = false;
        if (vh != null && vh.itemView != null) {
            focused = vh.itemView.requestFocus();
        }
        if (focused) {
            mainHandler.postDelayed(() -> restoreAllFocusability(), 120);
            return;
        }
        if (attempt >= 6) {
            mainHandler.postDelayed(() -> restoreAllFocusability(), 120);
            return;
        }
        appsList.postDelayed(() -> requestAppFocusWithRetry(index, attempt + 1), 24);
    }

    @Override
    public void onPause() {
        syncFocusFromCurrentView();
        super.onPause();
        if (adapter != null) adapter.setHostActive(false);
    }

    private void updateHeaderTime() {
        if (headerTime == null) return;
        try {
            // 24-hour clock for TV header.
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
            String s = fmt.format(new Date());
            headerTime.setText(s != null ? s : "");
        } catch (Exception ignored) {
        }
    }

    private void updateNetworkIcon(Context appContext) {
        if (headerNet == null) return;

        ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = null;
        try {
            if (cm != null) ni = cm.getActiveNetworkInfo();
        } catch (Exception ignored) {
        }

        if (ni == null || !ni.isConnected()) {
            headerNet.setVisibility(View.GONE);
            return;
        }

        int type = ni.getType();
        if (type == ConnectivityManager.TYPE_ETHERNET) {
            headerNet.setVisibility(View.VISIBLE);
            headerNet.setImageResource(R.drawable.ic_mql_ethernet);
        } else if (type == ConnectivityManager.TYPE_WIFI) {
            headerNet.setVisibility(View.VISIBLE);
            headerNet.setImageResource(R.drawable.ic_mql_wifi);
        } else {
            // Connected but not WiFi/Ethernet (e.g., cellular). Hide for TV-like UX.
            headerNet.setVisibility(View.GONE);
        }
    }

    private void loadCounts(Context appContext) {
        executor.execute(() -> {
            List<Channel> channels = new PlaylistRepository().loadForUser(appContext);
            RecentChannelsStore.pruneAgainstPlaylist(appContext, channels);
            
            int liveCount = 0;
            int radioCount = 0;
            
            for (Channel c : channels) {
                if (c == null) continue;
                String g = c.getGroupTitle();
                if (g != null && g.toLowerCase().contains("radio")) {
                    radioCount++;
                } else {
                    liveCount++;
                }
            }
            
            final int finalLiveCount = liveCount;
            final int finalRadioCount = radioCount;

            mainHandler.post(() -> {
                if (adapter == null) return;
                List<LauncherCard> cards = new ArrayList<>();
                cards.add(new LauncherCard("Live TV's", "+" + finalLiveCount + " Channels", R.drawable.tv_play_icon, NavDestination.LIVE_TV));
                cards.add(new LauncherCard("Radios", "+" + finalRadioCount + " Stations", R.drawable.internet_radio_icon, NavDestination.SHOWS));
                adapter.submit(cards);

                // After async card refresh, restore card focus.
                if (lastSelectedCardPosition == LIVE_TV_CARD_POSITION || lastSelectedCardPosition == RADIO_CARD_POSITION) {
                    if (cardsList != null && !cardsList.hasFocus() && (getView() == null || getView().findFocus() == null || getView().findFocus().getParent() == cardsList)) {
                        requestFocusToCard(lastSelectedCardPosition);
                    }
                }
            });
        });
    }

    private void requestLiveTvFocus() {
        requestFocusToCard(LIVE_TV_CARD_POSITION);
    }

    private void requestFocusToCard(int position) {
        if (cardsList == null) return;

        cardsList.post(() -> {
            if (cardsList == null) return;
            if (position < 0) return;
            cardsList.scrollToPosition(position);
            requestCardFocusWithRetry(position, 0);
        });
    }

    private void requestCardFocusWithRetry(int position, int attempt) {
        if (cardsList == null) return;
        RecyclerView.ViewHolder vh = cardsList.findViewHolderForAdapterPosition(position);
        boolean focused = false;
        if (vh != null && vh.itemView != null) {
            focused = vh.itemView.requestFocus();
        }
        if (focused) {
            // Re-enable header controls after focus is stable on the selected card.
            mainHandler.postDelayed(() -> restoreAllFocusability(), 120);
            return;
        }
        if (attempt >= 6) {
            mainHandler.postDelayed(() -> restoreAllFocusability(), 120);
            return;
        }
        cardsList.postDelayed(() -> requestCardFocusWithRetry(position, attempt + 1), 24);
    }

    private void setHeaderButtonsFocusable(boolean focusable) {
        if (searchButton != null) {
            searchButton.setFocusable(focusable);
            searchButton.setFocusableInTouchMode(focusable);
        }
        if (settingsButton != null) {
            settingsButton.setFocusable(focusable);
            settingsButton.setFocusableInTouchMode(focusable);
        }
        if (profileButton != null) {
            profileButton.setFocusable(focusable);
            profileButton.setFocusableInTouchMode(focusable);
        }
    }

    private void lockFocusForRestoration(int targetRowPosition) {
        setHeaderButtonsFocusable(targetRowPosition == SETTINGS_BUTTON_POSITION || targetRowPosition == PROFILE_BUTTON_POSITION);
        if (cardsList != null) {
            cardsList.setDescendantFocusability(targetRowPosition == LIVE_TV_CARD_POSITION || targetRowPosition == RADIO_CARD_POSITION ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        if (appsList != null) {
            appsList.setDescendantFocusability(targetRowPosition == APPS_ROW_POSITION ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        if (recentList != null) {
            recentList.setDescendantFocusability(targetRowPosition == RECENT_ROW_POSITION ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
    }

    private void restoreAllFocusability() {
        setHeaderButtonsFocusable(true);
        if (cardsList != null) cardsList.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (appsList != null) appsList.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (recentList != null) recentList.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    }

    private void syncHomeFocusState(int position) {
        lastSelectedCardPosition = position;
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setHomeFocusPosition(position);
        }
    }

    private void syncAppsFocusState(int index) {
        lastSelectedCardPosition = APPS_ROW_POSITION;
        lastSelectedAppIndex = Math.max(0, index);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setHomeAppsFocus(lastSelectedAppIndex);
        }
    }

    private void syncRecentFocusState(@Nullable String url, int index) {
        lastSelectedCardPosition = RECENT_ROW_POSITION;
        lastSelectedRecentUrl = url;
        lastSelectedRecentIndex = Math.max(0, index);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setHomeRecentFocus(lastSelectedRecentUrl, lastSelectedRecentIndex);
        }
    }

    private void syncFocusFromCurrentView() {
        View root = getView();
        if (root == null) return;

        View focused = root.findFocus();
        if (focused == null) return;

        if (focused == settingsButton) {
            syncHomeFocusState(SETTINGS_BUTTON_POSITION);
            return;
        }
        if (focused == profileButton) {
            syncHomeFocusState(PROFILE_BUTTON_POSITION);
            return;
        }

        if (cardsList != null) {
            RecyclerView.ViewHolder vh = cardsList.findContainingViewHolder(focused);
            if (vh != null) {
                int pos = vh.getBindingAdapterPosition();
                if (pos == LIVE_TV_CARD_POSITION || pos == RADIO_CARD_POSITION) {
                    syncHomeFocusState(pos);
                }
            }
        }

        if (appsList != null) {
            RecyclerView.ViewHolder vh = appsList.findContainingViewHolder(focused);
            if (vh != null) {
                int pos = vh.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    syncAppsFocusState(pos);
                }
            }
        }

        if (recentList != null) {
            RecyclerView.ViewHolder vh = recentList.findContainingViewHolder(focused);
            if (vh != null) {
                int pos = vh.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && recentAdapter != null) {
                    // Prefer URL for stability when the recent list order changes.
                    // If we can't resolve URL, keep index.
                    String url = null;
                    try {
                        // We don't have direct accessors; URL will be updated by adapter callbacks in most cases.
                        url = lastSelectedRecentUrl;
                    } catch (Exception ignored) {
                    }
                    syncRecentFocusState(url, pos);
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        syncFocusFromCurrentView();
        super.onDestroy();
        if (adapter != null) adapter.release();
        executor.shutdownNow();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (adapter != null) adapter.release();
        mainHandler.removeCallbacks(headerTicker);
        headerTime = null;
        headerNet = null;
    }

    private void loadLauncherApps(Context appContext) {
        if (appsAdapter == null) return;

        executor.execute(() -> {
            PackageManager pm = appContext.getPackageManager();
            List<LauncherAppEntry> all = queryAllLaunchableApps(pm, appContext);
            allLaunchableAppsCache = all;

            // Load pinned (user selected). If empty, seed with a few system apps.
            List<String> pinned = PinnedAppsStore.load(appContext);
            if (pinned.isEmpty() && !PinnedAppsStore.isInitialized(appContext)) {
                pinned = seedDefaultSystemApps(appContext, all);
            }

            List<LauncherAppEntry> row = new ArrayList<>();
            for (String flat : pinned) {
                ComponentName cn = ComponentName.unflattenFromString(flat);
                if (cn == null) continue;
                LauncherAppEntry e = findByComponent(all, cn);
                if (e != null) row.add(e);
            }

            // Add the plus button.
            row.add(new LauncherAppEntry("Tambah", null, null, null, true));

            mainHandler.post(() -> {
                if (appsAdapter != null) appsAdapter.submit(row);
                // Jika terakhir user berada di baris Apps, pastikan fokus dikembalikan
                if (lastSelectedCardPosition == APPS_ROW_POSITION) {
                    if (appsList != null && !appsList.hasFocus() && (getView() == null || getView().findFocus() == null || getView().findFocus().getParent() == appsList)) {
                        requestFocusToApp(lastSelectedAppIndex);
                    }
                }
            });
        });
    }

    private void loadRecentLive(Context appContext) {
        if (recentAdapter == null) return;

        executor.execute(() -> {
            PlaylistRepository repo = new PlaylistRepository();
            List<Channel> playlist = repo.loadForUser(appContext);
            List<Channel> recent = RecentChannelsStore.loadSyncedWithPlaylist(appContext, playlist);
            mainHandler.post(() -> {
                boolean has = recent != null && !recent.isEmpty();
                if (recentTitle != null) recentTitle.setVisibility(has ? View.VISIBLE : View.GONE);
                if (recentList != null) recentList.setVisibility(has ? View.VISIBLE : View.GONE);
                if (has) {
                    recentAdapter.submit(recent);
                } else {
                    recentAdapter.submit(new ArrayList<>());
                }

                // If we are returning to the Recent row, re-apply focus after data refresh.
                if (has && lastSelectedCardPosition == RECENT_ROW_POSITION) {
                    if (recentList != null && !recentList.hasFocus() && (getView() == null || getView().findFocus() == null || getView().findFocus().getParent() == recentList)) {
                        requestFocusToRecent(lastSelectedRecentUrl, lastSelectedRecentIndex);
                    }
                }
            });
        });
    }

    private void showAddAppDialog(Context appContext) {
        executor.execute(() -> {
            PackageManager pm = appContext.getPackageManager();
            List<LauncherAppEntry> all = allLaunchableAppsCache != null ? allLaunchableAppsCache : queryAllLaunchableApps(pm, appContext);

            List<String> pinned = PinnedAppsStore.load(appContext);

            // Only show "external" apps by default: non-system.
            List<LauncherAppEntry> candidates = new ArrayList<>();
            for (LauncherAppEntry e : all) {
                if (e == null || e.component == null) continue;
                if (appContext.getPackageName().equals(e.component.getPackageName())) continue;
                if (pinned.contains(e.component.flattenToString())) continue;

                boolean isSystem = false;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(e.component.getPackageName(), 0);
                    isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                } catch (Exception ignored) {
                }
                if (!isSystem) {
                    candidates.add(e);
                }
            }

            // If no external apps found, fall back to any not pinned.
            if (candidates.isEmpty()) {
                for (LauncherAppEntry e : all) {
                    if (e == null || e.component == null) continue;
                    if (appContext.getPackageName().equals(e.component.getPackageName())) continue;
                    if (pinned.contains(e.component.flattenToString())) continue;
                    candidates.add(e);
                }
            }

            mainHandler.post(() -> {
                if (getActivity() == null) return;
                if (candidates.isEmpty()) {
                    Toast.makeText(getContext(), "Tidak ada app untuk ditambahkan", Toast.LENGTH_SHORT).show();
                    return;
                }

                android.app.Dialog dialog = new android.app.Dialog(getActivity(), android.R.style.Theme_Translucent_NoTitleBar);
                dialog.setContentView(R.layout.dialog_add_app);

                RecyclerView rv = dialog.findViewById(R.id.dialog_add_app_list);
                // Menambahkan 4 kolom dengan margin bawah 14dp di setiap item
                rv.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(appContext, 4));
                rv.addItemDecoration(new RecyclerView.ItemDecoration() {
                    @Override
                    public void getItemOffsets(@NonNull android.graphics.Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                        outRect.bottom = Math.round(14 * getResources().getDisplayMetrics().density);
                    }
                });

                LauncherAppsAdapter dialogAdapter = new LauncherAppsAdapter(new LauncherAppsAdapter.Listener() {
                    @Override
                    public void onAppClicked(LauncherAppEntry entry) {
                        try {
                            if (entry != null && entry.component != null) {
                                PinnedAppsStore.add(appContext, entry.component.flattenToString());
                                loadLauncherApps(appContext);
                                dialog.dismiss();
                            }
                        } catch (Exception ignored) {
                        }
                    }

                    @Override
                    public void onAppLongPressed(LauncherAppEntry entry) {
                    }

                    @Override
                    public void onAddClicked() {
                    }

                    @Override
                    public void onAppFocused(LauncherAppEntry entry, int position) {
                    }
                });
                
                rv.setAdapter(dialogAdapter);
                dialogAdapter.submit(candidates);

                dialog.show();
            });
        });
    }

    private void showPinnedAppActions(Context appContext, LauncherAppEntry entry) {
        if (getActivity() == null || entry == null || entry.component == null) return;

        executor.execute(() -> {
            List<String> pinned = PinnedAppsStore.load(appContext);
            String key = entry.component.flattenToString();
            int idx = pinned.indexOf(key);

            mainHandler.post(() -> {
                if (idx < 0) {
                    Toast.makeText(getContext(), "App tidak ada di pinned list", Toast.LENGTH_SHORT).show();
                    return;
                }

                List<String> actions = new ArrayList<>();
                if (idx > 0) actions.add("Move Left");
                if (idx < pinned.size() - 1) actions.add("Move Right");
                actions.add("Hapus");

                CharSequence[] items = new CharSequence[actions.size()];
                for (int i = 0; i < actions.size(); i++) items[i] = actions.get(i);

                new AlertDialog.Builder(getActivity())
                        .setTitle(entry.label != null ? entry.label : "App")
                        .setItems(items, (d, which) -> {
                            String action = actions.get(which);
                            executor.execute(() -> {
                                List<String> list = PinnedAppsStore.load(appContext);
                                int pos = list.indexOf(key);
                                if (pos < 0) return;

                                if ("Move Left".equals(action) && pos > 0) {
                                    String tmp = list.get(pos - 1);
                                    list.set(pos - 1, list.get(pos));
                                    list.set(pos, tmp);
                                    PinnedAppsStore.save(appContext, list);
                                } else if ("Move Right".equals(action) && pos < list.size() - 1) {
                                    String tmp = list.get(pos + 1);
                                    list.set(pos + 1, list.get(pos));
                                    list.set(pos, tmp);
                                    PinnedAppsStore.save(appContext, list);
                                } else if ("Hapus".equals(action)) {
                                    list.remove(pos);
                                    PinnedAppsStore.save(appContext, list);
                                }

                                mainHandler.post(() -> loadLauncherApps(appContext));
                            });
                        })
                        .setNegativeButton("Batal", (d, w) -> d.dismiss())
                        .show();
            });
        });
    }

    private static List<String> seedDefaultSystemApps(Context appContext, List<LauncherAppEntry> all) {
        List<String> pinned = new ArrayList<>();
        if (all == null) return pinned;
        PackageManager pm = appContext.getPackageManager();

        // Prefer Settings if present.
        for (LauncherAppEntry e : all) {
            if (e != null && e.component != null && "com.android.settings".equals(e.component.getPackageName())) {
                pinned.add(e.component.flattenToString());
                break;
            }
        }

        // Add a few system apps.
        for (LauncherAppEntry e : all) {
            if (e == null || e.component == null) continue;
            if (appContext.getPackageName().equals(e.component.getPackageName())) continue;
            if (pinned.contains(e.component.flattenToString())) continue;

            boolean isSystem = false;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(e.component.getPackageName(), 0);
                isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            } catch (Exception ignored) {
            }
            if (isSystem) {
                pinned.add(e.component.flattenToString());
                if (pinned.size() >= 6) break;
            }
        }

        if (!pinned.isEmpty()) {
            PinnedAppsStore.save(appContext, pinned);
        }
        return pinned;
    }

    private static LauncherAppEntry findByComponent(List<LauncherAppEntry> all, ComponentName cn) {
        if (all == null || cn == null) return null;
        for (LauncherAppEntry e : all) {
            if (e != null && cn.equals(e.component)) return e;
        }
        return null;
    }

    @SuppressLint("QueryPermissionsNeeded")
    private static List<LauncherAppEntry> queryAllLaunchableApps(PackageManager pm, Context ctx) {
        List<LauncherAppEntry> out = new ArrayList<>();
        if (pm == null || ctx == null) return out;

        // Query LAUNCHER and LEANBACK_LAUNCHER and merge.
        List<ResolveInfo> resolved = new ArrayList<>();
        try {
            Intent i1 = new Intent(Intent.ACTION_MAIN);
            i1.addCategory(Intent.CATEGORY_LAUNCHER);
            resolved.addAll(pm.queryIntentActivities(i1, 0));
        } catch (Exception ignored) {
        }
        try {
            Intent i2 = new Intent(Intent.ACTION_MAIN);
            i2.addCategory("android.intent.category.LEANBACK_LAUNCHER");
            resolved.addAll(pm.queryIntentActivities(i2, 0));
        } catch (Exception ignored) {
        }

        // Dedup by component.
        List<String> seen = new ArrayList<>();
        for (ResolveInfo ri : resolved) {
            if (ri == null || ri.activityInfo == null) continue;
            ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            String key = cn.flattenToString();
            if (seen.contains(key)) continue;
            seen.add(key);
            try {
                LauncherAppEntry e = LauncherAppEntry.fromResolveInfo(ri, pm);
                // Exclude our own app.
                if (ctx.getPackageName().equals(cn.getPackageName())) continue;
                if (e.icon == null) continue;
                out.add(e);
            } catch (Exception ignored) {
            }
        }

        // Sort by label (avoid List.sort for Android 4.x compatibility).
        java.util.Collections.sort(out, (a, b) -> {
            String la = a != null && a.label != null ? a.label : "";
            String lb = b != null && b.label != null ? b.label : "";
            return la.compareToIgnoreCase(lb);
        });

        return out;
    }

    @Override
    public void onCardClicked(LauncherCard card) {
        if (card == null) return;
        // Save which card was clicked for focus restore on resume
        if (card.getDestination() == NavDestination.LIVE_TV) {
            syncHomeFocusState(LIVE_TV_CARD_POSITION);
        } else if (card.getDestination() == NavDestination.SHOWS) {
            syncHomeFocusState(RADIO_CARD_POSITION);
        }
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).navigateTo(card.getDestination());
        }
    }

    @Override
    public void onCardFocused(LauncherCard card) {
        if (card == null) return;
        if (card.getDestination() == NavDestination.LIVE_TV) {
            syncHomeFocusState(LIVE_TV_CARD_POSITION);
        } else if (card.getDestination() == NavDestination.SHOWS) {
            syncHomeFocusState(RADIO_CARD_POSITION);
        }
    }
}
