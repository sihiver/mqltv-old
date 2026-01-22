package com.mqltv;

import java.util.List;

public final class HomeSection {
    private final String title;
    private final List<Channel> channels;

    public HomeSection(String title, List<Channel> channels) {
        this.title = title;
        this.channels = channels;
    }

    public String getTitle() {
        return title;
    }

    public List<Channel> getChannels() {
        return channels;
    }
}
