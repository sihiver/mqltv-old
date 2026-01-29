package com.mqltv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Looper;
import android.util.Log;
import android.util.DisplayMetrics;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

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

    private static final String BING_API_URL = "https://bing.biturl.top/?resolution=UHD&format=json&index=0&mkt=id-ID";

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

    public static boolean clear(Context context) {
        try {
            File f = getFile(context);
            boolean ok = f != null && (!f.exists() || f.delete());
            // If user clears custom wallpaper, fall back to Bing auto mode.
            if (ok && context != null) {
                try {
                    prefs(context).edit().putString(KEY_SOURCE, SOURCE_BING).apply();
                } catch (Exception ignored) {
                }
            }
            return ok;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean save(Context context, InputStream input) {
        return saveToFile(context, input, FILE_NAME, true);
    }

    private static boolean saveToFile(Context context, InputStream input, String fileName, boolean markCustom) {
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

        String src = null;
        try {
            src = getSource(context);
        } catch (Exception ignored) {
        }

        // 1) If user explicitly selected custom wallpaper in this version, prefer it.
        if (SOURCE_CUSTOM.equals(src)) {
            try {
                File f = getFile(context);
                if (f != null && f.exists() && f.length() > 0) {
                    try (FileInputStream fis = new FileInputStream(f)) {
                        return decodeScaled(context, new BufferedInputStream(fis));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 2) Auto Bing wallpaper (cached daily). Never do network on main thread.
        // In auto mode (src != custom), try Bing first so auto-update actually changes
        // even if a legacy custom wallpaper file exists.
        try {
            boolean mainThread = Looper.getMainLooper() == Looper.myLooper();
            if (!mainThread) {
                ensureBingUpToDate(context);
            }

            File f = getBingFile(context);
            if (f != null && f.exists() && f.length() > 0) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    return decodeScaled(context, new BufferedInputStream(fis));
                }
            }
        } catch (Exception ignored) {
        }

        // 3) Legacy fallback: if an older build saved a custom wallpaper file but didn't
        // record the preference, still use it when Bing isn't available.
        try {
            File f = getFile(context);
            if (f != null && f.exists() && f.length() > 0) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    return decodeScaled(context, new BufferedInputStream(fis));
                }
            }
        } catch (Exception ignored) {
        }

        // 4) Asset fallback (optional): app/src/main/assets/launcher_wallpaper.jpg
        try {
            InputStream is = new BufferedInputStream(context.getAssets().open(FILE_NAME));
            try {
                return decodeScaled(context, is);
            } finally {
                try { is.close(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
        }

        return null;
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

        String ua = "MQLTV/1.0 (Android " + Build.VERSION.RELEASE + "; SDK " + Build.VERSION.SDK_INT + ")";

        Response resp = null;
        try {
            Log.d(TAG, "Bing: fetch json...");
            Request req = new Request.Builder()
                    .url(BING_API_URL)
                    .header("User-Agent", ua)
                    .header("Accept", "application/json")
                    .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
                    .get()
                    .build();
            resp = NetworkClient.getClient().newCall(req).execute();
            if (resp.handshake() != null) {
                Log.d(TAG, "Bing: json tls=" + resp.handshake().tlsVersion() + " cipher=" + resp.handshake().cipherSuite());
            }
            if (!resp.isSuccessful() || resp.body() == null) {
                Log.w(TAG, "Bing JSON failed: http=" + (resp != null ? resp.code() : 0));
                return;
            }

            String json = resp.body().string();
            if (json == null || json.trim().isEmpty()) return;

            JSONObject obj = new JSONObject(json);
            String url = obj.optString("url", null);
            String date = obj.optString("start_date", null);

            Log.d(TAG, "Bing JSON ok: date=" + date + " last=" + lastDate + " url=" + url);

            if (url == null || url.trim().isEmpty()) return;
            url = url.trim();
            if (url.startsWith("//")) url = "https:" + url;
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://www.bing.com" + (url.startsWith("/") ? "" : "/") + url;
            }

            boolean upToDate = cached != null && cached.exists() && cached.length() > 0 && date != null && date.equals(lastDate);
            if (upToDate) {
                try {
                    prefs(context).edit().putString(KEY_SOURCE, SOURCE_BING).putString(KEY_BING_URL, url).apply();
                } catch (Exception ignored) {
                }
                Log.d(TAG, "Bing wallpaper up-to-date (cached)");
                return;
            }

            // Download image and cache.
            Response imgResp = null;
            try {
                Log.d(TAG, "Bing: download image...");
                Request imgReq = new Request.Builder()
                        .url(url)
                        .header("User-Agent", ua)
                        .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                        .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
                        .get()
                        .build();
                imgResp = NetworkClient.getClient().newCall(imgReq).execute();
                if (imgResp.handshake() != null) {
                    Log.d(TAG, "Bing: img tls=" + imgResp.handshake().tlsVersion() + " cipher=" + imgResp.handshake().cipherSuite());
                }
                if (!imgResp.isSuccessful() || imgResp.body() == null) {
                    Log.w(TAG, "Bing image failed: http=" + (imgResp != null ? imgResp.code() : 0));
                    return;
                }

                long len = imgResp.body().contentLength();
                if (len > 0 && len > 25L * 1024L * 1024L) return;

                boolean ok;
                try (InputStream is = imgResp.body().byteStream()) {
                    ok = saveToFile(context, is, BING_FILE_NAME, false);
                }
                if (!ok) return;

                File out = getBingFile(context);
                long written = out != null && out.exists() ? out.length() : -1;
                Log.d(TAG, "Bing wallpaper saved: bytes=" + len + " written=" + written);

                try {
                    SharedPreferences.Editor e = prefs(context).edit();
                    e.putString(KEY_SOURCE, SOURCE_BING);
                    if (date != null && !date.trim().isEmpty()) e.putString(KEY_BING_DATE, date.trim());
                    e.putString(KEY_BING_URL, url);
                    e.apply();
                } catch (Exception ignored) {
                }
            } finally {
                try { if (imgResp != null) imgResp.close(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
            try {
                Log.w(TAG, "Bing update failed: " + ignored.getClass().getSimpleName() + ": " + ignored.getMessage());
            } catch (Exception ignored2) {
            }
            // Keep existing cached wallpaper if any; otherwise caller will fall back to default.
        } finally {
            try { if (resp != null) resp.close(); } catch (Exception ignored) {}
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
