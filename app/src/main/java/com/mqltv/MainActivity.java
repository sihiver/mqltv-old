package com.mqltv;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

public class MainActivity extends FragmentActivity {
    private NavDestination currentDestination;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!AuthPrefs.isLoggedIn(this)) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            showDestination(NavDestination.HOME);
        }
    }

    public void navigateTo(NavDestination destination) {
        if (destination == null) return;
        showDestination(destination);
    }

    public void openSettings() {
        showSettings();
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
                        .replace(R.id.content_container, new LauncherFragment())
                        .commit();
                break;
            case LIVE_TV:
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
            return;
        }
        // At root: ignore back to avoid exiting the Home app.
    }
}
