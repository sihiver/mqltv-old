package com.mqltv;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Detects M3U vs Vision+ JSON and parses to {@link Channel} list. */
public final class PlaylistParser {
    private PlaylistParser() {}

    public static List<Channel> parse(InputStream inputStream) throws IOException {
        if (inputStream == null) return java.util.Collections.emptyList();

        BufferedInputStream buffered = inputStream instanceof BufferedInputStream
                ? (BufferedInputStream) inputStream
                : new BufferedInputStream(inputStream);
        buffered.mark(4096);
        byte[] head = new byte[256];
        int read = buffered.read(head);
        buffered.reset();

        String prefix = "";
        if (read > 0) {
            prefix = new String(head, 0, read, "UTF-8").trim();
        }

        if (VisionPlusPlaylistParser.looksLikeJson(prefix)) {
            return VisionPlusPlaylistParser.parse(buffered);
        }
        return M3UParser.parse(buffered);
    }
}
