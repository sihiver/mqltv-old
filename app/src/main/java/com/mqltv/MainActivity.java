package com.mqltv;


import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

import androidx.fragment.app.FragmentActivity;

public class MainActivity extends FragmentActivity {
    private static final String PREFS_HOME_STATE = "home_state";
    private static final String KEY_FOCUS_POS = "focus_pos";
    private static final String KEY_APPS_INDEX = "apps_index";
    private static final String KEY_RECENT_URL = "recent_url";
    private static final String KEY_RECENT_INDEX = "recent_index";

    private NavDestination currentDestination;
    private int homeFocusPosition = 0;
    private int homeAppsIndex = 0;
    private String homeRecentUrl;
    private int homeRecentIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoreHomeState();

        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            showDestination(NavDestination.HOME);

            // If launched after login with a target destination, navigate there.
            String dest = getIntent() != null ? getIntent().getStringExtra(LoginActivity.EXTRA_AFTER_LOGIN_DEST) : null;
            if (LoginActivity.DEST_LIVE_TV.equals(dest)) {
                showDestination(NavDestination.LIVE_TV);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        AccountStatusRefresher.refreshIfDue(this);
    }

    @Override
    protected void onPause() {
        persistHomeState();
        super.onPause();
    }

    public void navigateTo(NavDestination destination) {
        if (destination == null) return;
        if (destination == NavDestination.LIVE_TV) {
            homeFocusPosition = 0;
        } else if (destination == NavDestination.SHOWS) {
            homeFocusPosition = 1;
        }
        persistHomeState();
        showDestination(destination);
    }

    public void openSettings() {
        homeFocusPosition = 2;
        persistHomeState();
        showSettings();
    }

    public void setHomeFocusPosition(int position) {
        homeFocusPosition = position;
        persistHomeState();
    }

    public void setHomeAppsFocus(int index) {
        homeFocusPosition = 4;
        homeAppsIndex = Math.max(0, index);
        persistHomeState();
    }

    public void setHomeRecentFocus(String url, int index) {
        homeFocusPosition = 5;
        homeRecentUrl = url;
        homeRecentIndex = Math.max(0, index);
        persistHomeState();
    }

    private void showPlaceholder(String title) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content_container, PlaceholderFragment.newInstance(title))
                .commit();
    }

    private void showSettings() {
        currentDestination = null;
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content_container, new SettingsFragment())
                .runOnCommit(() -> {
                    View root = getWindow().getDecorView();
                    root.post(() -> {
                        View auto = root.findViewById(R.id.player_mode_auto);
                        if (auto != null) {
                            auto.requestFocus();
                        }
                    });
                })
                .commit();
    }

    private void showDestination(NavDestination destination) {
        if (destination == currentDestination) return;
        currentDestination = destination;

        switch (destination) {
            case HOME:
                getSupportFragmentManager().beginTransaction()
                    .replace(R.id.content_container, LauncherFragment.newInstance(homeFocusPosition, homeAppsIndex, homeRecentUrl, homeRecentIndex))
                        .commit();
                break;
            case LIVE_TV:
                if (!LoginGuard.ensureLoggedIn(this, LoginActivity.DEST_LIVE_TV)) {
                    return;
                }
                if (!SubscriptionGuard.ensureNotExpired(this)) {
                    return;
                }
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.content_container, LiveTvFragment.newInstance(false))
                        .commit();
                break;
            case MOVIES:
                showPlaceholder("Movies");
                break;
            case SHOWS:
                if (!LoginGuard.ensureLoggedIn(this, LoginActivity.DEST_LIVE_TV)) {
                    return;
                }
                if (!SubscriptionGuard.ensureNotExpired(this)) {
                    return;
                }
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.content_container, LiveTvFragment.newInstance(true))
                        .commit();
                break;
            case LIBRARY:
                showPlaceholder("Library");
                break;
        }
    }

    @Override
    public void onBackPressed() {
        // Launcher-style behavior: don't finish the Home activity.
        if (currentDestination != NavDestination.HOME) {
            showDestination(NavDestination.HOME);
        }
        // At root: ignore back to avoid exiting the Home app.
    }

    private void persistHomeState() {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS_HOME_STATE, MODE_PRIVATE);
            sp.edit()
                    .putInt(KEY_FOCUS_POS, homeFocusPosition)
                    .putInt(KEY_APPS_INDEX, homeAppsIndex)
                    .putString(KEY_RECENT_URL, homeRecentUrl)
                    .putInt(KEY_RECENT_INDEX, homeRecentIndex)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private void restoreHomeState() {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS_HOME_STATE, MODE_PRIVATE);
            homeFocusPosition = sp.getInt(KEY_FOCUS_POS, 0);
            homeAppsIndex = Math.max(0, sp.getInt(KEY_APPS_INDEX, 0));
            homeRecentUrl = sp.getString(KEY_RECENT_URL, null);
            homeRecentIndex = Math.max(0, sp.getInt(KEY_RECENT_INDEX, 0));
        } catch (Exception ignored) {
            homeFocusPosition = 0;
            homeAppsIndex = 0;
            homeRecentUrl = null;
            homeRecentIndex = 0;
        }
    }
}
