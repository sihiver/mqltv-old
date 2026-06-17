package com.mqltv;

import androidx.annotation.Nullable;

public final class Channel {
    private final int id;
    private final String title;
    private String url;

    private final String groupTitle;
    private final String logoUrl;
    private final boolean isLive;
    private final int viewerCount;

    public Channel(int id, String title, String groupTitle, String logoUrl, boolean isLive, int viewerCount) {
        this.id = id;
        this.title = title;
        this.groupTitle = groupTitle;
        this.logoUrl = logoUrl;
        this.isLive = isLive;
        this.viewerCount = viewerCount;
        this.url = "";
    }

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }

    public String getGroupTitle() {
        return groupTitle;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public int getId() {
        return id;
    }

    public String getSourceId() {
        return String.valueOf(id);
    }

    public boolean isLive() {
        return isLive;
    }

    public int getViewerCount() {
        return viewerCount;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
