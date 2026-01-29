package com.mqltv;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LauncherFragment extends Fragment implements LauncherCardAdapter.Listener {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private RecyclerView cardsList;
    private LauncherCardAdapter adapter;

    private RecyclerView appsList;
    private LauncherAppsAdapter appsAdapter;
    private List<LauncherAppEntry> allLaunchableAppsCache;

    private TextView recentTitle;
    private RecyclerView recentList;
    private ChannelCardAdapter recentAdapter;

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

        appsList = v.findViewById(R.id.launcher_apps);
        if (appsList != null) {
            appsList.setLayoutManager(new LinearLayoutManager(v.getContext(), LinearLayoutManager.HORIZONTAL, false));
            appsList.setHasFixedSize(false);
            appsList.setItemViewCacheSize(16);
            appsList.setClipToPadding(false);
            appsList.setClipChildren(false);

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
            recentAdapter = new ChannelCardAdapter();
            recentList.setAdapter(recentAdapter);
        }

        // Seed cards with placeholders; subtitles will be updated after loading.
        List<LauncherCard> cards = new ArrayList<>();
        cards.add(new LauncherCard("Live TV's", "+0 Channels", R.drawable.tv_play_icon, NavDestination.LIVE_TV));
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
        loadLauncherApps(appContext);
        loadRecentLive(appContext);
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getContext() != null) {
            loadLauncherApps(getContext().getApplicationContext());
            loadRecentLive(getContext().getApplicationContext());
        }
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    private void loadCounts(Context appContext) {
        executor.execute(() -> {
            PlaylistRepository repo = new PlaylistRepository();
            List<Channel> channels = repo.loadDefault(appContext);
            final int liveCount = channels != null ? channels.size() : 0;

            mainHandler.post(() -> {
                if (adapter == null) return;
                List<LauncherCard> cards = new ArrayList<>();
                cards.add(new LauncherCard("Live TV's", "+" + liveCount + " Channels", R.drawable.tv_play_icon, NavDestination.LIVE_TV));
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
            row.add(new LauncherAppEntry("Tambah", null, null, true));

            List<LauncherAppEntry> finalRow = row;
            mainHandler.post(() -> {
                if (appsAdapter != null) appsAdapter.submit(finalRow);
            });
        });
    }

    private void loadRecentLive(Context appContext) {
        if (recentAdapter == null) return;

        executor.execute(() -> {
            List<Channel> recent = RecentChannelsStore.load(appContext);
            mainHandler.post(() -> {
                boolean has = recent != null && !recent.isEmpty();
                if (recentTitle != null) recentTitle.setVisibility(has ? View.VISIBLE : View.GONE);
                if (recentList != null) recentList.setVisibility(has ? View.VISIBLE : View.GONE);
                if (has) {
                    recentAdapter.submit(recent);
                } else {
                    recentAdapter.submit(new ArrayList<>());
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
                    isSystem = ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
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

            final CharSequence[] labels = new CharSequence[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) {
                labels[i] = candidates.get(i).label;
            }

            mainHandler.post(() -> {
                if (getActivity() == null) return;
                if (candidates.isEmpty()) {
                    Toast.makeText(getContext(), "Tidak ada app untuk ditambahkan", Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(getActivity())
                        .setTitle("Tambah App")
                        .setItems(labels, (d, which) -> {
                            try {
                                LauncherAppEntry picked = candidates.get(which);
                                if (picked != null && picked.component != null) {
                                    PinnedAppsStore.add(appContext, picked.component.flattenToString());
                                    loadLauncherApps(appContext);
                                }
                            } catch (Exception ignored) {
                            }
                        })
                        .setNegativeButton("Batal", (d, w) -> d.dismiss())
                        .show();
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
                isSystem = ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
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
            if (key == null || seen.contains(key)) continue;
            seen.add(key);
            try {
                LauncherAppEntry e = LauncherAppEntry.fromResolveInfo(ri, pm);
                // Exclude our own app.
                if (ctx.getPackageName().equals(cn.getPackageName())) continue;
                if (e == null || e.icon == null) continue;
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
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).navigateTo(card.getDestination());
        }
    }
}
