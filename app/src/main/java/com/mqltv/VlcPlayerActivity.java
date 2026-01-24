package com.mqltv;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
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
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

public class VlcPlayerActivity extends FragmentActivity {

    private static final String TAG = "VlcPlayer";

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private VLCVideoLayout videoLayout;

    private View controls;
    private ImageButton playPause;
    private SeekBar seekBar;
    private TextView timeText;
    private TextView durationText;
    private View liveBadge;
    private ProgressBar loading;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
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

        videoLayout = findViewById(R.id.vlc_video_layout);

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

        String url = getIntent().getStringExtra(Constants.EXTRA_URL);
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, "Invalid stream URL", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        ArrayList<String> options = new ArrayList<>();
        // Increase logging to help diagnose HW vs SW decoding in logcat.
        options.add("-vvv");
        // Network buffering options; tune if needed.
        options.add("--network-caching=1500");
        options.add("--clock-jitter=0");
        options.add("--clock-synchro=0");
        int hwMode = PlaybackPrefs.getVlcHwDecoderMode(this);
        boolean useHw = hwMode != PlaybackPrefs.VLC_HW_OFF;
        boolean useTexture = PlaybackPrefs.isVlcUseTexture(this);

        int vout = PlaybackPrefs.getVlcVout(this);
        if (vout == PlaybackPrefs.VLC_VOUT_ANDROID_DISPLAY) {
            options.add("--vout=android_display");
        } else if (vout == PlaybackPrefs.VLC_VOUT_ANDROID_SURFACE) {
            options.add("--vout=android_surface");
        } else if (vout == PlaybackPrefs.VLC_VOUT_GLES2) {
            options.add("--vout=gles2");
        }

        Log.i(TAG, "Starting VLC: hwMode=" + hwMode + " useHw=" + useHw + " useTexture=" + useTexture + " vout=" + vout);
        if (hwMode == PlaybackPrefs.VLC_HW_OFF) {
            options.add("--avcodec-hw=none");
        } else {
            // AUTO/ON: prefer hardware decoding.
            options.add("--avcodec-hw=mediacodec");
        }

        libVLC = new LibVLC(this, options);
        mediaPlayer = new MediaPlayer(libVLC);
        mediaPlayer.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
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
        videoLayout.post(() -> {
            if (isFinishing() || mediaPlayer == null || libVLC == null) return;

            mediaPlayer.attachViews(videoLayout, null, false, useTexture);

            Media media = new Media(libVLC, Uri.parse(url));
            media.setHWDecoderEnabled(useHw, false);
            mediaPlayer.setMedia(media);
            media.release();

            mediaPlayer.play();
            mediaPlayer.updateVideoSurfaces();

            showControlsTemporarily();
            uiHandler.removeCallbacks(progressRunnable);
            uiHandler.post(progressRunnable);
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

        uiHandler.removeCallbacks(progressRunnable);
        uiHandler.removeCallbacks(hideControlsRunnable);

        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {
            }
            try {
                mediaPlayer.detachViews();
            } catch (Exception ignored) {
            }
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }

        if (libVLC != null) {
            try {
                libVLC.release();
            } catch (Exception ignored) {
            }
            libVLC = null;
        }
    }
}
