package com.mqltv;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

public final class PlaylistRepository {
    private static final String DEFAULT_ASSET = "channels.m3u";

    public List<Channel> loadDefault(Context context) {
        InputStream inputStream = null;
        try {
            inputStream = context.getAssets().open(DEFAULT_ASSET);
            return M3UParser.parse(inputStream);
        } catch (IOException e) {
            return Collections.emptyList();
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
