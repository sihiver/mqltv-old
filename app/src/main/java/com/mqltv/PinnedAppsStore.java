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

    private static String packageOf(String component) {
        if (component == null) return "";
        int slash = component.indexOf('/');
        return slash > 0 ? component.substring(0, slash).trim() : component.trim();
    }

    public static List<String> load(Context context) {
        List<String> out = new ArrayList<>();
        if (context == null) return out;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_LIST, null);
            if (raw == null || raw.trim().isEmpty()) return out;
            JSONArray arr = new JSONArray(raw);
            java.util.Set<String> seenPackages = new java.util.HashSet<>();
            boolean hadDuplicates = false;
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, null);
                if (s != null && !s.trim().isEmpty()) {
                    String pkg = packageOf(s);
                    if (!pkg.isEmpty() && seenPackages.add(pkg)) {
                        out.add(s);
                    } else {
                        hadDuplicates = true;
                    }
                }
            }
            if (hadDuplicates) {
                save(context, out);
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
            java.util.Set<String> seenPackages = new java.util.HashSet<>();
            if (components != null) {
                for (String c : components) {
                    if (c != null && !c.trim().isEmpty()) {
                        String pkg = packageOf(c);
                        if (!pkg.isEmpty() && seenPackages.add(pkg)) {
                            arr.put(c);
                        }
                    }
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
        String targetPkg = packageOf(component);
        List<String> list = load(context);
        for (String existing : list) {
            if (component.equals(existing) || (!targetPkg.isEmpty() && targetPkg.equals(packageOf(existing)))) {
                return;
            }
        }
        list.add(component);
        save(context, list);
    }

    public static void remove(Context context, String component) {
        if (context == null || component == null || component.trim().isEmpty()) return;
        String targetPkg = packageOf(component);
        List<String> list = load(context);
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            String existing = list.get(i);
            if (component.equals(existing) || (!targetPkg.isEmpty() && targetPkg.equals(packageOf(existing)))) {
                list.remove(i);
                changed = true;
            }
        }
        if (changed) {
            save(context, list);
        }
    }
}
