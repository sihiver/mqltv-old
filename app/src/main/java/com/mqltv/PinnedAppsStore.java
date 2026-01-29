package com.mqltv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** Stores pinned launcher apps as flattened ComponentName strings. */
public final class PinnedAppsStore {
    private static final String PREFS = "mql_pinned_apps";
    private static final String KEY_LIST = "components";
    private static final String KEY_INITIALIZED = "initialized";

    private PinnedAppsStore() {}

    public static List<String> load(Context context) {
        List<String> out = new ArrayList<>();
        if (context == null) return out;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_LIST, null);
            if (raw == null || raw.trim().isEmpty()) return out;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, null);
                if (s != null && !s.trim().isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static boolean isInitialized(Context context) {
        if (context == null) return false;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.getBoolean(KEY_INITIALIZED, false);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void save(Context context, List<String> components) {
        if (context == null) return;
        try {
            JSONArray arr = new JSONArray();
            if (components != null) {
                for (String c : components) {
                    if (c != null && !c.trim().isEmpty()) arr.put(c);
                }
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LIST, arr.toString())
                    .putBoolean(KEY_INITIALIZED, true)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    public static void add(Context context, String component) {
        if (context == null || component == null || component.trim().isEmpty()) return;
        List<String> list = load(context);
        if (!list.contains(component)) {
            list.add(component);
            save(context, list);
        }
    }
}
