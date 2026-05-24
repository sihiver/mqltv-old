package com.mqltv;

import androidx.annotation.Nullable;

public final class Channel {
    private final String title;
    private final String url;

    private final String groupTitle;
    private final String logoUrl;
    private final String sourceId;

    @Nullable
    private final ChannelPlaybackMeta playbackMeta;

    public Channel(String title, String url) {
        this(title, url, null, null, null, null);
    }

    public Channel(String title, String url, String groupTitle, String logoUrl) {
        this(title, url, groupTitle, logoUrl, null, null);
    }

    public Channel(String title, String url, String groupTitle, String logoUrl,
                   @Nullable String sourceId, @Nullable ChannelPlaybackMeta playbackMeta) {
        this.title = title;
        this.url = url;
        this.groupTitle = groupTitle;
        this.logoUrl = logoUrl;
        this.sourceId = sourceId;
        this.playbackMeta = playbackMeta;
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

    @Nullable
    public String getSourceId() {
        return sourceId;
    }

    @Nullable
    public ChannelPlaybackMeta getPlaybackMeta() {
        return playbackMeta;
    }

    public boolean hasVisionPlusPlayback() {
        return playbackMeta != null && playbackMeta.isActive();
    }
}
