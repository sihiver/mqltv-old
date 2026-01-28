package com.mqltv;

public final class LauncherCard {
    private final String title;
    private final String subtitle;
    private final int iconRes;
    private final NavDestination destination;

    public LauncherCard(String title, String subtitle, int iconRes, NavDestination destination) {
        this.title = title;
        this.subtitle = subtitle;
        this.iconRes = iconRes;
        this.destination = destination;
    }

    public String getTitle() {
        return title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public int getIconRes() {
        return iconRes;
    }

    public NavDestination getDestination() {
        return destination;
    }
}
