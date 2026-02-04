package com.mqltv;

import android.app.Activity;

/**
 * Enforces playback access while a player activity is running.
 *
 * Why: if admin disables/expires an account while user is already playing,
 * the user could keep watching until they leave the player. This helper
 * re-checks login + subscription status and stops playback when no longer allowed.
 */
public final class PlaybackAccessEnforcer {
    private PlaybackAccessEnforcer() {}

    /**
     * Ensures user is logged in and not expired.
     * If not allowed, starts the appropriate screen and finishes the activity.
     */
    public static boolean ensureAccessOrFinish(Activity activity, String afterLoginDest) {
        if (activity == null) return true;

        if (!AuthPrefs.isLoggedIn(activity)) {
            LoginGuard.ensureLoggedIn(activity, afterLoginDest);
            try {
                activity.finish();
            } catch (Throwable ignored) {
            }
            return false;
        }

        if (!SubscriptionGuard.ensureNotExpired(activity)) {
            try {
                activity.finish();
            } catch (Throwable ignored) {
            }
            return false;
        }

        return true;
    }

    /**
     * Refreshes status from backend (best-effort) then enforces access.
     * Always calls {@code onDone} on the main thread.
     */
    public static void refreshThenEnforce(Activity activity, String afterLoginDest, Runnable onDone) {
        if (activity == null) {
            if (onDone != null) onDone.run();
            return;
        }

        // Fast local check first.
        if (!ensureAccessOrFinish(activity, afterLoginDest)) {
            if (onDone != null) onDone.run();
            return;
        }

        AccountStatusRefresher.refresh(activity, () -> {
            ensureAccessOrFinish(activity, afterLoginDest);
            if (onDone != null) onDone.run();
        });
    }
}
