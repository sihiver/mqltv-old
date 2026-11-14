package com.mqltv;

import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * In-memory LRU cache for resolved channel stream URLs and DRM metadata.
 * Dramatically speeds up channel zapping (CH+/CH-) when revisiting recently watched channels
 * while respecting a short TTL so tokenized URLs remain fresh.
 */
public final class StreamInfoCache {

    private static final long TTL_MS = 5L * 60L * 1000L; // 5 minutes

    public static final class Entry {
        @NonNull public final String streamUrl;
        @Nullable public final ChannelPlaybackMeta meta;
        public final long timestampMs;

        public Entry(@NonNull String streamUrl, @Nullable ChannelPlaybackMeta meta) {
            this.streamUrl = streamUrl;
            this.meta = meta;
            this.timestampMs = android.os.SystemClock.elapsedRealtime();
        }

        public boolean isExpired() {
            return (android.os.SystemClock.elapsedRealtime() - timestampMs) > TTL_MS;
        }
    }

    private static final LruCache<Integer, Entry> CACHE = new LruCache<>(64);

    private StreamInfoCache() {}

    @Nullable
    public static synchronized Entry get(int channelId) {
        if (channelId <= 0) return null;
        Entry entry = CACHE.get(channelId);
        if (entry == null) return null;
        if (entry.isExpired()) {
            CACHE.remove(channelId);
            return null;
        }
        return entry;
    }

    public static synchronized void put(int channelId, @NonNull String streamUrl, @Nullable ChannelPlaybackMeta meta) {
        if (channelId <= 0 || streamUrl.isEmpty()) return;
        CACHE.put(channelId, new Entry(streamUrl, meta));
    }

    public static synchronized void invalidate(int channelId) {
        if (channelId <= 0) return;
        CACHE.remove(channelId);
    }

    public static synchronized void clear() {
        CACHE.evictAll();
    }
}

