package com.mqltv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class LauncherWallpaper {
    public static final String FILE_NAME = "launcher_wallpaper.jpg";

    private LauncherWallpaper() {}

    public static File getFile(Context context) {
        if (context == null) return null;
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static boolean clear(Context context) {
        try {
            File f = getFile(context);
            return f != null && (!f.exists() || f.delete());
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean save(Context context, InputStream input) {
        if (context == null || input == null) return false;

        File out = new File(context.getFilesDir(), FILE_NAME);
        File tmp = new File(context.getFilesDir(), FILE_NAME + ".tmp");

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
            return true;
        } catch (Exception ignored) {
            try { tmp.delete(); } catch (Exception ignored2) {}
            return false;
        }
    }

    public static Bitmap tryLoad(Context context) {
        if (context == null) return null;

        // 1) Internal app files dir: /data/data/<pkg>/files/launcher_wallpaper.jpg
        try {
            File f = getFile(context);
            if (f.exists() && f.length() > 0) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    return decodeScaled(context, new BufferedInputStream(fis));
                }
            }
        } catch (Exception ignored) {
        }

        // 2) Asset fallback (optional): app/src/main/assets/launcher_wallpaper.jpg
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
