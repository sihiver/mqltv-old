package com.mqltv;

public final class NavItem {
    private final NavDestination destination;
    private final int iconRes;
    private final String title;

    public NavItem(NavDestination destination, int iconRes, String title) {
        this.destination = destination;
        this.iconRes = iconRes;
        this.title = title;
    }

    public NavDestination getDestination() {
        return destination;
    }

    public int getIconRes() {
        return iconRes;
    }

    public String getTitle() {
        return title;
    }
}
