package com.mqltv;

public final class Channel {
    private final String title;
    private final String url;

    public Channel(String title, String url) {
        this.title = title;
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }
}
