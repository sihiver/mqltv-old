package com.mqltv;


import android.os.Bundle;
import android.view.View;

import androidx.fragment.app.FragmentActivity;

public class MainActivity extends FragmentActivity {
    private NavDestination currentDestination;
    private int homeFocusPosition = 0;
    private int homeAppsIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

    public void navigateTo(NavDestination destination) {
        if (destination == null) return;
        if (destination == NavDestination.LIVE_TV) {
            homeFocusPosition = 0;
        } else if (destination == NavDestination.SHOWS) {
            homeFocusPosition = 1;
        }
        showDestination(destination);
    }

    public void openSettings() {
        homeFocusPosition = 2;
        showSettings();
    }

    public void setHomeFocusPosition(int position) {
        homeFocusPosition = position;
    }

    public void setHomeAppsFocus(int index) {
        homeFocusPosition = 4;
        homeAppsIndex = Math.max(0, index);
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
                    .replace(R.id.content_container, LauncherFragment.newInstance(homeFocusPosition, homeAppsIndex))
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
                        .replace(R.id.content_container, new LiveTvFragment())
                        .commit();
                break;
            case MOVIES:
                showPlaceholder("Movies");
                break;
            case SHOWS:
                showPlaceholder("Shows");
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
}
