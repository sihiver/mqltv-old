package com.mqltv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public final class M3UParser {
    private M3UParser() {}

    public static List<Channel> parse(InputStream inputStream) throws IOException {
        List<Channel> channels = new ArrayList<>();

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(inputStream, "UTF-8")
        );

        String pendingTitle = null;
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXTINF")) {
                int commaIndex = line.indexOf(',');
                if (commaIndex >= 0 && commaIndex + 1 < line.length()) {
                    pendingTitle = line.substring(commaIndex + 1).trim();
                } else {
                    pendingTitle = "Channel";
                }
                continue;
            }

            if (line.startsWith("#")) {
                continue;
            }

            // URL line
            String url = line;
            String title = pendingTitle != null ? pendingTitle : url;
            channels.add(new Channel(title, url));
            pendingTitle = null;
        }

        return channels;
    }
}
