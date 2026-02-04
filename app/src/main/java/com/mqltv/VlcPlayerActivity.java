package com.mqltv;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.SurfaceView;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;

public class VlcPlayerActivity extends FragmentActivity {

    private static final String TAG = "VlcPlayer";

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private SurfaceView surfaceView;
    private TextureView textureView;
    private IVLCVout vlcVout;
    private FrameLayout videoContainer;

    private View controls;
    private ImageButton playPause;
    private SeekBar seekBar;
    private TextView timeText;
    private TextView durationText;
    private View liveBadge;
    private ProgressBar loading;
    private boolean released = false;

    private final IVLCVout.Callback vlcVoutCallback = new IVLCVout.Callback() {
        @Override
        public void onSurfacesCreated(IVLCVout vout) {
            Log.i(TAG, "VLC surfaces created");
        }

        @Override
        public void onSurfacesDestroyed(IVLCVout vout) {
            Log.i(TAG, "VLC surfaces destroyed");
        }
    };

    private final IVLCVout.OnNewVideoLayoutListener voutLayoutListener = new IVLCVout.OnNewVideoLayoutListener() {
        @Override
        public void onNewVideoLayout(IVLCVout vout, int width, int height, int visibleWidth, int visibleHeight, int sarNum, int sarDen) {
            if (width * height == 0 || videoContainer == null) return;
            if (sarNum == 0 || sarDen == 0) {
                sarNum = 1;
                sarDen = 1;
            }

            final int containerW = videoContainer.getWidth();
            final int containerH = videoContainer.getHeight();
            if (containerW == 0 || containerH == 0) return;

            final int visibleW = visibleWidth * sarNum / sarDen;
            final float videoAR = (float) visibleW / (float) visibleHeight;
            final float containerAR = (float) containerW / (float) containerH;

            int displayW = containerW;
            int displayH = containerH;
            if (containerAR < videoAR) {
                displayH = (int) (containerW / videoAR);
            } else {
                displayW = (int) (containerH * videoAR);
            }

            final int finalW = displayW;
            final int finalH = displayH;
            runOnUiThread(() -> {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(finalW, finalH);
                lp.leftMargin = (containerW - finalW) / 2;
                lp.topMargin = (containerH - finalH) / 2;
                if (surfaceView != null && surfaceView.getVisibility() == View.VISIBLE) {
                    surfaceView.setLayoutParams(lp);
                }
                if (textureView != null && textureView.getVisibility() == View.VISIBLE) {
                    textureView.setLayoutParams(lp);
                }
            });
        }
    };

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean accessCheckInFlight = false;
    private final Runnable accessTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            if (accessCheckInFlight) {
                uiHandler.postDelayed(this, 3000);
                return;
            }
            accessCheckInFlight = true;
            PlaybackAccessEnforcer.refreshThenEnforce(VlcPlayerActivity.this, LoginActivity.DEST_LIVE_TV, () -> {
                accessCheckInFlight = false;
                if (!isFinishing()) uiHandler.postDelayed(accessTick, 30_000);
            });
        }
    };
    private final Runnable hideControlsRunnable = () -> {
        if (controls != null) controls.setVisibility(View.GONE);
    };
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            updateUiFromPlayer();
            uiHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vlc_player);

        surfaceView = findViewById(R.id.vlc_surface_view);
        textureView = findViewById(R.id.vlc_texture_view);
        videoContainer = findViewById(R.id.vlc_video_container);

        loading = findViewById(R.id.vlc_loading);
        controls = findViewById(R.id.vlc_controls_container);
        playPause = findViewById(R.id.vlc_btn_play_pause);
        seekBar = findViewById(R.id.vlc_seekbar);
        timeText = findViewById(R.id.vlc_time);
        durationText = findViewById(R.id.vlc_duration);
        liveBadge = findViewById(R.id.vlc_live_badge);

        if (playPause != null) {
            playPause.setOnClickListener(v -> {
                togglePlayPause();
                showControlsTemporarily();
            });
        }

        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    if (mediaPlayer == null) return;
                    long length = mediaPlayer.getLength();
                    if (length <= 0) return;
                    long newTime = (length * progress) / 1000L;
                    mediaPlayer.setTime(newTime);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    showControlsTemporarily();
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    showControlsTemporarily();
                }
            });
        }

        String title = getIntent().getStringExtra(Constants.EXTRA_TITLE);
        if (title != null) {
            setTitle(title);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (!PlaybackAccessEnforcer.ensureAccessOrFinish(this, LoginActivity.DEST_LIVE_TV)) return;

        String title = getIntent().getStringExtra(Constants.EXTRA_TITLE);
        String url = getIntent().getStringExtra(Constants.EXTRA_URL);
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, "Invalid stream URL", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        PresenceReporter.startPlayback(getApplicationContext(), title, url);

        ArrayList<String> options = new ArrayList<>();
        // Keep logging lightweight on legacy STBs.
        boolean legacySdk = android.os.Build.VERSION.SDK_INT <= 19;
        options.add(legacySdk ? "-vv" : "-vvv");

        // Network buffering options; read from preferences.
        int cachingMs = PlaybackPrefs.getVlcNetworkCaching(this);
        // Safety floor for legacy devices to prevent "clock started too soon" lateness.
        if (legacySdk && cachingMs < 3000) cachingMs = 5000;
        final int cachingMsFinal = cachingMs;
        options.add("--network-caching=" + cachingMsFinal);
        options.add("--live-caching=" + cachingMsFinal);
        options.add("--file-caching=" + cachingMsFinal);

        // On slow STBs (incl. Android 4.4), letting VLC drop/skip late video frames is
        // often required to prevent the video from falling behind and appearing stuck.
        options.add("--drop-late-frames");
        options.add("--skip-frames");
        // Tuning for old CPUs: fewer threads + lighter decode.
        int cores = 1;
        try { cores = Math.max(1, Runtime.getRuntime().availableProcessors()); } catch (Exception ignored) {}
        int avThreads = legacySdk ? Math.min(2, cores) : Math.min(4, cores);
        final int avThreadsFinal = avThreads;
        options.add("--avcodec-threads=" + avThreadsFinal);
        options.add(legacySdk ? "--avcodec-skiploopfilter=nonref" : "--avcodec-skiploopfilter=all");
        if (legacySdk) {
            // Reduce CPU load when HW decode falls back to SW on legacy devices.
            options.add("--avcodec-fast");
            options.add("--avcodec-skip-frame=nonref");
            options.add("--avcodec-skip-idct=nonref");
        }
        int hwMode = PlaybackPrefs.getVlcHwDecoderMode(this);
        if (DeviceQuirks.isHuaweiEc6108v9() && hwMode == PlaybackPrefs.VLC_HW_PLUS) {
            Log.w(TAG, "EC6108V9: disabling VLC HW+; using HW ON");
            hwMode = PlaybackPrefs.VLC_HW_ON;
        }
        final int hwModeFinal = hwMode;
        boolean useHw = hwMode != PlaybackPrefs.VLC_HW_OFF;
        boolean useTexture = PlaybackPrefs.isVlcUseTexture(this);
        boolean deinterlace = PlaybackPrefs.isVlcDeinterlaceEnabled(this);
        int hwImpl = PlaybackPrefs.getVlcHwDecoderImpl(this);
        boolean forceHwOnly = PlaybackPrefs.isVlcHwForceOnly(this);

        int vout = PlaybackPrefs.getVlcVout(this);
        // Force android_display on this device; gles2 crashes with EGL config error.
        String voutName = "android_display";
        if (vout == PlaybackPrefs.VLC_VOUT_GLES2) {
            Log.w(TAG, "VLC vout gles2 causes EGL crash; forcing android_display");
        } else if (vout == PlaybackPrefs.VLC_VOUT_ANDROID_SURFACE) {
            Log.w(TAG, "VLC vout android_surface not available; forcing android_display");
        }
        options.add("--vout=" + voutName);
        options.add("--android-display-chroma=RV16");

        if (deinterlace) {
            options.add("--deinterlace=1");
            options.add("--deinterlace-mode=yadif");
        } else {
            options.add("--deinterlace=0");
        }

        final String hwImplName = !useHw ? null
            : (hwImpl == PlaybackPrefs.VLC_HW_IMPL_MEDIACODEC_NDK ? "mediacodec_ndk"
            : (android.os.Build.VERSION.SDK_INT >= 21 ? "mediacodec_ndk" : "mediacodec_jni"));
        final String hwCodecList = (hwImplName != null) ? hwImplName : (android.os.Build.VERSION.SDK_INT >= 21 ? "mediacodec_ndk" : "mediacodec_jni");

        Log.i(TAG, "Starting VLC: hwMode=" + hwMode + " useHw=" + useHw + " useTexture=" + useTexture + " vout=" + vout + " hwImpl=" + hwImplName + " forceHwOnly=" + forceHwOnly + " cachingMs=" + cachingMsFinal + " avThreads=" + avThreadsFinal);

        libVLC = new LibVLC(this, options);
        mediaPlayer = new MediaPlayer(libVLC);
        // Video scale is handled by SurfaceView/TextureView layout.
        mediaPlayer.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event event) {
                if (event.type == MediaPlayer.Event.Buffering) {
                    if (loading != null) {
                        loading.setVisibility(event.getBuffering() < 100f ? View.VISIBLE : View.GONE);
                    }
                }
                if (event.type == MediaPlayer.Event.EncounteredError) {
                    Toast.makeText(VlcPlayerActivity.this, "VLC playback error", Toast.LENGTH_LONG).show();
                }

                if (event.type == MediaPlayer.Event.Playing
                        || event.type == MediaPlayer.Event.Paused
                        || event.type == MediaPlayer.Event.Stopped
                        || event.type == MediaPlayer.Event.TimeChanged
                        || event.type == MediaPlayer.Event.LengthChanged) {
                    updateUiFromPlayer();
                }
            }
        });

        // Important for some STBs: attach views only after the layout is attached/measured.
        final int[] attachTries = new int[] {0};
        surfaceView.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || mediaPlayer == null || libVLC == null) return;

                View videoView = useTexture ? textureView : surfaceView;
                int vw = videoView != null ? videoView.getWidth() : 0;
                int vh = videoView != null ? videoView.getHeight() : 0;
                if ((vw <= 0 || vh <= 0) && attachTries[0] < 30) {
                    attachTries[0]++;
                    if (surfaceView != null) surfaceView.postDelayed(this, 50);
                    return;
                }

            vlcVout = mediaPlayer.getVLCVout();
            if (useTexture) {
                if (textureView != null) textureView.setVisibility(View.VISIBLE);
                if (surfaceView != null) surfaceView.setVisibility(View.GONE);
                if (textureView != null) {
                    vlcVout.setVideoView(textureView);
                    vlcVout.setSubtitlesView(textureView);
                }
            } else {
                if (textureView != null) textureView.setVisibility(View.GONE);
                if (surfaceView != null) surfaceView.setVisibility(View.VISIBLE);
                if (surfaceView != null) {
                    vlcVout.setVideoView(surfaceView);
                    vlcVout.setSubtitlesView(surfaceView);
                }
            }
            vlcVout.addCallback(vlcVoutCallback);
            if (videoView != null) {
                vlcVout.setWindowSize(videoView.getWidth(), videoView.getHeight());
            }
            vlcVout.attachViews(voutLayoutListener);

            Media media = new Media(libVLC, Uri.parse(url));
            media.addOption(":network-caching=" + cachingMsFinal);
            media.addOption(":live-caching=" + cachingMsFinal);
            media.addOption(":file-caching=" + cachingMsFinal);
            media.addOption(":drop-late-frames");
            media.addOption(":skip-frames");
            media.addOption(":avcodec-threads=" + avThreadsFinal);
            media.addOption(legacySdk ? ":avcodec-skiploopfilter=nonref" : ":avcodec-skiploopfilter=all");
            if (legacySdk) {
                media.addOption(":avcodec-fast");
                media.addOption(":avcodec-skip-frame=nonref");
                media.addOption(":avcodec-skip-idct=nonref");
            }
            media.addOption(":android-display-chroma=RV16");
            if (deinterlace) {
                media.addOption(":deinterlace=1");
                media.addOption(":deinterlace-mode=yadif");
            } else {
                media.addOption(":deinterlace=0");
            }
            boolean forceHw = hwModeFinal == PlaybackPrefs.VLC_HW_PLUS;
            media.setHWDecoderEnabled(useHw, forceHw);
            if (forceHwOnly && useHw) {
                media.addOption(":codec=" + hwCodecList);
            }
            mediaPlayer.setMedia(media);
            media.release();

            mediaPlayer.play();
            // No updateVideoSurfaces() in older LibVLC API.

            showControlsTemporarily();
            uiHandler.removeCallbacks(progressRunnable);
            uiHandler.post(progressRunnable);
            }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // For TV: show controls on any D-PAD/OK interaction.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                togglePlayPause();
                showControlsTemporarily();
                return true;
            }

            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                showControlsTemporarily();

                // Optional quick seek when the stream is seekable.
                if ((key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) && mediaPlayer != null) {
                    long length = mediaPlayer.getLength();
                    if (length > 0) {
                        long cur = mediaPlayer.getTime();
                        long delta = (key == KeyEvent.KEYCODE_DPAD_LEFT) ? -10_000L : 10_000L;
                        long target = Math.max(0, Math.min(length, cur + delta));
                        mediaPlayer.setTime(target);
                        return true;
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        } else {
            mediaPlayer.play();
        }
        updateUiFromPlayer();
    }

    private void showControlsTemporarily() {
        if (controls == null) return;
        controls.setVisibility(View.VISIBLE);
        uiHandler.removeCallbacks(hideControlsRunnable);
        uiHandler.postDelayed(hideControlsRunnable, 3000);
    }

    private void updateUiFromPlayer() {
        if (mediaPlayer == null) return;
        long time = mediaPlayer.getTime();
        long length = mediaPlayer.getLength();

        if (playPause != null) {
            playPause.setImageResource(mediaPlayer.isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        }

        if (timeText != null) timeText.setText(formatTime(time));
        if (durationText != null) {
            durationText.setText(length > 0 ? formatTime(length) : "LIVE");
        }

        if (liveBadge != null) {
            liveBadge.setVisibility(length > 0 ? View.GONE : View.VISIBLE);
        }

        if (seekBar != null) {
            if (length > 0) {
                seekBar.setEnabled(true);
                int progress = (int) ((time * 1000L) / Math.max(1L, length));
                seekBar.setProgress(progress);
            } else {
                seekBar.setEnabled(false);
                seekBar.setProgress(0);
            }
        }
    }

    private static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000L;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;
        if (hours > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }

    @Override
    protected void onStop() {
        super.onStop();
        uiHandler.removeCallbacks(accessTick);
        if (isFinishing()) {
            PresenceReporter.stopPlayback(getApplicationContext());
        }
        releasePlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!PlaybackAccessEnforcer.ensureAccessOrFinish(this, LoginActivity.DEST_LIVE_TV)) return;
        uiHandler.removeCallbacks(accessTick);
        uiHandler.post(accessTick);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(accessTick);
        super.onPause();
        releasePlayer();
    }

    private void releasePlayer() {
        if (released) return;
        released = true;

        uiHandler.removeCallbacks(progressRunnable);
        uiHandler.removeCallbacks(hideControlsRunnable);

        if (mediaPlayer != null) {
            try { mediaPlayer.setEventListener(null); } catch (Exception ignored) {}
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            try {
                if (vlcVout != null) {
                    vlcVout.removeCallback(vlcVoutCallback);
                    vlcVout.detachViews();
                }
            } catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }

        if (libVLC != null) {
            try { libVLC.release(); } catch (Exception ignored) {}
            libVLC = null;
        }
    }
}
