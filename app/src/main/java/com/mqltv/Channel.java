package com.mqltv;

public final class Channel {
    private final String title;
    private final String url;

    private final String groupTitle;
    private final String logoUrl;

    public Channel(String title, String url) {
        this.title = title;
        this.url = url;
        this.groupTitle = null;
        this.logoUrl = null;
    }

    public Channel(String title, String url, String groupTitle, String logoUrl) {
        this.title = title;
        this.url = url;
        this.groupTitle = groupTitle;
        this.logoUrl = logoUrl;
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
}
