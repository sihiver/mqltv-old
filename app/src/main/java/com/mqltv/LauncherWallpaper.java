package com.mqltv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class LauncherWallpaper {
    private static final String TAG = "LauncherWallpaper";
    public static final String FILE_NAME = "launcher_wallpaper.jpg";
    public static final String BING_FILE_NAME = "launcher_wallpaper_bing.jpg";

    private static final String PREFS = "mqltv_wallpaper";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_BING_DATE = "bing_date";
    private static final String KEY_BING_URL = "bing_url";

    public static final String SOURCE_CUSTOM = "custom";
    public static final String SOURCE_BING = "bing";

    private static final String[] BING_API_URLS = {
            "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=id-ID",
            "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-US",
            "https://cn.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1"
    };

    public interface WallpaperListener {
        void onWallpaperChanged(Bitmap newWallpaper);
    }

    private static final List<WallpaperListener> sListeners = new CopyOnWriteArrayList<>();

    public static void addListener(WallpaperListener listener) {
        if (listener != null && !sListeners.contains(listener)) {
            sListeners.add(listener);
        }
    }

    public static void removeListener(WallpaperListener listener) {
        sListeners.remove(listener);
    }

    private static void notifyChanged(Context context) {
        sCachedBitmap = null;
        if (context == null) return;
        new Thread(() -> {
            Bitmap bmp = tryLoad(context);
            if (bmp != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    for (WallpaperListener l : sListeners) {
                        try {
                            l.onWallpaperChanged(bmp);
                        } catch (Exception ignored) {}
                    }
                });
            }
        }).start();
    }

    private LauncherWallpaper() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getSource(Context context) {
        if (context == null) return null;
        try {
            return prefs(context).getString(KEY_SOURCE, null);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String getBingDate(Context context) {
        if (context == null) return null;
        try {
            return prefs(context).getString(KEY_BING_DATE, null);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static File getFile(Context context) {
        if (context == null) return null;
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static File getBingFile(Context context) {
        if (context == null) return null;
        return new File(context.getFilesDir(), BING_FILE_NAME);
    }

    private static Bitmap sCachedBitmap;
    private static long sCachedTime;

    public static Bitmap getCachedBitmap() {
        if (sCachedBitmap != null && System.currentTimeMillis() - sCachedTime < 3600_000) {
            return sCachedBitmap;
        }
        return null;
    }

    public static boolean clear(Context context) {
        sCachedBitmap = null;
        try {
            File f = getFile(context);
            boolean ok = f != null && (!f.exists() || f.delete());
            // If user clears custom wallpaper, fall back to Bing auto mode.
            if (ok && context != null) {
                try {
                    prefs(context).edit().putString(KEY_SOURCE, SOURCE_BING).apply();
                } catch (Exception ignored) {
                }
                notifyChanged(context);
            }
            return ok;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean save(Context context, InputStream input) {
        boolean ok = saveToFile(context, input, FILE_NAME, true);
        if (ok && context != null) {
            notifyChanged(context);
        }
        return ok;
    }

    private static boolean saveToFile(Context context, InputStream input, String fileName, boolean markCustom) {
        sCachedBitmap = null;
        if (context == null || input == null) return false;

        File out = new File(context.getFilesDir(), fileName);
        File tmp = new File(context.getFilesDir(), fileName + ".tmp");

        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(tmp))) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = input.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            bos.flush();
        } catch (Exception ignored) {
            try { tmp.delete(); } catch (Exception ignored2) {}
            return false;
        }

        try {
            if (out.exists() && !out.delete()) {
                // continue; rename may still overwrite on some filesystems
            }
            if (tmp.renameTo(out)) {
                return true;
            }

            // Fallback copy if rename fails.
            try (FileInputStream fis = new FileInputStream(tmp);
                 BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out))) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = fis.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                bos.flush();
            }
            try { tmp.delete(); } catch (Exception ignored) {}
            if (markCustom) {
                try {
                    prefs(context).edit().putString(KEY_SOURCE, SOURCE_CUSTOM).apply();
                } catch (Exception ignored2) {
                }
            }
            return true;
        } catch (Exception ignored) {
            try { tmp.delete(); } catch (Exception ignored2) {}
            return false;
        }
    }

    public static Bitmap tryLoad(Context context) {
        if (context == null) return null;
        
        Bitmap cached = getCachedBitmap();
        if (cached != null) return cached;

        Bitmap decoded = doLoad(context);
        if (decoded != null) {
            sCachedBitmap = decoded;
            sCachedTime = System.currentTimeMillis();
        }
        return decoded;
    }

    private static Bitmap doLoad(Context context) {
        String src = null;
        try {
            src = getSource(context);
        } catch (Exception ignored) {
        }

        // 1) If user explicitly selected custom wallpaper in this version, prefer it.
        if (SOURCE_CUSTOM.equals(src)) {
            try {
                File f = getFile(context);
                if (f.exists() && f.length() > 0) {
                    try (FileInputStream fis = new FileInputStream(f)) {
                        return decodeScaled(context, new BufferedInputStream(fis));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 2) Auto Bing wallpaper (cached daily). Never do network on main thread.
        try {
            File f = getBingFile(context);
            if (f.exists() && f.length() > 0) {
                // Return cached file immediately, but check for updates in background
                boolean mainThread = Looper.getMainLooper() == Looper.myLooper();
                if (!mainThread) {
                    new Thread(() -> ensureBingUpToDate(context)).start();
                }
                try (FileInputStream fis = new FileInputStream(f)) {
                    return decodeScaled(context, new BufferedInputStream(fis));
                }
            } else {
                boolean mainThread = Looper.getMainLooper() == Looper.myLooper();
                if (!mainThread) {
                    ensureBingUpToDate(context);
                } else {
                    new Thread(() -> ensureBingUpToDate(context)).start();
                }
                if (f.exists() && f.length() > 0) {
                    try (FileInputStream fis = new FileInputStream(f)) {
                        return decodeScaled(context, new BufferedInputStream(fis));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 3) Legacy fallback: if an older build saved a custom wallpaper file but didn't
        // record the preference, still use it when Bing isn't available.
        try {
            File f = getFile(context);
            if (f.exists() && f.length() > 0) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    return decodeScaled(context, new BufferedInputStream(fis));
                }
            }
        } catch (Exception ignored) {
        }

        // 4) Asset fallback (optional): app/src/main/assets/launcher_wallpaper.jpg
        try {
            try (InputStream is = new BufferedInputStream(context.getAssets().open(FILE_NAME))) {
                return decodeScaled(context, is);
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    public static void forceRefreshBing(Context context) {
        if (context == null) return;
        new Thread(() -> {
            try {
                File cached = getBingFile(context);
                if (cached != null && cached.exists()) {
                    cached.delete();
                }
                prefs(context).edit().remove(KEY_BING_DATE).putString(KEY_SOURCE, SOURCE_BING).apply();
            } catch (Exception ignored) {
            }
            ensureBingUpToDate(context);
        }).start();
    }

    private static void ensureBingUpToDate(Context context) {
        if (context == null) return;

        try {
            // Only run in auto mode if user hasn't set custom.
            String src = getSource(context);
            if (SOURCE_CUSTOM.equals(src)) return;
        } catch (Exception ignored) {
        }

        File cached = getBingFile(context);
        String lastDate = getBingDate(context);

        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

        OkHttpClient client = NetworkClient.getWallpaperClient();
        if (client == null) {
            client = NetworkClient.getClient();
        }

        String targetUrl = null;
        String targetDate = null;

        for (String apiUrl : BING_API_URLS) {
            Response resp = null;
            try {
                Log.d(TAG, "Bing: fetch json from " + apiUrl);
                Request req = new Request.Builder()
                        .url(apiUrl)
                        .header("User-Agent", ua)
                        .header("Accept", "application/json")
                        .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
                        .get()
                        .build();
                resp = client.newCall(req).execute();
                if (!resp.isSuccessful() || resp.body() == null) {
                    Log.w(TAG, "Bing JSON HTTP failed (" + apiUrl + "): " + (resp != null ? resp.code() : "null"));
                    continue;
                }

                String json = resp.body().string();
                if (json == null || json.trim().isEmpty()) continue;

                JSONObject obj = new JSONObject(json);
                // Check official Bing schema: images[0]
                JSONArray images = obj.optJSONArray("images");
                if (images != null && images.length() > 0) {
                    JSONObject first = images.getJSONObject(0);
                    targetDate = first.optString("startdate", first.optString("enddate", ""));
                    String urlbase = first.optString("urlbase", "");
                    if (!urlbase.isEmpty()) {
                        targetUrl = urlbase + "_1920x1080.jpg";
                    } else {
                        targetUrl = first.optString("url", "");
                    }
                } else if (obj.has("url")) {
                    targetUrl = obj.optString("url", "");
                    targetDate = obj.optString("start_date", "");
                }

                if (targetUrl != null && !targetUrl.trim().isEmpty()) {
                    targetUrl = targetUrl.trim();
                    if (targetUrl.startsWith("//")) targetUrl = "https:" + targetUrl;
                    if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                        String host = apiUrl.contains("cn.bing.com") ? "https://cn.bing.com" : "https://www.bing.com";
                        targetUrl = host + (targetUrl.startsWith("/") ? "" : "/") + targetUrl;
                    }
                    Log.d(TAG, "Bing JSON parsed ok: date=" + targetDate + " url=" + targetUrl);
                    break;
                }
            } catch (Exception e) {
                Log.w(TAG, "Bing JSON attempt failed for " + apiUrl + ": " + e.getMessage());
            } finally {
                if (resp != null) {
                    try { resp.close(); } catch (Exception ignored) {}
                }
            }
        }

        if (targetUrl == null || targetUrl.isEmpty()) {
            Log.w(TAG, "Bing: unable to retrieve wallpaper URL from all endpoints");
            return;
        }

        boolean upToDate = cached.exists() && cached.length() > 0 && targetDate != null && targetDate.equals(lastDate);
        if (upToDate) {
            try {
                prefs(context).edit().putString(KEY_SOURCE, SOURCE_BING).putString(KEY_BING_URL, targetUrl).apply();
            } catch (Exception ignored) {
            }
            Log.d(TAG, "Bing wallpaper up-to-date (cached)");
            return;
        }

        // Download image and cache.
        Response imgResp = null;
        try {
            Log.d(TAG, "Bing: download image from " + targetUrl);
            Request imgReq = new Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", ua)
                    .header("Accept", "image/jpeg,image/webp,image/*,*/*;q=0.8")
                    .get()
                    .build();
            imgResp = client.newCall(imgReq).execute();
            if (!imgResp.isSuccessful() || imgResp.body() == null) {
                Log.w(TAG, "Bing image failed: http=" + imgResp.code());
                return;
            }

            long len = imgResp.body().contentLength();
            if (len > 25L * 1024L * 1024L) return;

            boolean ok;
            try (InputStream is = imgResp.body().byteStream()) {
                ok = saveToFile(context, is, BING_FILE_NAME, false);
            }
            if (!ok) return;

            File out = getBingFile(context);
            long written = out.exists() ? out.length() : -1;
            Log.d(TAG, "Bing wallpaper saved: bytes=" + len + " written=" + written);

            try {
                SharedPreferences.Editor e = prefs(context).edit();
                e.putString(KEY_SOURCE, SOURCE_BING);
                if (targetDate != null && !targetDate.trim().isEmpty()) e.putString(KEY_BING_DATE, targetDate.trim());
                e.putString(KEY_BING_URL, targetUrl);
                e.apply();
            } catch (Exception ignored) {
            }

            // Notify UI listener that Bing wallpaper has been downloaded
            notifyChanged(context);
        } catch (Exception ignored) {
            Log.w(TAG, "Bing image download failed: " + ignored.getClass().getSimpleName() + ": " + ignored.getMessage());
        } finally {
            if (imgResp != null) {
                try { imgResp.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static Bitmap decodeScaled(Context context, InputStream is) {
        try {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            int reqW = Math.max(1, dm.widthPixels);
            int reqH = Math.max(1, dm.heightPixels);

            // Read bounds first.
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            is.mark(8 * 1024 * 1024);
            BitmapFactory.decodeStream(is, null, opts);
            try { is.reset(); } catch (Exception ignored) {
                // If reset fails, we can't decode twice.
                return BitmapFactory.decodeStream(is);
            }

            opts.inSampleSize = calculateInSampleSize(opts, reqW, reqH);
            opts.inJustDecodeBounds = false;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(is, null, opts);
        } catch (Exception ignored) {
            try {
                return BitmapFactory.decodeStream(is);
            } catch (Exception ignored2) {
                return null;
            }
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }
}
