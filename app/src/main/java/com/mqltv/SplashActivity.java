package com.mqltv;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Animated Splash Screen for MQLTV (mqltv-old).
 * Displays a cinematic TV-optimized entrance animation of the MQLTV logo,
 * glowing neon ambient effects, and a modern progress indicator.
 *
 * Transitions smoothly to MainActivity.
 * Remote D-pad (OK / Back) allows instant skip.
 */
public class SplashActivity extends FragmentActivity {

    private FrameLayout rootLayout;
    private View ambientGlow;
    private View contentContainer;
    private ImageView logoView;
    private TextView subtitleView;
    private FrameLayout loadingContainer;
    private View loadingThumb;
    private TextView versionView;

    private final List<Animator> activeAnimators = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isNavigating = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        normalizeTvDensity();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        rootLayout = findViewById(R.id.splash_root);
        ambientGlow = findViewById(R.id.splash_ambient_glow);
        contentContainer = findViewById(R.id.splash_content_container);
        logoView = findViewById(R.id.splash_logo);
        subtitleView = findViewById(R.id.splash_subtitle);
        loadingContainer = findViewById(R.id.splash_loading_container);
        loadingThumb = findViewById(R.id.splash_loading_thumb);
        versionView = findViewById(R.id.splash_version);

        // Version name
        String ver = "1.0.1";
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pInfo.versionName;
        } catch (Throwable ignored) {
        }
        versionView.setText("MQLTV · v" + ver);

        // Async time sync if available
        try {
            TimeSync.syncAsync(getApplicationContext());
        } catch (Throwable ignored) {
        }

        // Start entrance animations
        startSplashAnimation();

        // Schedule auto transition after 1900ms
        mainHandler.postDelayed(this::proceedToNextScreen, 1900);
    }

    private void normalizeTvDensity() {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int sh = Math.min(dm.widthPixels, dm.heightPixels);
            if (sh > 0) {
                float targetDensity = sh / 540f;
                if (Math.abs(dm.density - targetDensity) > 0.05f) {
                    float scaleFactor = dm.scaledDensity / dm.density;
                    dm.density = targetDensity;
                    dm.scaledDensity = targetDensity * (scaleFactor > 0 ? scaleFactor : 1f);
                    dm.densityDpi = Math.round(targetDensity * 160f);

                    DisplayMetrics appDm = getApplicationContext().getResources().getDisplayMetrics();
                    appDm.density = dm.density;
                    appDm.scaledDensity = dm.scaledDensity;
                    appDm.densityDpi = dm.densityDpi;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void startSplashAnimation() {
        ambientGlow.setAlpha(0f);
        ambientGlow.setScaleX(0.7f);
        ambientGlow.setScaleY(0.7f);

        logoView.setAlpha(0f);
        logoView.setScaleX(0.82f);
        logoView.setScaleY(0.82f);
        logoView.setTranslationY(24f);

        subtitleView.setAlpha(0f);
        subtitleView.setTranslationY(14f);

        loadingContainer.setAlpha(0f);
        versionView.setAlpha(0f);

        // 1. Ambient Glow entrance
        ObjectAnimator glowFade = ObjectAnimator.ofFloat(ambientGlow, View.ALPHA, 0f, 0.65f);
        glowFade.setDuration(750);
        glowFade.setInterpolator(new DecelerateInterpolator());

        ObjectAnimator glowScaleX = ObjectAnimator.ofFloat(ambientGlow, View.SCALE_X, 0.7f, 1.0f);
        glowScaleX.setDuration(850);
        glowScaleX.setInterpolator(new DecelerateInterpolator());

        ObjectAnimator glowScaleY = ObjectAnimator.ofFloat(ambientGlow, View.SCALE_Y, 0.7f, 1.0f);
        glowScaleY.setDuration(850);
        glowScaleY.setInterpolator(new DecelerateInterpolator());

        // 2. Logo entrance
        ObjectAnimator logoFade = ObjectAnimator.ofFloat(logoView, View.ALPHA, 0f, 1f);
        logoFade.setStartDelay(100);
        logoFade.setDuration(650);
        logoFade.setInterpolator(new DecelerateInterpolator());

        ObjectAnimator logoScaleX = ObjectAnimator.ofFloat(logoView, View.SCALE_X, 0.82f, 1.0f);
        logoScaleX.setStartDelay(100);
        logoScaleX.setDuration(800);
        logoScaleX.setInterpolator(new OvershootInterpolator(0.7f));

        ObjectAnimator logoScaleY = ObjectAnimator.ofFloat(logoView, View.SCALE_Y, 0.82f, 1.0f);
        logoScaleY.setStartDelay(100);
        logoScaleY.setDuration(800);
        logoScaleY.setInterpolator(new OvershootInterpolator(0.7f));

        ObjectAnimator logoTransY = ObjectAnimator.ofFloat(logoView, View.TRANSLATION_Y, 24f, 0f);
        logoTransY.setStartDelay(100);
        logoTransY.setDuration(800);
        logoTransY.setInterpolator(new DecelerateInterpolator());

        // 3. Subtitle entrance
        ObjectAnimator subFade = ObjectAnimator.ofFloat(subtitleView, View.ALPHA, 0f, 1f);
        subFade.setStartDelay(500);
        subFade.setDuration(450);
        subFade.setInterpolator(new DecelerateInterpolator());

        ObjectAnimator subTransY = ObjectAnimator.ofFloat(subtitleView, View.TRANSLATION_Y, 14f, 0f);
        subTransY.setStartDelay(500);
        subTransY.setDuration(450);
        subTransY.setInterpolator(new DecelerateInterpolator());

        // 4. Loading bar and version entrance
        ObjectAnimator loaderFade = ObjectAnimator.ofFloat(loadingContainer, View.ALPHA, 0f, 1f);
        loaderFade.setStartDelay(600);
        loaderFade.setDuration(400);

        ObjectAnimator verFade = ObjectAnimator.ofFloat(versionView, View.ALPHA, 0f, 0.35f);
        verFade.setStartDelay(700);
        verFade.setDuration(400);

        // 5. Loading thumb sweep
        float density = getResources().getDisplayMetrics().density;
        float maxTravel = 120f * density;
        ObjectAnimator thumbSweep = ObjectAnimator.ofFloat(loadingThumb, View.TRANSLATION_X, 0f, maxTravel);
        thumbSweep.setDuration(900);
        thumbSweep.setRepeatMode(ValueAnimator.REVERSE);
        thumbSweep.setRepeatCount(ValueAnimator.INFINITE);
        thumbSweep.setInterpolator(new AccelerateDecelerateInterpolator());

        AnimatorSet entranceSet = new AnimatorSet();
        entranceSet.playTogether(
                glowFade, glowScaleX, glowScaleY,
                logoFade, logoScaleX, logoScaleY, logoTransY,
                subFade, subTransY,
                loaderFade, verFade,
                thumbSweep
        );

        entranceSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!isNavigating && !isFinishing()) {
                    startBreathingLoop();
                }
            }
        });

        activeAnimators.add(entranceSet);
        entranceSet.start();
    }

    private void startBreathingLoop() {
        ObjectAnimator glowBreath = ObjectAnimator.ofFloat(ambientGlow, View.ALPHA, 0.45f, 0.70f);
        glowBreath.setDuration(1400);
        glowBreath.setRepeatMode(ValueAnimator.REVERSE);
        glowBreath.setRepeatCount(ValueAnimator.INFINITE);
        glowBreath.setInterpolator(new AccelerateDecelerateInterpolator());

        ObjectAnimator logoBreathX = ObjectAnimator.ofFloat(logoView, View.SCALE_X, 1.0f, 1.025f);
        logoBreathX.setDuration(1400);
        logoBreathX.setRepeatMode(ValueAnimator.REVERSE);
        logoBreathX.setRepeatCount(ValueAnimator.INFINITE);
        logoBreathX.setInterpolator(new AccelerateDecelerateInterpolator());

        ObjectAnimator logoBreathY = ObjectAnimator.ofFloat(logoView, View.SCALE_Y, 1.0f, 1.025f);
        logoBreathY.setDuration(1400);
        logoBreathY.setRepeatMode(ValueAnimator.REVERSE);
        logoBreathY.setRepeatCount(ValueAnimator.INFINITE);
        logoBreathY.setInterpolator(new AccelerateDecelerateInterpolator());

        AnimatorSet loopSet = new AnimatorSet();
        loopSet.playTogether(glowBreath, logoBreathX, logoBreathY);

        activeAnimators.add(loopSet);
        loopSet.start();
    }

    private void proceedToNextScreen() {
        if (isNavigating || isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
            return;
        }
        isNavigating = true;
        mainHandler.removeCallbacksAndMessages(null);

        for (Animator animator : activeAnimators) {
            if (animator != null) {
                animator.cancel();
            }
        }
        activeAnimators.clear();

        contentContainer.animate()
                .alpha(0f)
                .scaleX(1.05f)
                .scaleY(1.05f)
                .setDuration(220)
                .start();

        ambientGlow.animate()
                .alpha(0f)
                .setDuration(180)
                .start();

        versionView.animate()
                .alpha(0f)
                .setDuration(150)
                .start();

        rootLayout.postDelayed(this::navigateTarget, 220);
    }

    private void navigateTarget() {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
            return;
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                case KeyEvent.KEYCODE_SPACE:
                    proceedToNextScreen();
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        for (Animator animator : activeAnimators) {
            if (animator != null) {
                animator.cancel();
            }
        }
        activeAnimators.clear();
    }
}
