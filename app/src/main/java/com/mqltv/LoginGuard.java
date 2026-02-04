package com.mqltv;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * Ensures user is logged in before protected actions.
 */
public final class LoginGuard {
    private LoginGuard() {}

    public static boolean ensureLoggedIn(Context context) {
        return ensureLoggedIn(context, null);
    }

    /**
     * @param afterLoginDest optional destination (see LoginActivity.DEST_*)
     */
    public static boolean ensureLoggedIn(Context context, String afterLoginDest) {
        if (context == null) return true;
        if (AuthPrefs.isLoggedIn(context)) return true;

        final Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            Intent i = new Intent(app, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (afterLoginDest != null && !afterLoginDest.trim().isEmpty()) {
                i.putExtra(LoginActivity.EXTRA_AFTER_LOGIN_DEST, afterLoginDest.trim());
            }
            app.startActivity(i);
        });
        return false;
    }
}
