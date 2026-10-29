package com.mqltv;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import androidx.core.app.NotificationManagerCompat;
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
import androidx.core.widget.NestedScrollView;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
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
    private static final int NATIVE_SETTINGS_BUTTON_POSITION = 6;
    private static final int NOTIFICATIONS_BUTTON_POSITION = 7;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private RecyclerView cardsList;
    private LauncherCardAdapter adapter;
    private View searchButton;
    private View settingsButton;
    private View nativeSettingsButton;
    private View notificationsButton;
    private TextView notificationsBadge;
    private View profileButton;

    private final BroadcastReceiver notificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MqlNotificationListenerService.ACTION_NOTIFICATION_COUNT_CHANGED.equals(intent.getAction())) {
                int count = intent.getIntExtra(MqlNotificationListenerService.EXTRA_COUNT, 0);
                updateNotificationBadge(count);
            }
        }
    };

    private void updateNotificationBadge(int count) {
        if (notificationsBadge != null) {
            if (count > 0) {
                notificationsBadge.setVisibility(View.VISIBLE);
                notificationsBadge.setText(String.valueOf(count));
            } else {
                notificationsBadge.setVisibility(View.GONE);
            }
        }
    }
    private int lastSelectedCardPosition = APPS_ROW_POSITION;
    private int lastSelectedAppIndex = 0;
    private String latestLiveSubtitle = "+5000 Saluran";

    private int lastSelectedRecentIndex = 0;
    @Nullable
    private String lastSelectedRecentUrl;

    private androidx.core.widget.NestedScrollView scrollContainer;
    private RecyclerView appsList;
    private View rootLayout;
    private GlassView glass;
    private SpringAnimation springX;
    private SpringAnimation springY;
    private boolean glassShown = false;

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
    private TextView greetingText;
    private TextView headerDate;
    private TextView headerNetLabel;

    private LauncherAmbientHelper ambientHelper;
    private TextView heroKicker;
    private View heroKickerBar;
    private TextView heroTitle;
    private TextView heroSubtitle;
    private View launcherHero;
    private View launcherHeader;
    private LauncherWallpaper.WallpaperListener wallpaperListener;

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
            int pos = args.getInt(ARG_INITIAL_FOCUS_POSITION, APPS_ROW_POSITION);
            if (pos == LIVE_TV_CARD_POSITION || pos == RADIO_CARD_POSITION) {
                lastSelectedCardPosition = APPS_ROW_POSITION;
                lastSelectedAppIndex = 0;
            } else {
                lastSelectedCardPosition = pos;
                lastSelectedAppIndex = Math.max(0, args.getInt(ARG_INITIAL_APPS_INDEX, 0));
            }
            lastSelectedRecentUrl = args.getString(ARG_INITIAL_RECENT_URL, null);
            lastSelectedRecentIndex = Math.max(0, args.getInt(ARG_INITIAL_RECENT_INDEX, 0));
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_launcher, container, false);
        Context appContext = v.getContext().getApplicationContext();
        rootLayout = v;

        glass = v.findViewById(R.id.launcher_glass);
        if (glass != null) {
            springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
            SpringForce forceX = new SpringForce();
            forceX.setDampingRatio(0.76f);
            forceX.setStiffness(320f);
            springX.setSpring(forceX);

            springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
            SpringForce forceY = new SpringForce();
            forceY.setDampingRatio(0.76f);
            forceY.setStiffness(320f);
            springY.setSpring(forceY);
        }

        launcherHeader = v.findViewById(R.id.launcher_header);
        scrollContainer = v.findViewById(R.id.launcher_scroll);
        if (scrollContainer != null) {
            scrollContainer.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) (sv, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                int dy = scrollY - oldScrollY;
                if (glassShown && glass != null && glass.getAlpha() > 0.05f) {
                    glass.setTranslationY(glass.getTranslationY() - dy);
                    if (springY != null && springY.getSpring() != null) {
                        springY.getSpring().setFinalPosition(springY.getSpring().getFinalPosition() - dy);
                    }
                }
            });
        }

        View safeLeft = v.findViewById(R.id.launcher_safe_left);
        View scrollContent = v.findViewById(R.id.launcher_scroll_content);
        if (safeLeft != null && scrollContent != null) {
            v.post(() -> {
                int left = safeLeft.getLeft();
                if (left > 0 && scrollContent.getPaddingLeft() != left) {
                    scrollContent.setPadding(left, scrollContent.getPaddingTop(), left, scrollContent.getPaddingBottom());
                }
            });
        }

        ImageView ambientGlowView = v.findViewById(R.id.launcher_ambient_glow);
        if (ambientGlowView != null) {
            ambientHelper = new LauncherAmbientHelper(ambientGlowView);
        }

        greetingText = v.findViewById(R.id.launcher_greeting);
        headerDate = v.findViewById(R.id.launcher_header_date);
        headerNetLabel = v.findViewById(R.id.launcher_header_net_label);

        heroKicker = v.findViewById(R.id.launcher_hero_kicker);
        heroKickerBar = v.findViewById(R.id.launcher_hero_kicker_bar);
        heroTitle = v.findViewById(R.id.launcher_hero_title);
        heroSubtitle = v.findViewById(R.id.launcher_hero_subtitle);
        launcherHero = v.findViewById(R.id.launcher_hero);

        updateHero("MQLTV · LIVE ENTERTAINMENT", "LIVE TV", "+5000 Saluran TV Lokal & Internasional", 0xFF8B5CF6);

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

            wallpaperListener = newBmp -> {
                if (isAdded() && wallpaper != null && newBmp != null) {
                    mainHandler.post(() -> {
                        try {
                            wallpaper.setImageBitmap(newBmp);
                            wallpaper.animate().alpha(1f).setDuration(400).start();
                        } catch (Exception ignored) {
                        }
                        LauncherCardStyle style = LauncherCardStyle.fromWallpaper(appContext, newBmp);
                        if (style != null && adapter != null) {
                            adapter.setCardStyle(style);
                        }
                    });
                }
            };
            LauncherWallpaper.addListener(wallpaperListener);
        }

        searchButton = v.findViewById(R.id.launcher_search);
        setupPillFocus(searchButton, () -> {
            Intent i = new Intent(appContext, SearchActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(i);
        }, null);

        settingsButton = v.findViewById(R.id.launcher_settings);
        setupPillFocus(settingsButton, () -> {
            syncHomeFocusState(SETTINGS_BUTTON_POSITION);
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openSettings();
            }
        }, () -> syncHomeFocusState(SETTINGS_BUTTON_POSITION));

        nativeSettingsButton = v.findViewById(R.id.launcher_native_settings);
        setupPillFocus(nativeSettingsButton, () -> {
            syncHomeFocusState(NATIVE_SETTINGS_BUTTON_POSITION);
            Intent intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
        }, () -> syncHomeFocusState(NATIVE_SETTINGS_BUTTON_POSITION));

        notificationsButton = v.findViewById(R.id.launcher_notifications);
        notificationsBadge = v.findViewById(R.id.launcher_notifications_badge);
        setupPillFocus(notificationsButton, () -> {
            syncHomeFocusState(NOTIFICATIONS_BUTTON_POSITION);
            boolean isNotificationAccessGranted = NotificationManagerCompat.getEnabledListenerPackages(appContext).contains(appContext.getPackageName());
            if (!isNotificationAccessGranted) {
                Toast.makeText(appContext, "Mohon izinkan Akses Notifikasi untuk MQLTV", Toast.LENGTH_LONG).show();
                Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { appContext.startActivity(intent); } catch (Exception e) { e.printStackTrace(); }
            } else {
                try {
                    @SuppressLint("WrongConstant") Object sbm = appContext.getSystemService("statusbar");
                    Class<?> statusbarManager = Class.forName("android.app.StatusBarManager");
                    java.lang.reflect.Method expand = statusbarManager.getMethod("expandNotificationsPanel");
                    expand.invoke(sbm);
                } catch (Exception e) {
                    e.printStackTrace();
                    boolean success = false;
                    String[] tvIntents = {
                        "com.android.tv.NOTIFICATIONS",
                        "com.google.android.tvlauncher.action.NOTIFICATIONS",
                        "android.settings.NOTIFICATION_SETTINGS"
                    };
                    for (String action : tvIntents) {
                        try {
                            Intent intent = new Intent(action);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            appContext.startActivity(intent);
                            success = true;
                            break;
                        } catch (Exception ignored) {}
                    }
                    if (!success) {
                        Toast.makeText(appContext, "TV Anda tidak memiliki panel notifikasi.", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }, () -> syncHomeFocusState(NOTIFICATIONS_BUTTON_POSITION));

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
        setupPillFocus(profileButton, () -> {
            syncHomeFocusState(PROFILE_BUTTON_POSITION);
            if (AuthPrefs.isLoggedIn(appContext)) {
                Intent i = new Intent(appContext, AccountActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(i);
            } else {
                LoginGuard.ensureLoggedIn(appContext);
            }
        }, () -> syncHomeFocusState(PROFILE_BUTTON_POSITION));



        appsList = v.findViewById(R.id.launcher_apps);
        if (appsList != null) {
            androidx.recyclerview.widget.GridLayoutManager glm = new androidx.recyclerview.widget.GridLayoutManager(v.getContext(), 4, RecyclerView.VERTICAL, false);
            glm.setSpanSizeLookup(new androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    if (appsAdapter != null && appsAdapter.isLibraryAt(position)) {
                        return 4;
                    }
                    return 1;
                }
            });
            appsList.setLayoutManager(glm);
            appsList.setHasFixedSize(false);
            appsList.setItemViewCacheSize(24);
            appsList.setClipToPadding(false);
            appsList.setClipChildren(false);
            appsList.setNestedScrollingEnabled(false);
            appsList.addItemDecoration(new DockDecoration(4, getResources().getDisplayMetrics().density));
            androidx.recyclerview.widget.RecyclerView.ItemAnimator animator2 = appsList.getItemAnimator();
            if (animator2 instanceof androidx.recyclerview.widget.SimpleItemAnimator) {
                ((androidx.recyclerview.widget.SimpleItemAnimator) animator2).setSupportsChangeAnimations(false);
            }

            appsAdapter = new LauncherAppsAdapter(new LauncherAppsAdapter.Listener() {
                @Override
                public void onAppClicked(LauncherAppEntry entry) {
                    if (entry == null) return;
                    if (entry.isLiveTv) {
                        syncAppsFocusState(0);
                        if (getActivity() instanceof MainActivity) {
                            ((MainActivity) getActivity()).navigateTo(NavDestination.LIVE_TV);
                        }
                        return;
                    }
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
                    if (entry == null || entry.component == null || entry.isLiveTv) return;
                    showPinnedAppActions(appContext, entry);
                }

                @Override
                public void onAddClicked() {
                    showAppLibrary(appContext);
                }

                @Override
                public void onLibraryClicked() {
                    showAppLibrary(appContext);
                }

                @Override
                public void onLibraryFocused(View view) {
                    if (glass != null) {
                        glass.animate().cancel();
                        glass.animate().alpha(0f).setDuration(120).start();
                        glassShown = false;
                    }
                    if (appsAdapter != null) {
                        syncAppsFocusState(appsAdapter.getItemCount() - 1);
                    }
                }

                @Override
                public void onLibraryUp() {
                    onAppLibraryUp();
                }

                @Override
                public void onFocusLibraryRequested() {
                    focusLibrary();
                }

                @Override
                public void onAppFocused(LauncherAppEntry entry, int position, View cardView) {
                    syncAppsFocusState(position);
                    if (position < 4) {
                        if (scrollContainer != null) {
                            scrollContainer.smoothScrollTo(0, 0);
                            scrollContainer.post(() -> {
                                if (scrollContainer != null && scrollContainer.getScrollY() != 0) {
                                    scrollContainer.smoothScrollTo(0, 0);
                                }
                            });
                        }
                    }
                    int color = 0xFF3B82F6;
                    if (entry != null) {
                        color = LauncherAppsAdapter.colorFromEntry(entry);
                        if (position < 4) {
                            if (launcherHero != null) {
                                launcherHero.animate().alpha(1f).setDuration(160).start();
                            }
                            if (entry.isLiveTv) {
                                updateHero("MQLTV · LIVE ENTERTAINMENT", "LIVE TV",
                                        latestLiveSubtitle != null ? latestLiveSubtitle + " TV Lokal & Internasional" : "+5000 Saluran TV Lokal & Internasional",
                                        color);
                            } else {
                                updateHero("APLIKASI", entry.label, "", color);
                            }
                        } else {
                            if (launcherHero != null) {
                                launcherHero.animate().alpha(0f).setDuration(160).start();
                            }
                        }
                    }
                    moveGlassToCard(cardView, color);
                }
            });
            appsList.setAdapter(appsAdapter);
        }

        // Initial Hero state for Live TV
        updateHero("MQLTV · LIVE ENTERTAINMENT", "LIVE TV", "+5000 Saluran TV Lokal & Internasional", 0xFF8B5CF6);

        loadCounts(appContext);
        loadLauncherApps(appContext);
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getContext() != null) {
            android.content.IntentFilter filter = new android.content.IntentFilter(MqlNotificationListenerService.ACTION_NOTIFICATION_COUNT_CHANGED);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                getContext().registerReceiver(notificationReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                getContext().registerReceiver(notificationReceiver, filter);
            }
        }
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
            } else if (lastSelectedCardPosition == NATIVE_SETTINGS_BUTTON_POSITION) {
                if (nativeSettingsButton != null) {
                    mainHandler.post(() -> {
                        setHeaderButtonsFocusable(true);
                        nativeSettingsButton.setFocusable(true);
                        nativeSettingsButton.setFocusableInTouchMode(true);
                        nativeSettingsButton.requestFocus();
                    });
                }
            } else if (lastSelectedCardPosition == NOTIFICATIONS_BUTTON_POSITION) {
                if (notificationsButton != null) {
                    mainHandler.post(() -> {
                        setHeaderButtonsFocusable(true);
                        notificationsButton.setFocusable(true);
                        notificationsButton.setFocusableInTouchMode(true);
                        notificationsButton.requestFocus();
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
            } else if (lastSelectedCardPosition == RECENT_ROW_POSITION) {
                lockFocusForRestoration(lastSelectedCardPosition);
                requestFocusToRecent(lastSelectedRecentUrl, lastSelectedRecentIndex);
            } else {
                lockFocusForRestoration(APPS_ROW_POSITION);
                requestFocusToApp(lastSelectedAppIndex);
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
        if (getContext() != null) {
            try {
                getContext().unregisterReceiver(notificationReceiver);
            } catch (Exception e) { e.printStackTrace(); }
        }
        super.onPause();
        if (adapter != null) adapter.setHostActive(false);
    }

    private void setupPillFocus(View view, Runnable onClick, Runnable onFocus) {
        if (view == null) return;
        if (onClick != null) {
            view.setOnClickListener(v -> onClick.run());
        }
        view.setOnFocusChangeListener((v, hasFocus) -> {
            float s = hasFocus ? 1.08f : 1.0f;
            float z = hasFocus ? 8f : 0f;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                v.animate().scaleX(s).scaleY(s).translationZ(z).setDuration(120).start();
            } else {
                v.animate().scaleX(s).scaleY(s).setDuration(120).start();
            }
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    if (child instanceof ImageView) {
                        ((ImageView) child).setColorFilter(hasFocus ? 0xFF121212 : 0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
                    } else if (child instanceof TextView) {
                        ((TextView) child).setTextColor(hasFocus ? 0xFF121212 : 0xFFFFFFFF);
                    }
                }
            }
            if (hasFocus) {
                if (glass != null && glassShown) {
                    glass.animate().alpha(0f).setDuration(120).start();
                    glassShown = false;
                }
                if (onFocus != null) {
                    onFocus.run();
                }
            }
        });
    }

    private void moveGlassToCard(View cardView, int accentColor) {
        if (cardView == null || glass == null || rootLayout == null) return;
        if (scrollContainer != null && scrollContainer.getVisibility() != View.VISIBLE) return;
        if (glass.getVisibility() != View.VISIBLE || !cardView.isShown()) return;
        if (cardView.getWidth() == 0 || cardView.getHeight() == 0) {
            cardView.post(() -> moveGlassToCard(cardView, accentColor));
            return;
        }

        int[] rootLoc = new int[2];
        rootLayout.getLocationOnScreen(rootLoc);
        int[] cardLoc = new int[2];
        cardView.getLocationOnScreen(cardLoc);

        float cardX = cardLoc[0] - rootLoc[0];
        float cardY = cardLoc[1] - rootLoc[1];
        float cardW = cardView.getWidth();
        float cardH = cardView.getHeight();

        float centerX = cardX + cardW / 2f;
        float centerY = cardY + cardH / 2f;

        float density = getResources().getDisplayMetrics().density;
        float pad = 6f * density;
        float scale = 1.10f;
        float targetW = cardW * scale + 2 * pad;
        float targetH = cardH * scale + 2 * pad;
        float targetX = centerX - targetW / 2f;
        float targetY = centerY - targetH / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != Math.round(targetW) || lp.height != Math.round(targetH)) {
            lp.width = Math.round(targetW);
            lp.height = Math.round(targetH);
            glass.setLayoutParams(lp);
        }

        glass.setAccent(accentColor);

        if (!glassShown || glass.getAlpha() < 0.05f) {
            if (springX != null) springX.cancel();
            if (springY != null) springY.cancel();
            glass.setTranslationX(targetX);
            glass.setTranslationY(targetY);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            if (springX != null) springX.animateToFinalPosition(targetX);
            if (springY != null) springY.animateToFinalPosition(targetY);
        }
    }

    public void updateHero(String kicker, String title, String subtitle, int accentColor) {
        if (heroTitle != null && title != null) {
            heroTitle.setText(title);
        }
        if (heroSubtitle != null && subtitle != null) {
            heroSubtitle.setText(subtitle);
        }
        if (heroKicker != null && kicker != null) {
            heroKicker.setText(kicker);
        }
        if (heroKickerBar != null) {
            try {
                heroKickerBar.getBackground().setColorFilter(accentColor, android.graphics.PorterDuff.Mode.SRC_IN);
            } catch (Exception ignored) {
            }
        }
        if (ambientHelper != null) {
            ambientHelper.setGlowColor(accentColor);
        }
    }

    private void updateHeaderTime() {
        if (headerTime == null) return;
        try {
            Date now = TimeSync.getDate();
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
            String s = fmt.format(now);
            headerTime.setText(s != null ? s : "");

            if (headerDate != null) {
                SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault());
                String dateStr = dateFmt.format(now);
                headerDate.setText(dateStr != null ? dateStr.toUpperCase(Locale.getDefault()) : "");
            }

            if (greetingText != null) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTime(now);
                int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
                String greeting;
                if (hour >= 4 && hour < 11) {
                    greeting = "SELAMAT PAGI";
                } else if (hour >= 11 && hour < 15) {
                    greeting = "SELAMAT SIANG";
                } else if (hour >= 15 && hour < 18) {
                    greeting = "SELAMAT SORE";
                } else {
                    greeting = "SELAMAT MALAM";
                }
                greetingText.setText(greeting);
            }
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
            if (headerNetLabel != null) headerNetLabel.setText("OFFLINE");
            return;
        }

        int type = ni.getType();
        if (type == ConnectivityManager.TYPE_ETHERNET) {
            headerNet.setVisibility(View.VISIBLE);
            headerNet.setImageResource(R.drawable.ic_mql_ethernet);
            if (headerNetLabel != null) headerNetLabel.setText("ETHERNET");
        } else if (type == ConnectivityManager.TYPE_WIFI) {
            headerNet.setVisibility(View.VISIBLE);
            headerNet.setImageResource(R.drawable.ic_mql_wifi);
            if (headerNetLabel != null) headerNetLabel.setText("WI-FI");
        } else {
            headerNet.setVisibility(View.GONE);
            if (headerNetLabel != null) headerNetLabel.setText("CONNECTED");
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
            latestLiveSubtitle = "+" + finalLiveCount + " Saluran";

            mainHandler.post(() -> {
                loadLauncherApps(appContext);

                if (appsList != null && appsList.hasFocus() && lastSelectedAppIndex == 0) {
                    updateHero("MQLTV · LIVE ENTERTAINMENT", "LIVE TV", "+" + finalLiveCount + " Saluran TV Tersedia", 0xFF8B5CF6);
                }

                // If channel count is 0 (load failed or network delayed), schedule auto-retry in 4 seconds
                if (finalLiveCount == 0) {
                    mainHandler.postDelayed(() -> {
                        if (isAdded()) {
                            loadCounts(appContext);
                        }
                    }, 4000);
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
        if (nativeSettingsButton != null) {
            nativeSettingsButton.setFocusable(focusable);
            nativeSettingsButton.setFocusableInTouchMode(focusable);
        }
        if (notificationsButton != null) {
            notificationsButton.setFocusable(focusable);
            notificationsButton.setFocusableInTouchMode(focusable);
        }
        if (profileButton != null) {
            profileButton.setFocusable(focusable);
            profileButton.setFocusableInTouchMode(focusable);
        }
    }

    private void lockFocusForRestoration(int targetRowPosition) {
        setHeaderButtonsFocusable(targetRowPosition == SETTINGS_BUTTON_POSITION || targetRowPosition == NATIVE_SETTINGS_BUTTON_POSITION || targetRowPosition == NOTIFICATIONS_BUTTON_POSITION || targetRowPosition == PROFILE_BUTTON_POSITION);
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
        if (focused == nativeSettingsButton) {
            syncHomeFocusState(NATIVE_SETTINGS_BUTTON_POSITION);
            return;
        }
        if (focused == notificationsButton) {
            syncHomeFocusState(NOTIFICATIONS_BUTTON_POSITION);
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
        if (wallpaperListener != null) {
            LauncherWallpaper.removeListener(wallpaperListener);
            wallpaperListener = null;
        }
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
            // Live TV as #1 App Tile
            row.add(LauncherAppEntry.createLiveTvEntry(appContext, latestLiveSubtitle));

            for (String flat : pinned) {
                ComponentName cn = ComponentName.unflattenFromString(flat);
                if (cn == null) continue;
                if (cn.getPackageName() != null && cn.getPackageName().equals(appContext.getPackageName())) continue;
                LauncherAppEntry e = findByComponent(all, cn);
                if (e != null) row.add(e);
            }

            mainHandler.post(() -> {
                boolean wasEmpty = appsAdapter != null && appsAdapter.getAppsCount() == 0;
                if (appsAdapter != null) appsAdapter.submit(row);
                // Jika terakhir user berada di baris Apps, pastikan fokus dikembalikan
                if (lastSelectedCardPosition == APPS_ROW_POSITION) {
                    int targetIdx = lastSelectedAppIndex;
                    if (targetIdx < 0 || targetIdx >= row.size()) targetIdx = 0;
                    if (wasEmpty || appsList == null || !appsList.hasFocus() || (getView() == null || getView().findFocus() == null || getView().findFocus().getParent() == appsList)) {
                        requestFocusToApp(targetIdx);
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

    private void showAppLibrary(Context appContext) {
        if (getActivity() == null) return;
        View header = getView() != null ? getView().findViewById(R.id.launcher_header) : null;
        View scroll = getView() != null ? getView().findViewById(R.id.launcher_scroll) : null;

        if (glass != null) {
            glass.animate().cancel();
            if (springX != null) springX.cancel();
            if (springY != null) springY.cancel();
            glass.setAlpha(0f);
            glass.setVisibility(View.INVISIBLE);
            glassShown = false;
        }
        if (header != null) header.setVisibility(View.INVISIBLE);
        if (scroll != null) scroll.setVisibility(View.INVISIBLE);

        AppLibraryDialog dialog = new AppLibraryDialog(getActivity(), () -> {
            loadLauncherApps(appContext);
        }, () -> {
            if (header != null) header.setVisibility(View.VISIBLE);
            if (scroll != null) scroll.setVisibility(View.VISIBLE);
            if (glass != null) {
                glass.setAlpha(0f);
                glass.setVisibility(View.VISIBLE);
                glassShown = false;
            }
            focusLibrary();
        });
        dialog.show();
    }

    private void focusLibrary() {
        if (appsAdapter == null || appsList == null) return;
        if (scrollContainer != null) {
            scrollContainer.smoothScrollTo(0, appsList.getBottom() + 300);
        }
        int position = appsAdapter.getItemCount() - 1;
        RecyclerView.ViewHolder holder = appsList.findViewHolderForAdapterPosition(position);
        if (holder != null && holder.itemView != null) {
            View btn = holder.itemView.findViewById(R.id.btnLibrary);
            if (btn != null) {
                btn.requestFocus();
                return;
            }
        }
        appsList.postDelayed(() -> {
            if (appsList == null || appsAdapter == null) return;
            RecyclerView.ViewHolder h = appsList.findViewHolderForAdapterPosition(appsAdapter.getItemCount() - 1);
            if (h != null && h.itemView != null) {
                View btn = h.itemView.findViewById(R.id.btnLibrary);
                if (btn != null) btn.requestFocus();
            }
        }, 120);
    }

    private void onAppLibraryUp() {
        int count = appsAdapter != null ? appsAdapter.getAppsCount() : 0;
        if (count == 0) return;
        int lastRowStart = ((count - 1) / 4) * 4;
        int target = Math.min(lastRowStart + (lastSelectedAppIndex % 4), count - 1);
        requestFocusToApp(target);
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
                    public void onAppFocused(LauncherAppEntry entry, int position, View cardView) {
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

                android.app.Dialog dialog = new android.app.Dialog(getActivity(), android.R.style.Theme_Translucent_NoTitleBar);
                dialog.setContentView(R.layout.dialog_app_actions);

                ImageView icon = dialog.findViewById(R.id.dialog_app_action_icon);
                TextView title = dialog.findViewById(R.id.dialog_app_action_title);
                View btnLeft = dialog.findViewById(R.id.btn_move_left);
                View btnRemove = dialog.findViewById(R.id.btn_remove);
                View btnRight = dialog.findViewById(R.id.btn_move_right);

                if (entry.icon != null) icon.setImageDrawable(entry.icon);
                title.setText(entry.label != null ? entry.label : "App");

                View.OnFocusChangeListener focusListener = (v, hasFocus) -> {
                    float s = hasFocus ? 1.10f : 1.0f;
                    float z = hasFocus ? 10f : 0f;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        v.animate().scaleX(s).scaleY(s).translationZ(z).setDuration(120).start();
                    } else {
                        v.animate().scaleX(s).scaleY(s).setDuration(120).start();
                    }
                };

                btnLeft.setOnFocusChangeListener(focusListener);
                btnRemove.setOnFocusChangeListener(focusListener);
                btnRight.setOnFocusChangeListener(focusListener);

                if (idx == 0) {
                    btnLeft.setVisibility(View.GONE);
                } else {
                    btnLeft.setOnClickListener(v -> {
                        executor.execute(() -> {
                            List<String> list = PinnedAppsStore.load(appContext);
                            int pos = list.indexOf(key);
                            if (pos > 0) {
                                String tmp = list.get(pos - 1);
                                list.set(pos - 1, list.get(pos));
                                list.set(pos, tmp);
                                PinnedAppsStore.save(appContext, list);
                                mainHandler.post(() -> loadLauncherApps(appContext));
                            }
                        });
                        dialog.dismiss();
                    });
                }

                if (idx == pinned.size() - 1) {
                    btnRight.setVisibility(View.GONE);
                } else {
                    btnRight.setOnClickListener(v -> {
                        executor.execute(() -> {
                            List<String> list = PinnedAppsStore.load(appContext);
                            int pos = list.indexOf(key);
                            if (pos >= 0 && pos < list.size() - 1) {
                                String tmp = list.get(pos + 1);
                                list.set(pos + 1, list.get(pos));
                                list.set(pos, tmp);
                                PinnedAppsStore.save(appContext, list);
                                mainHandler.post(() -> loadLauncherApps(appContext));
                            }
                        });
                        dialog.dismiss();
                    });
                }

                btnRemove.setOnClickListener(v -> {
                    executor.execute(() -> {
                        List<String> list = PinnedAppsStore.load(appContext);
                        int pos = list.indexOf(key);
                        if (pos >= 0) {
                            list.remove(pos);
                            PinnedAppsStore.save(appContext, list);
                            mainHandler.post(() -> loadLauncherApps(appContext));
                        }
                    });
                    dialog.dismiss();
                });

                // Set focus back to default (remove gets focus first since it's the main action)
                btnRemove.requestFocus();

                dialog.show();
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
            updateHero("MQLTV · LIVE ENTERTAINMENT", "LIVE TV", card.getSubtitle() != null ? card.getSubtitle() : "+5000 Saluran TV Lokal & Internasional", 0xFF8B5CF6);
        } else if (card.getDestination() == NavDestination.SHOWS) {
            syncHomeFocusState(RADIO_CARD_POSITION);
            updateHero("MQLTV · DIGITAL AUDIO", "INTERNET RADIOS", card.getSubtitle() != null ? card.getSubtitle() : "Radio Streaming Saluran Lokal & Global", 0xFF06B6D4);
        }
    }
}
