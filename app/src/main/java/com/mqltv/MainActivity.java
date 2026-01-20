package com.mqltv;

import android.animation.ValueAnimator;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends FragmentActivity implements NavAdapter.Listener {

    private LinearLayout navPanel;
    private TextView navTitle;
    private TextView settingsText;
    private TextView helpText;
    private RecyclerView navList;

    private NavAdapter navAdapter;
    private boolean navExpanded = false;
    private NavDestination currentDestination;

    private final ViewTreeObserver.OnGlobalFocusChangeListener focusListener = (oldFocus, newFocus) -> {
        boolean inNav = newFocus != null && isDescendant(navPanel, newFocus);
        setNavExpanded(inNav);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        navPanel = findViewById(R.id.nav_panel);
        navTitle = findViewById(R.id.nav_title);
        settingsText = findViewById(R.id.nav_action_settings_text);
        helpText = findViewById(R.id.nav_action_help_text);
        navList = findViewById(R.id.nav_list);

        setupNav();

        if (savedInstanceState == null) {
            showDestination(NavDestination.HOME);
        }
    }

    private void setupNav() {
        List<NavItem> items = new ArrayList<>();
        items.add(new NavItem(NavDestination.HOME, android.R.drawable.ic_menu_view, "Home"));
        items.add(new NavItem(NavDestination.LIVE_TV, android.R.drawable.ic_media_play, "Live TV"));
        items.add(new NavItem(NavDestination.MOVIES, android.R.drawable.ic_menu_slideshow, "Movies"));
        items.add(new NavItem(NavDestination.SHOWS, android.R.drawable.ic_menu_agenda, "Shows"));
        items.add(new NavItem(NavDestination.LIBRARY, android.R.drawable.ic_menu_sort_by_size, "Library"));

        navAdapter = new NavAdapter(items, this);
        navList.setLayoutManager(new LinearLayoutManager(this));
        navList.setAdapter(navAdapter);

        View header = findViewById(R.id.nav_header);
        header.setOnClickListener(v -> {
            // Placeholder: bisa dipakai untuk switch profile / search
            showDestination(NavDestination.HOME);
        });

        View settings = findViewById(R.id.nav_action_settings);
        settings.setOnClickListener(v -> showPlaceholder("Settings"));

        View help = findViewById(R.id.nav_action_help);
        help.setOnClickListener(v -> showPlaceholder("Help"));

        // Start collapsed
        applyExpandedState(false);

        // Focus starts on nav for TV UX
        header.requestFocus();
    }

    private void showPlaceholder(String title) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content_container, PlaceholderFragment.newInstance(title))
                .commit();
    }

    private void showDestination(NavDestination destination) {
        if (destination == currentDestination) return;
        currentDestination = destination;
        if (navAdapter != null) navAdapter.setSelected(destination);

        switch (destination) {
            case HOME:
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.content_container, new HomeFragment())
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

    private void setNavExpanded(boolean expanded) {
        if (navExpanded == expanded) return;
        navExpanded = expanded;

        int targetWidth = getResources().getDimensionPixelSize(
                expanded ? R.dimen.nav_width_expanded : R.dimen.nav_width_collapsed
        );
        int startWidth = navPanel.getLayoutParams().width;

        ValueAnimator animator = ValueAnimator.ofInt(startWidth, targetWidth);
        animator.setDuration(180);
        animator.addUpdateListener(a -> {
            ViewGroup.LayoutParams lp = navPanel.getLayoutParams();
            lp.width = (int) a.getAnimatedValue();
            navPanel.setLayoutParams(lp);
        });
        animator.start();

        applyExpandedState(expanded);
    }

    private void applyExpandedState(boolean expanded) {
        if (navPanel != null) {
            int padH = getResources().getDimensionPixelSize(
                    expanded ? R.dimen.nav_panel_padding_h_expanded : R.dimen.nav_panel_padding_h_collapsed
            );
            int padV = getResources().getDimensionPixelSize(R.dimen.nav_panel_padding_v);
            navPanel.setPadding(padH, padV, padH, padV);
        }
        navTitle.setVisibility(expanded ? View.VISIBLE : View.GONE);
        settingsText.setVisibility(expanded ? View.VISIBLE : View.GONE);
        helpText.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (navAdapter != null) navAdapter.setExpanded(expanded);
    }

    private static boolean isDescendant(ViewGroup root, View child) {
        if (root == null || child == null) return false;
        View v = child;
        while (v != null) {
            if (v == root) return true;
            if (!(v.getParent() instanceof View)) return false;
            v = (View) v.getParent();
        }
        return false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(focusListener);
    }

    @Override
    protected void onStop() {
        super.onStop();
        getWindow().getDecorView().getViewTreeObserver().removeOnGlobalFocusChangeListener(focusListener);
    }

    @Override
    public void onDestinationClicked(NavDestination destination) {
        showDestination(destination);
    }
}
