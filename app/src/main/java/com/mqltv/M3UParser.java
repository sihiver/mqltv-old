package com.mqltv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class M3UParser {
    private M3UParser() {}

    public static List<Channel> parse(InputStream inputStream) throws IOException {
        List<Channel> channels = new ArrayList<>();

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(inputStream, "UTF-8")
        );

        String pendingTitle = null;
        String pendingGroupTitle = null;
        String pendingLogoUrl = null;
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXTINF")) {
                int commaIndex = line.indexOf(',');
                String header = commaIndex >= 0 ? line.substring(0, commaIndex) : line;
                Map<String, String> attrs = parseAttributes(header);
                pendingGroupTitle = emptyToNull(attrs.get("group-title"));
                pendingLogoUrl = emptyToNull(attrs.get("tvg-logo"));

                if (commaIndex >= 0 && commaIndex + 1 < line.length()) {
                    pendingTitle = line.substring(commaIndex + 1).trim();
                } else {
                    String tvgName = emptyToNull(attrs.get("tvg-name"));
                    pendingTitle = tvgName != null ? tvgName : "Channel";
                }
                continue;
            }

            if (line.startsWith("#")) {
                continue;
            }

            // URL line
            String url = line;
            String title = pendingTitle != null ? pendingTitle : url;
            channels.add(new Channel(title, url, pendingGroupTitle, pendingLogoUrl));
            pendingTitle = null;
            pendingGroupTitle = null;
            pendingLogoUrl = null;
        }

        return channels;
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static Map<String, String> parseAttributes(String extinfHeader) {
        // Example: #EXTINF:-1 tvg-logo="http://..." group-title="News",Title
        Map<String, String> attrs = new HashMap<>();
        if (extinfHeader == null) return attrs;

        int i = extinfHeader.indexOf(' ');
        if (i < 0) return attrs;

        int n = extinfHeader.length();
        while (i < n) {
            while (i < n && extinfHeader.charAt(i) == ' ') i++;
            if (i >= n) break;

            int keyStart = i;
            while (i < n) {
                char c = extinfHeader.charAt(i);
                if (c == '=' || c == ' ') break;
                i++;
            }
            if (i >= n) break;

            String key = extinfHeader.substring(keyStart, i).trim();
            while (i < n && extinfHeader.charAt(i) == ' ') i++;
            if (i >= n || extinfHeader.charAt(i) != '=') {
                while (i < n && extinfHeader.charAt(i) != ' ') i++;
                continue;
            }
            i++; // '='
            while (i < n && extinfHeader.charAt(i) == ' ') i++;
            if (i >= n || extinfHeader.charAt(i) != '"') {
                while (i < n && extinfHeader.charAt(i) != ' ') i++;
                continue;
            }
            i++; // opening quote

            int valueStart = i;
            while (i < n && extinfHeader.charAt(i) != '"') i++;
            if (i <= n) {
                String value = extinfHeader.substring(valueStart, i);
                if (!key.isEmpty()) attrs.put(key, value);
            }
            if (i < n && extinfHeader.charAt(i) == '"') i++;
        }
        return attrs;
    }
}
