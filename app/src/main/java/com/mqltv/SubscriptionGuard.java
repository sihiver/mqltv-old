package com.mqltv;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Client-side subscription expiry guard.
 *
 * We store expiresAt (RFC3339) from /public/login response.
 * If now > expiresAt, we show ExpiredActivity and block playback.
 */
public final class SubscriptionGuard {
    private SubscriptionGuard() {}

    public static boolean isExpired(Context context) {
        if (context == null) return false;
        String expiresAt = AuthPrefs.getExpiresAt(context);
        if (expiresAt == null) return false;
        expiresAt = expiresAt.trim();
        if (expiresAt.isEmpty()) return false;

        long expMs = parseRfc3339ToMillis(expiresAt);
        if (expMs <= 0) return false;
        return System.currentTimeMillis() > expMs;
    }

    /**
     * Returns true if user can proceed. If expired, navigates to ExpiredActivity and returns false.
     */
    public static boolean ensureNotExpired(Context context) {
        if (!isExpired(context)) return true;
        showExpired(context);
        return false;
    }

    public static void showExpired(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            Intent i = new Intent(app, ExpiredActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            app.startActivity(i);
        });
    }

    private static long parseRfc3339ToMillis(String input) {
        if (input == null) return -1;
        String s = input.trim();
        if (s.isEmpty()) return -1;

        // Normalize timezone:
        // - RFC3339 allows Z or +07:00; SimpleDateFormat with Z expects +0700.
        if (s.endsWith("Z")) {
            s = s.substring(0, s.length() - 1) + "+0000";
        } else {
            // Convert trailing "+HH:MM" / "-HH:MM" into "+HHMM" / "-HHMM"
            int len = s.length();
            if (len >= 6) {
                char sign = s.charAt(len - 6);
                if ((sign == '+' || sign == '-') && s.charAt(len - 3) == ':') {
                    s = s.substring(0, len - 3) + s.substring(len - 2);
                }
            }
        }

        // Try with milliseconds then without.
        Date d = tryParse(s, "yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        if (d == null) d = tryParse(s, "yyyy-MM-dd'T'HH:mm:ssZ");
        return d != null ? d.getTime() : -1;
    }

    private static Date tryParse(String s, String pattern) {
        try {
            SimpleDateFormat fmt = new SimpleDateFormat(pattern, Locale.US);
            fmt.setLenient(false);
            // Pattern includes offset, but enforce UTC as a base.
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            return fmt.parse(s);
        } catch (ParseException ignored) {
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
