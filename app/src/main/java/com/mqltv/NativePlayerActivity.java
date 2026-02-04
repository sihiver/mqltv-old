package com.mqltv;

import android.app.Activity;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.IOException;

public class NativePlayerActivity extends Activity {

    private static final String TAG = "NativePlayer";

    // Legacy STBs sometimes start audio earlier than video; we mitigate by muting
    // during start/buffering and unmuting after the first video frame is rendered.
    private boolean audioMuted = false;
    private boolean firstVideoFrameRendered = false;
    private boolean isBuffering = false;

    private SurfaceView surfaceView;
    private ProgressBar loading;
    private View controls;
    private ImageButton playPause;

    private MediaPlayer mediaPlayer;
    private String url;
    private String title;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean prepared = false;
    private boolean started = false;
    private boolean didResyncSeek = false;

    private final Runnable showBufferingIfStillBuffering = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer == null) return;
            if (!isBuffering) return;
            // If we're already playing smoothly, don't re-show spinner.
            try {
                if (firstVideoFrameRendered && mediaPlayer.isPlaying()) return;
            } catch (Throwable ignored) {
            }
            showLoading(true);
        }
    };

    // Basic freeze watchdog: restart if position doesn't advance for a while.
    private long lastPositionMs = -1;
    private long lastPositionChangedAtMs = 0;
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer == null || !prepared) {
                mainHandler.postDelayed(this, 2000);
                return;
            }

            try {
                // Fallback: if video is playing, spinner should not be visible.
                if (started && firstVideoFrameRendered) {
                    try {
                        if (mediaPlayer.isPlaying()) {
                            showLoading(false);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (started && mediaPlayer.isPlaying()) {
                    long pos = mediaPlayer.getCurrentPosition();
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (lastPositionMs < 0) {
                        lastPositionMs = pos;
                        lastPositionChangedAtMs = now;
                    } else if (pos != lastPositionMs) {
                        lastPositionMs = pos;
                        lastPositionChangedAtMs = now;
                    } else {
                        // stuck
                        if (now - lastPositionChangedAtMs > 12000) {
                            Log.w(TAG, "Watchdog: playback appears stuck, restarting...");
                            restartPlayback();
                            // Give it some time before checking again.
                            mainHandler.postDelayed(this, 4000);
                            return;
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Watchdog check failed", t);
            }

            mainHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!SubscriptionGuard.ensureNotExpired(this)) {
            finish();
            return;
        }

        setContentView(R.layout.activity_native_player);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        surfaceView = findViewById(R.id.native_surface_view);
        loading = findViewById(R.id.native_loading);
        controls = findViewById(R.id.native_controls_container);
        playPause = findViewById(R.id.native_btn_play_pause);

        title = getIntent().getStringExtra(Constants.EXTRA_TITLE);
        url = getIntent().getStringExtra(Constants.EXTRA_URL);

        PresenceReporter.startPlayback(getApplicationContext(), title, url);

        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, "URL kosong", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (controls != null) {
            controls.setVisibility(View.GONE);
        }
        showLoading(true);

        if (playPause != null) {
            playPause.setOnClickListener(v -> togglePlayPause());
        }

        SurfaceHolder holder = surfaceView.getHolder();
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Log.d(TAG, "surfaceCreated");
                startPlayback(holder);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.d(TAG, "surfaceDestroyed");
                releasePlayer();
            }
        });

        mainHandler.postDelayed(watchdog, 2000);
    }

    private void startPlayback(SurfaceHolder holder) {
        if (mediaPlayer != null) return;

        prepared = false;
        started = false;
        audioMuted = false;
        firstVideoFrameRendered = false;
        isBuffering = true;
        lastPositionMs = -1;
        lastPositionChangedAtMs = 0;

        Log.i(TAG, "Starting native playback: " + title + " / " + url);

        try {
            MediaPlayer mp = new MediaPlayer();
            mediaPlayer = mp;

            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setScreenOnWhilePlaying(true);

            mp.setOnPreparedListener(player -> {
                prepared = true;
                // Keep loading visible until we actually see video rendering.
                if (loading != null) loading.setVisibility(View.VISIBLE);
                try {
                    // Start muted only on legacy devices to avoid audio leading the first frames.
                    if (DeviceQuirks.isZteB760H() || Build.VERSION.SDK_INT <= 19) {
                        setMuted(true);
                    }
                    startNow(player);
                } catch (Throwable t) {
                    Log.e(TAG, "start() failed", t);
                    Toast.makeText(NativePlayerActivity.this, "Gagal start playback", Toast.LENGTH_SHORT).show();
                    finish();
                }
            });

            mp.setOnInfoListener((player, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    isBuffering = true;
                    mainHandler.removeCallbacks(showBufferingIfStillBuffering);
                    // After video has started, only show spinner if buffering persists.
                    if (firstVideoFrameRendered) {
                        mainHandler.postDelayed(showBufferingIfStillBuffering, 500);
                    } else {
                        showLoading(true);
                    }
                    setMuted(true);
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    isBuffering = false;
                    mainHandler.removeCallbacks(showBufferingIfStillBuffering);
                    // If video has rendered, hide loading. Some devices may not send this reliably,
                    // so VIDEO_RENDERING_START also hides the spinner unconditionally.
                    if (firstVideoFrameRendered) showLoading(false);
                    // Don't unmute yet unless we already have video rendering.
                    // Some STBs report BUFFERING_END before the first frame, which causes audio lead.
                    if (firstVideoFrameRendered) {
                        mainHandler.postDelayed(() -> setMuted(false), getAudioPostBufferDelayMs());
                    }
                } else if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    firstVideoFrameRendered = true;
                    // Standard UX: hide loading when the first frame is actually being rendered.
                    // Do this unconditionally; some devices never report BUFFERING_END.
                    isBuffering = false;
                    mainHandler.removeCallbacks(showBufferingIfStillBuffering);
                    showLoading(false);
                    // ZTE B760H sometimes has persistent A/V offset (audio leads).
                    // A seek-to-current-position forces a pipeline flush and often re-aligns.
                    if (DeviceQuirks.isZteB760H() && !didResyncSeek) {
                        didResyncSeek = true;
                        forceResyncSeek(player);
                        return false;
                    }
                    // Unmute after first video frame is actually rendering.
                    mainHandler.postDelayed(() -> setMuted(false), getAudioPostVideoRenderDelayMs());
                }
                return false;
            });

            mp.setOnErrorListener((player, what, extra) -> {
                Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                Toast.makeText(NativePlayerActivity.this, "Native player error: " + what, Toast.LENGTH_SHORT).show();
                finish();
                return true;
            });

            mp.setOnCompletionListener(player -> {
                Log.i(TAG, "Playback completed");
                finish();
            });

            mp.setDisplay(holder);

            Uri uri = Uri.parse(url);
            // Use setDataSource(Context, Uri) for better compatibility.
            mp.setDataSource(getApplicationContext(), uri);

            // For some legacy devices, preparing async is safer.
            mp.prepareAsync();

        } catch (IOException e) {
            Log.e(TAG, "Failed to start playback", e);
            Toast.makeText(this, "Gagal buka stream", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start playback (unexpected)", t);
            Toast.makeText(this, "Native player crash", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setMuted(boolean muted) {
        MediaPlayer mp = mediaPlayer;
        if (mp == null) return;
        if (audioMuted == muted) return;
        audioMuted = muted;
        try {
            float vol = muted ? 0f : 1f;
            mp.setVolume(vol, vol);
        } catch (Throwable t) {
            Log.w(TAG, "setVolume failed", t);
        }
    }

    private void showLoading(boolean show) {
        if (loading == null) return;
        loading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void startNow(MediaPlayer player) {
        if (player == null) return;
        // Keep loading visible until VIDEO_RENDERING_START.
        player.start();
        started = true;
        updatePlayPauseUi();

        // Fallback: if video is already playing but the device never sends VIDEO_RENDERING_START,
        // don't keep the spinner forever.
        mainHandler.postDelayed(() -> {
            if (mediaPlayer != player) return;
            if (loading != null && loading.getVisibility() == View.VISIBLE) {
                try {
                    if (player.isPlaying()) {
                        isBuffering = false;
                        loading.setVisibility(View.GONE);
                    }
                } catch (Throwable ignored) {
                }
            }
        }, 2500);

        // Fallback: if we don't get VIDEO_RENDERING_START, unmute after a delay.
        mainHandler.postDelayed(() -> {
            if (mediaPlayer == null) return;
            if (!firstVideoFrameRendered) {
                Log.w(TAG, "No VIDEO_RENDERING_START; unmuting via fallback delay");
                setMuted(false);
            }
        }, getAudioUnmuteFallbackDelayMs());
    }

    private void forceResyncSeek(MediaPlayer player) {
        if (player == null) return;
        if (mediaPlayer != player) return;

        try {
            setMuted(true);
            isBuffering = true;
            showLoading(true);
            final int pos = Math.max(0, player.getCurrentPosition());
            Log.w(TAG, "Resync: seekTo currentPosition=" + pos);

            final Runnable unmuteFallback = () -> {
                if (mediaPlayer != player) return;
                mainHandler.postDelayed(() -> {
                    setMuted(false);
                    isBuffering = false;
                    if (firstVideoFrameRendered) showLoading(false);
                }, 120);
            };

            player.setOnSeekCompleteListener(mp -> {
                // Remove listener to avoid repeated callbacks.
                try {
                    mp.setOnSeekCompleteListener(null);
                } catch (Throwable ignored) {
                }
                unmuteFallback.run();
            });

            player.seekTo(pos);
            // If seek complete never arrives, unmute anyway.
            mainHandler.postDelayed(unmuteFallback, 700);
        } catch (Throwable t) {
            Log.w(TAG, "Resync seek failed", t);
            mainHandler.postDelayed(() -> setMuted(false), 200);
        }
    }

    private long getAudioUnmuteFallbackDelayMs() {
        // Keep this reasonably low; too high makes audio feel "late".
        if (DeviceQuirks.isZteB760H()) return 800;
        if (Build.VERSION.SDK_INT <= 19) return 600;
        return 400;
    }

    private long getAudioPostBufferDelayMs() {
        // Unmute quickly after buffering; extra delay tends to create noticeable lag.
        if (DeviceQuirks.isZteB760H()) return 0;
        if (Build.VERSION.SDK_INT <= 19) return 0;
        return 0;
    }

    private long getAudioPostVideoRenderDelayMs() {
        // Small delay can help if audio still slightly leads, but keep it modest.
        if (DeviceQuirks.isZteB760H()) return 120;
        if (Build.VERSION.SDK_INT <= 19) return 80;
        return 50;
    }

    private void restartPlayback() {
        SurfaceHolder holder = surfaceView != null ? surfaceView.getHolder() : null;
        if (holder == null) return;

        if (loading != null) loading.setVisibility(View.VISIBLE);
        releasePlayer();
        mainHandler.postDelayed(() -> startPlayback(holder), 400);
    }

    private void releasePlayer() {
        MediaPlayer mp = mediaPlayer;
        mediaPlayer = null;
        prepared = false;
        started = false;

        if (mp != null) {
            try {
                mp.reset();
            } catch (Throwable ignored) {
            }
            try {
                mp.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void togglePlayPause() {
        if (mediaPlayer == null || !prepared) return;
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            } else {
                mediaPlayer.start();
                started = true;
            }
            updatePlayPauseUi();
        } catch (Throwable t) {
            Log.w(TAG, "togglePlayPause failed", t);
        }
    }

    private void updatePlayPauseUi() {
        if (playPause == null || mediaPlayer == null || !prepared) return;
        try {
            boolean playing = mediaPlayer.isPlaying();
            playPause.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        } catch (Throwable ignored) {
        }
    }

    private void toggleControls() {
        if (controls == null) return;
        if (controls.getVisibility() == View.VISIBLE) {
            controls.setVisibility(View.GONE);
        } else {
            controls.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            toggleControls();
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_SPACE) {
            togglePlayPause();
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop playback when leaving.
        releasePlayer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            PresenceReporter.stopPlayback(getApplicationContext());
        }
        mainHandler.removeCallbacks(watchdog);
        releasePlayer();
    }
}
