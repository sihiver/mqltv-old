package com.mqltv;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
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
import android.content.Intent;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;

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

    private PlayerChannelOverlayController channelOverlay;

    private AlertDialog idleDialog;
    private CountDownTimer idleCountDownTimer;

    private final Handler idleHandler = new Handler(Looper.getMainLooper());
    private final Runnable idleRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            showIdleConfirmationDialog();
        }
    };

    private void showIdleConfirmationDialog() {
        if (isFinishing()) return;
        dismissIdleConfirmationDialog();

        int val = PlaybackPrefs.getIdleTimeoutHours(this);
        String timeStr = (val == PlaybackPrefs.IDLE_TIMEOUT_1_MIN) ? "1 menit" : val + " jam";

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Apakah Anda Masih Nonton?");
        builder.setMessage("Tidak ada interaksi selama " + timeStr + ".\nSiaran akan ditutup dalam 30 detik.");

        builder.setPositiveButton("Lanjut Nonton", (dialog, which) -> {
            dismissIdleConfirmationDialog();
            resetIdleTimer();
        });

        builder.setNegativeButton("Keluar", (dialog, which) -> {
            dismissIdleConfirmationDialog();
            finish();
        });

        builder.setOnCancelListener(dialog -> {
            dismissIdleConfirmationDialog();
            resetIdleTimer();
        });

        idleDialog = builder.create();
        idleDialog.show();

        idleCountDownTimer = new CountDownTimer(30_000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (idleDialog != null && idleDialog.isShowing()) {
                    long seconds = (millisUntilFinished / 1000) + 1;
                    idleDialog.setMessage("Tidak ada interaksi selama " + timeStr + ".\nSiaran akan ditutup dalam " + seconds + " detik.");
                }
            }

            @Override
            public void onFinish() {
                dismissIdleConfirmationDialog();
                finish();
            }
        }.start();
    }

    private void dismissIdleConfirmationDialog() {
        if (idleCountDownTimer != null) {
            idleCountDownTimer.cancel();
            idleCountDownTimer = null;
        }
        if (idleDialog != null) {
            if (idleDialog.isShowing()) {
                try { idleDialog.dismiss(); } catch (Exception ignored) {}
            }
            idleDialog = null;
        }
    }

    private void resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable);
        long timeoutMs = PlaybackPrefs.getIdleTimeoutMillis(this);
        if (timeoutMs > 0) {
            idleHandler.postDelayed(idleRunnable, timeoutMs);
        }
    }

    private void stopIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable);
        dismissIdleConfirmationDialog();
    }

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
            int sn = sarNum;
            int sd = sarDen;
            if (sn == 0 || sd == 0) {
                sn = 1;
                sd = 1;
            }

            final int containerW = videoContainer.getWidth();
            final int containerH = videoContainer.getHeight();
            if (containerW == 0 || containerH == 0) return;

            final int fVisibleW = visibleWidth;
            final int fVisibleH = visibleHeight;
            final int fSarNum = sn;
            final int fSarDen = sd;
            runOnUiThread(() -> {
                View videoView = textureView != null && textureView.getVisibility() == View.VISIBLE
                        ? textureView : surfaceView;
                VideoDisplayHelper.applySurfaceLayout(
                        videoContainer, videoView, VlcPlayerActivity.this,
                        fVisibleW, fVisibleH, fSarNum, fSarDen);
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
        overridePendingTransition(0, 0);
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

        channelOverlay = new PlayerChannelOverlayController(this, this::switchChannelInPlace);
    }

    @Override
    public void onBackPressed() {
        if (channelOverlay != null && channelOverlay.isVisible()) {
            channelOverlay.hide();
            return;
        }
        super.onBackPressed();
    }

    private int vlcRetryCount = 0;
    private static final int MAX_VLC_RETRIES = 3;
    private boolean isZapping = false;
    private long zapSequence = 0;
    @Nullable private volatile Call activeZapCall = null;
    private static final ExecutorService ZAP_EXECUTOR = Executors.newSingleThreadExecutor();

    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = focusChange -> {
        if (mediaPlayer == null) return;
        try {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                mediaPlayer.setVolume(30);
            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                if (!isZapping) mediaPlayer.setVolume(100);
            }
        } catch (Exception ignored) {}
    };

    private void requestAudioFocus() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                am.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
        } catch (Exception ignored) {}
    }

    private void abandonAudioFocus() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                am.abandonAudioFocus(audioFocusListener);
            }
        } catch (Exception ignored) {}
    }

    private final Runnable vlcRetryRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || mediaPlayer == null || libVLC == null) return;
            String currentUrl = getIntent().getStringExtra(Constants.EXTRA_URL);
            if (currentUrl == null || currentUrl.isEmpty()) return;
            ChannelPlaybackMeta meta = ChannelPlaybackMeta.fromIntent(getIntent());
            try {
                mediaPlayer.stop();
                Media media = new Media(libVLC, Uri.parse(currentUrl));
                configureVlcMedia(media, meta);
                mediaPlayer.setMedia(media);
                media.release();
                mediaPlayer.play();
            } catch (Exception e) {
                Log.e(TAG, "VLC retry failed", e);
            }
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        released = false;

        if (!PlaybackAccessEnforcer.ensureAccessOrFinish(this, LoginActivity.DEST_LIVE_TV)) return;

        String title = getIntent().getStringExtra(Constants.EXTRA_TITLE);
        String url = getIntent().getStringExtra(Constants.EXTRA_URL);
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, "Invalid stream URL", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        requestAudioFocus();
        PresenceReporter.startPlayback(getApplicationContext(), title, url);

        int currentId = getIntent().getIntExtra(Constants.EXTRA_CHANNEL_ID, 0);
        if (channelOverlay != null) {
            channelOverlay.setCurrentChannelId(currentId);
            channelOverlay.showQuickInfoBannerForChannelId(currentId);
        }

        ArrayList<String> options = new ArrayList<>();
        // Avoid -vvv JNI log flooding which causes severe CPU frame drops on TV STBs.
        boolean legacySdk = android.os.Build.VERSION.SDK_INT <= 19;
        boolean isDebuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        options.add(isDebuggable ? "-v" : "--quiet");

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
        final int avThreadsFinal = legacySdk ? Math.min(2, cores) : Math.min(4, cores);
        options.add("--avcodec-threads=" + avThreadsFinal);
        options.add(legacySdk ? "--avcodec-skiploopfilter=nonref" : "--avcodec-skiploopfilter=all");
        if (legacySdk) {
            // Reduce CPU load when HW decode falls back to SW on legacy devices.
            options.add("--avcodec-fast");
            options.add("--avcodec-skip-frame=nonref");
            options.add("--avcodec-skip-idct=nonref");
            // Relax clock jitter so VLC doesn't drop frames aggressively on slow CPUs.
            options.add("--clock-jitter=0");
            options.add("--clock-synchro=0");
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
        // Always use 32-bit chroma (RV32) to prevent 16-bit color banding on modern TV displays
        // and prevent frozen video on legacy SoCs.
        options.add("--android-display-chroma=RV32");

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
        mediaPlayer.setEventListener(event -> {
            if (event.type == MediaPlayer.Event.Buffering) {
                // TV-satellite style: never show center loading spinner
                if (loading != null) loading.setVisibility(View.GONE);
            }
            if (event.type == MediaPlayer.Event.EncounteredError) {
                if (loading != null) loading.setVisibility(View.GONE);
                if (vlcRetryCount < MAX_VLC_RETRIES) {
                    vlcRetryCount++;
                    long delayMs = vlcRetryCount == 1 ? 1500L : (vlcRetryCount == 2 ? 3000L : 5000L);
                    Log.w(TAG, "VLC EncounteredError: auto-retry " + vlcRetryCount + "/" + MAX_VLC_RETRIES + " in " + delayMs + "ms");
                    uiHandler.removeCallbacks(vlcRetryRunnable);
                    uiHandler.postDelayed(vlcRetryRunnable, delayMs);
                    return;
                }
                isZapping = false;
                if (mediaPlayer != null) {
                    try { mediaPlayer.setVolume(100); } catch (Exception ignored) {}
                }
                Toast.makeText(VlcPlayerActivity.this, "VLC playback error", Toast.LENGTH_LONG).show();
            }

            if (event.type == MediaPlayer.Event.TimeChanged) {
                // TimeChanged means frames are actively rendering!
                isZapping = false;
                vlcRetryCount = 0;
                uiHandler.removeCallbacks(vlcRetryRunnable);
                if (loading != null) loading.setVisibility(View.GONE);
                if (mediaPlayer != null) {
                    try { mediaPlayer.setVolume(100); } catch (Exception ignored) {}
                }
                updateUiFromPlayer();
            } else if (event.type == MediaPlayer.Event.Playing
                    || event.type == MediaPlayer.Event.Paused
                    || event.type == MediaPlayer.Event.Stopped
                    || event.type == MediaPlayer.Event.LengthChanged) {
                if (loading != null) loading.setVisibility(View.GONE);
                updateUiFromPlayer();
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
            ChannelPlaybackMeta meta = ChannelPlaybackMeta.fromIntent(getIntent());
            configureVlcMedia(media, meta);
            mediaPlayer.setMedia(media);
            media.release();

            mediaPlayer.play();
            // No updateVideoSurfaces() in older LibVLC API.

            uiHandler.removeCallbacks(progressRunnable);
            uiHandler.post(progressRunnable);
            }
        });
    }

    @SuppressLint("RestrictedApi")
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (channelOverlay != null && channelOverlay.handleKeyEvent(event)) {
            if (channelOverlay.isVisible() && controls != null) controls.setVisibility(View.GONE);
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_DPAD_UP) {
                if (channelOverlay != null) {
                    channelOverlay.zapNextChannel();
                    return true;
                }
            } else if (key == KeyEvent.KEYCODE_CHANNEL_DOWN || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (channelOverlay != null) {
                    channelOverlay.zapPrevChannel();
                    return true;
                }
            } else if (key == KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK
                    || key == KeyEvent.KEYCODE_MENU
                    || key == KeyEvent.KEYCODE_PROG_RED) {
                toggleAudioTrack();
                return true;
            } else if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // Quick seek only when the stream is seekable (VOD)
                if (mediaPlayer != null) {
                    long length = mediaPlayer.getLength();
                    if (length > 0) {
                        showControlsTemporarily();
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
                int progress = (int) ((time * 1000L) / length);
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
    protected void onDestroy() {
        if (channelOverlay != null) {
            channelOverlay.destroy();
            channelOverlay = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resetIdleTimer();
        if (!PlaybackAccessEnforcer.ensureAccessOrFinish(this, LoginActivity.DEST_LIVE_TV)) return;
        uiHandler.removeCallbacks(accessTick);
        uiHandler.post(accessTick);
    }

    @Override
    protected void onPause() {
        stopIdleTimer();
        uiHandler.removeCallbacks(accessTick);
        super.onPause();
        releasePlayer();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (idleDialog != null && idleDialog.isShowing()) {
            dismissIdleConfirmationDialog();
        }
        resetIdleTimer();
    }

    private void toggleAudioTrack() {
        if (mediaPlayer == null) return;
        MediaPlayer.TrackDescription[] tracks = mediaPlayer.getAudioTracks();
        if (tracks == null || tracks.length <= 1) {
            Toast.makeText(this, "Hanya 1 track audio tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        int cur = mediaPlayer.getAudioTrack();
        int nextIdx = 0;
        for (int i = 0; i < tracks.length; i++) {
            if (tracks[i].id == cur) {
                nextIdx = (i + 1) % tracks.length;
                break;
            }
        }
        mediaPlayer.setAudioTrack(tracks[nextIdx].id);
        Toast.makeText(this, "Audio: " + tracks[nextIdx].name, Toast.LENGTH_SHORT).show();
    }

    private void configureVlcMedia(Media media, @Nullable ChannelPlaybackMeta meta) {
        if (media == null) return;
        boolean legacySdk = android.os.Build.VERSION.SDK_INT <= 19;
        int cachingMs = PlaybackPrefs.getVlcNetworkCaching(this);
        if (legacySdk && cachingMs < 3000) cachingMs = 5000;
        final int cachingMsFinal = cachingMs;

        int cores = 1;
        try { cores = Math.max(1, Runtime.getRuntime().availableProcessors()); } catch (Exception ignored) {}
        final int avThreadsFinal = legacySdk ? Math.min(2, cores) : Math.min(4, cores);

        int hwMode = PlaybackPrefs.getVlcHwDecoderMode(this);
        if (DeviceQuirks.isHuaweiEc6108v9() && hwMode == PlaybackPrefs.VLC_HW_PLUS) {
            hwMode = PlaybackPrefs.VLC_HW_ON;
        }
        final int hwModeFinal = hwMode;
        boolean useHw = hwMode != PlaybackPrefs.VLC_HW_OFF;
        boolean deinterlace = PlaybackPrefs.isVlcDeinterlaceEnabled(this);
        int hwImpl = PlaybackPrefs.getVlcHwDecoderImpl(this);
        boolean forceHwOnly = PlaybackPrefs.isVlcHwForceOnly(this);

        final String hwImplName = !useHw ? null
            : (hwImpl == PlaybackPrefs.VLC_HW_IMPL_MEDIACODEC_NDK ? "mediacodec_ndk"
            : (android.os.Build.VERSION.SDK_INT >= 21 ? "mediacodec_ndk" : "mediacodec_jni"));
        final String hwCodecList = (hwImplName != null) ? hwImplName : (android.os.Build.VERSION.SDK_INT >= 21 ? "mediacodec_ndk" : "mediacodec_jni");

        VisionPlusPlayback.applyVlcMediaHeaders(media, meta);
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
            media.addOption(":clock-jitter=0");
        }
        media.addOption(":android-display-chroma=RV32");
        if (deinterlace) {
            media.addOption(":deinterlace=1");
            media.addOption(":deinterlace-mode=yadif");
        } else {
            media.addOption(":deinterlace=0");
        }
        if (legacySdk) {
            media.setHWDecoderEnabled(true, false);
        } else {
            boolean forceHw = hwModeFinal == PlaybackPrefs.VLC_HW_PLUS;
            media.setHWDecoderEnabled(useHw, forceHw);
            if (forceHwOnly && useHw) {
                media.addOption(":codec=" + hwCodecList);
            }
        }
    }

    public void switchChannelInPlace(@Nullable Channel channel) {
        if (channel == null || isFinishing()) return;

        int currentId = getIntent().getIntExtra(Constants.EXTRA_CHANNEL_ID, 0);
        if (channel.getId() == currentId) return;

        if (!LoginGuard.ensureLoggedIn(this, LoginActivity.DEST_LIVE_TV)) return;
        if (!SubscriptionGuard.ensureNotExpired(this)) return;

        vlcRetryCount = 0;
        uiHandler.removeCallbacks(vlcRetryRunnable);

        RecentChannelsStore.record(this, channel);
        PresenceReporter.reportOnlineLaunch(this, channel.getTitle(), channel.getUrl());

        if (channelOverlay != null) {
            channelOverlay.setCurrentChannelId(channel.getId());
            if (!channelOverlay.isVisible()) {
                channelOverlay.showQuickInfoBannerForChannelId(channel.getId());
            }
        }

        isZapping = true;
        final long currentZapSeq = ++zapSequence;

        // Cancel any previous in-flight HTTP request so rapid CH+/CH- doesn't queue up sequentially
        Call prevCall = activeZapCall;
        if (prevCall != null) {
            try { prevCall.cancel(); } catch (Exception ignored) {}
        }

        // Keep screen on last frame, do not show center loading spinner
        if (loading != null) {
            loading.setVisibility(View.GONE);
        }
        // Mute audio immediately so previous channel stops playing instantly
        if (mediaPlayer != null) {
            try { mediaPlayer.setVolume(0); } catch (Exception ignored) {}
        }

        final int targetId = channel.getId();
        final String targetTitle = channel.getTitle();

        StreamInfoCache.Entry cached = StreamInfoCache.get(targetId);
        if (cached != null) {
            applyResolvedVlcStream(channel, targetId, targetTitle, cached.streamUrl, cached.meta, currentZapSeq);
            return;
        }

        ZAP_EXECUTOR.execute(() -> {
            if (currentZapSeq != zapSequence) return;
            try {
                String baseUrl = AuthPrefs.getBaseUrl(VlcPlayerActivity.this);
                String token = AuthPrefs.getAccessToken(VlcPlayerActivity.this);
                String url = baseUrl + "/api/channels/" + targetId + "/stream";
                Request req = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "MQLTV/1.0")
                        .header("Authorization", "Bearer " + token)
                        .build();

                Call call = NetworkClient.getClient().newCall(req);
                activeZapCall = call;
                try (Response resp = call.execute()) {
                    ResponseBody body = resp.body();
                    if (!resp.isSuccessful() || body == null) {
                        int code = resp != null ? resp.code() : 0;
                        String errorMsg = "Gagal memuat stream (HTTP " + code + ")";
                        if (body != null) {
                            try {
                                JSONObject errObj = new JSONObject(body.string());
                                String parsedErr = errObj.optString("error", "");
                                if (!parsedErr.isEmpty()) errorMsg = parsedErr;
                            } catch (Exception ignored) {}
                        }
                        final String finalErrorMsg = errorMsg;
                        runOnUiThread(() -> {
                            if (isFinishing() || currentZapSeq != zapSequence) return;
                            isZapping = false;
                            if (mediaPlayer != null) {
                                try { mediaPlayer.setVolume(100); } catch (Exception ignored) {}
                            }
                            if ((code == 401 || code == 403) && finalErrorMsg.toLowerCase().contains("subscription tidak aktif")) {
                                SubscriptionGuard.showExpired(VlcPlayerActivity.this);
                            } else {
                                Toast.makeText(VlcPlayerActivity.this, finalErrorMsg, Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }

                    JSONObject json = new JSONObject(body.string());
                    String streamUrl = json.optString("streamUrl", "");
                    if (streamUrl.isEmpty()) {
                        runOnUiThread(() -> {
                            if (isFinishing() || currentZapSeq != zapSequence) return;
                            isZapping = false;
                            if (mediaPlayer != null) {
                                try { mediaPlayer.setVolume(100); } catch (Exception ignored) {}
                            }
                            Toast.makeText(VlcPlayerActivity.this, "Stream tidak tersedia", Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }

                    JSONObject fakeMeta = new JSONObject();
                    fakeMeta.put("drm_type", json.isNull("drmType") ? "" : json.optString("drmType", ""));
                    fakeMeta.put("drm_key", json.isNull("drmKey") ? "" : json.optString("drmKey", ""));
                    fakeMeta.put("user_agent", json.isNull("userAgent") ? "" : json.optString("userAgent", ""));
                    fakeMeta.put("referer", json.isNull("referer") ? "" : json.optString("referer", ""));
                    ChannelPlaybackMeta meta = ChannelPlaybackMeta.fromVisionPlusObject(fakeMeta);
                    StreamInfoCache.put(targetId, streamUrl, meta);

                    runOnUiThread(() -> applyResolvedVlcStream(channel, targetId, targetTitle, streamUrl, meta, currentZapSeq));
                }
            } catch (Exception e) {
                if (activeZapCall != null && activeZapCall.isCanceled()) return;
                runOnUiThread(() -> {
                    if (isFinishing() || currentZapSeq != zapSequence) return;
                    isZapping = false;
                    if (mediaPlayer != null) {
                        try { mediaPlayer.setVolume(100); } catch (Exception ignored) {}
                    }
                    Toast.makeText(VlcPlayerActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void applyResolvedVlcStream(@NonNull Channel channel, int targetId, String targetTitle,
                                        @NonNull String streamUrl, @Nullable ChannelPlaybackMeta meta,
                                        long currentZapSeq) {
        if (isFinishing() || currentZapSeq != zapSequence) return;

        // Check if channel requires DRM (Media3/ExoPlayer), or is Radio, or preferred player is MX Player
        boolean isRadio = channel.getGroupTitle() != null && channel.getGroupTitle().toLowerCase().contains("radio");
        boolean requiresDrm = meta != null && meta.requiresExoDrm();
        boolean useMx = PlaybackPrefs.isUseMxPlayer(VlcPlayerActivity.this) && (meta == null || !meta.requiresExoDrm());

        if (isRadio || requiresDrm || useMx) {
            releasePlayer();
            Intent intent;
            if (requiresDrm) {
                intent = PlayerIntents.createPlayIntent(VlcPlayerActivity.this, channel.getId(), channel.getTitle(), streamUrl, meta);
            } else if (isRadio) {
                intent = PlayerIntents.createPlayIntent(VlcPlayerActivity.this, channel.getId(), channel.getTitle(), streamUrl, meta);
            } else {
                intent = PlayerIntents.createPreferredPlayIntent(VlcPlayerActivity.this, channel.getId(), channel.getTitle(), streamUrl, meta);
            }
            startActivity(intent);
            finish();
            return;
        }

        // IN-PLACE TRANSITION ON VLC
        setTitle(targetTitle);
        getIntent().putExtra(Constants.EXTRA_CHANNEL_ID, targetId);
        getIntent().putExtra(Constants.EXTRA_TITLE, targetTitle);
        getIntent().putExtra(Constants.EXTRA_URL, streamUrl);
        if (meta != null) meta.putInIntent(getIntent());

        PresenceReporter.startPlayback(getApplicationContext(), targetTitle, streamUrl);

        if (mediaPlayer != null && libVLC != null) {
            try {
                mediaPlayer.stop();
                Media media = new Media(libVLC, Uri.parse(streamUrl));
                configureVlcMedia(media, meta);
                mediaPlayer.setMedia(media);
                media.release();
                mediaPlayer.play();
            } catch (Exception e) {
                Log.e(TAG, "Error switching VLC media", e);
            }
        }
    }

    private void releasePlayer() {
        isZapping = false;
        uiHandler.removeCallbacks(vlcRetryRunnable);
        Call prevCall = activeZapCall;
        if (prevCall != null) {
            try { prevCall.cancel(); } catch (Exception ignored) {}
            activeZapCall = null;
        }
        abandonAudioFocus();
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

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
