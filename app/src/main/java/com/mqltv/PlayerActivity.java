package com.mqltv;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.video.MediaCodecVideoDecoderException;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;

public class PlayerActivity extends FragmentActivity {

    private ExoPlayer player;
    private PlayerView playerView;
    private ProgressBar loadingView;

    private PlayerChannelOverlayController channelOverlay;
    @Nullable private DefaultTrackSelector trackSelector;
    private boolean visionPlusPlayback;
    private ChannelMixerAudioProcessor channelMixerAudioProcessor;

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

    private final Handler accessHandler = new Handler(Looper.getMainLooper());
    private boolean accessCheckInFlight = false;

    private final Runnable accessTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            if (accessCheckInFlight) {
                accessHandler.postDelayed(this, 3000);
                return;
            }
            accessCheckInFlight = true;
            PlaybackAccessEnforcer.refreshThenEnforce(PlayerActivity.this, LoginActivity.DEST_LIVE_TV, () -> {
                accessCheckInFlight = false;
                if (!isFinishing()) accessHandler.postDelayed(accessTick, 30_000);
            });
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        overridePendingTransition(0, 0);
        super.onCreate(savedInstanceState);

        String url = getIntent().getStringExtra(Constants.EXTRA_URL);
        ChannelPlaybackMeta meta = ChannelPlaybackMeta.fromIntent(getIntent());
        if (DrmCapabilities.isDashClearKeyUnsupportedOnThisDevice(meta, url)) {
            android.util.Log.w("PlayerActivity", "dash-clearkey blocked on API "
                    + android.os.Build.VERSION.SDK_INT + " jenis="
                    + (meta != null ? meta.getJenis() : ""));
            Toast.makeText(this, DrmCapabilities.getDashClearKeyUnsupportedMessage(),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setContentView(R.layout.activity_player);

        playerView = findViewById(R.id.player_view);
        if (playerView != null) {
            // Keep last frame visible when reset (satellite TV / Kodi freeze-frame zapping)
            playerView.setKeepContentOnPlayerReset(true);
        }
        loadingView = findViewById(R.id.player_loading);

        String title = getIntent().getStringExtra(Constants.EXTRA_TITLE);
        if (title != null) {
            setTitle(title);
        }

        channelOverlay = new PlayerChannelOverlayController(this, this::switchChannelInPlace);
    }

    @SuppressLint("RestrictedApi")
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (channelOverlay != null && channelOverlay.handleKeyEvent(event)) return true;

        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == android.view.KeyEvent.KEYCODE_CHANNEL_UP || keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                if (channelOverlay != null) {
                    channelOverlay.zapNextChannel();
                    return true;
                }
            } else if (keyCode == android.view.KeyEvent.KEYCODE_CHANNEL_DOWN || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                if (channelOverlay != null) {
                    channelOverlay.zapPrevChannel();
                    return true;
                }
            } else if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK
                    || keyCode == android.view.KeyEvent.KEYCODE_MENU
                    || keyCode == android.view.KeyEvent.KEYCODE_PROG_RED) {
                toggleAudioChannelMode();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void toggleAudioChannelMode() {
        // First check if the stream exposes multiple discrete audio tracks (e.g., Track 1 Indo, Track 2 Eng)
        if (player != null && trackSelector != null) {
            Tracks currentTracks = player.getCurrentTracks();
            java.util.List<Tracks.Group> audioGroups = new java.util.ArrayList<>();
            for (Tracks.Group group : currentTracks.getGroups()) {
                if (group.getType() == C.TRACK_TYPE_AUDIO && group.isSupported()) {
                    audioGroups.add(group);
                }
            }
            int totalDiscreteTracks = 0;
            for (Tracks.Group g : audioGroups) {
                totalDiscreteTracks += g.length;
            }
            if (totalDiscreteTracks > 1) {
                int selectedFlatIndex = 0;
                int flatIdx = 0;
                for (Tracks.Group g : audioGroups) {
                    for (int t = 0; t < g.length; t++) {
                        if (g.isTrackSelected(t)) {
                            selectedFlatIndex = flatIdx;
                        }
                        flatIdx++;
                    }
                }
                int nextFlatIndex = (selectedFlatIndex + 1) % totalDiscreteTracks;
                flatIdx = 0;
                for (Tracks.Group g : audioGroups) {
                    for (int t = 0; t < g.length; t++) {
                        if (flatIdx == nextFlatIndex) {
                            androidx.media3.common.Format fmt = g.getTrackFormat(t);
                            String lang = fmt.language != null ? fmt.language.toUpperCase() : "";
                            String lbl = fmt.label != null ? fmt.label : ("Track " + (nextFlatIndex + 1));
                            String desc = !lang.isEmpty() ? (lbl + " (" + lang + ")") : lbl;
                            trackSelector.setParameters(
                                    trackSelector.buildUponParameters()
                                            .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), t))
                            );
                            Toast.makeText(this, "Audio Track: " + desc, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        flatIdx++;
                    }
                }
            }
        }

        if (channelMixerAudioProcessor == null) return;
        int currentMode = channelMixerAudioProcessor.getMode();
        int newMode;
        String label;
        if (currentMode == ChannelMixerAudioProcessor.MODE_STEREO) {
            newMode = ChannelMixerAudioProcessor.MODE_LEFT_ONLY;
            label = "Audio: Kiri (Bahasa Indonesia)";
        } else if (currentMode == ChannelMixerAudioProcessor.MODE_LEFT_ONLY) {
            newMode = ChannelMixerAudioProcessor.MODE_RIGHT_ONLY;
            label = "Audio: Kanan (Bahasa Inggris / Original)";
        } else {
            newMode = ChannelMixerAudioProcessor.MODE_STEREO;
            label = "Audio: Stereo (Semua Bunyi)";
        }
        channelMixerAudioProcessor.setMode(newMode);
        PlaybackPrefs.setAudioChannelMode(this, newMode);
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (channelOverlay != null && channelOverlay.isVisible()) {
            channelOverlay.hide();
            return;
        }
        super.onBackPressed();
    }

    private int errorRetryCount = 0;
    private static final int MAX_AUTO_RETRIES = 3;
    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || player == null) return;
            int currentId = getIntent().getIntExtra(Constants.EXTRA_CHANNEL_ID, 0);
            if (errorRetryCount >= 2 && currentId > 0) {
                // Re-fetch fresh stream URL/token on 2nd+ retry in case stream token expired
                StreamInfoCache.invalidate(currentId);
                String title = getIntent().getStringExtra(Constants.EXTRA_TITLE);
                Channel dummy = new Channel(currentId, title != null ? title : "Channel", "", "", false, 0);
                refreshCurrentChannelStream(dummy);
            } else {
                player.seekToDefaultPosition();
                player.prepare();
                player.play();
            }
        }
    };

    @OptIn(markerClass = UnstableApi.class) @Override
    protected void onStart() {
        super.onStart();

        if (!PlaybackAccessEnforcer.ensureAccessOrFinish(this, LoginActivity.DEST_LIVE_TV)) return;

        String title = getIntent().getStringExtra(Constants.EXTRA_TITLE);
        String url = getIntent().getStringExtra(Constants.EXTRA_URL);
        if (url == null || url.trim().isEmpty()) return;

        ChannelPlaybackMeta meta = ChannelPlaybackMeta.fromIntent(getIntent());

        int currentId = getIntent().getIntExtra(Constants.EXTRA_CHANNEL_ID, 0);
        if (channelOverlay != null) {
            channelOverlay.setCurrentChannelId(currentId);
            channelOverlay.showQuickInfoBannerForChannelId(currentId);
        }

        PresenceReporter.startPlayback(getApplicationContext(), title, url);

        trackSelector = new DefaultTrackSelector(this);
        visionPlusPlayback = meta != null && meta.isActive();
        if (visionPlusPlayback) {
            PlayerQualityHelper.apply(trackSelector, PlaybackPrefs.getVisionVideoQuality(this));
        } else {
            boolean limit480p = PlaybackPrefs.isExoLimit480p(this);
            if (isProbablyEmulator() || android.os.Build.VERSION.SDK_INT <= 19 || limit480p) {
                trackSelector.setParameters(
                        trackSelector.buildUponParameters()
                                .setForceLowestBitrate(true)
                                .setMaxVideoSize(854, 480)
                );
            }
        }

        channelMixerAudioProcessor = new ChannelMixerAudioProcessor();
        channelMixerAudioProcessor.setMode(PlaybackPrefs.getAudioChannelMode(this));
        channelMixerAudioProcessor.setListener(suggestedMode -> {
            channelMixerAudioProcessor.setMode(suggestedMode);
            PlaybackPrefs.setAudioChannelMode(PlayerActivity.this, suggestedMode);
            Toast.makeText(PlayerActivity.this,
                    "Mendeteksi Dual Audio: Mengaktifkan Bahasa Indonesia otomatis",
                    Toast.LENGTH_LONG).show();
        });

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this) {
            @Override
            @Nullable
            protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(
                    android.content.Context context,
                    boolean enableFloatOutput,
                    boolean enableAudioTrackPlaybackParams) {
                androidx.media3.exoplayer.audio.AudioSink baseSink =
                        new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                                .setAudioProcessors(new androidx.media3.common.audio.AudioProcessor[] { channelMixerAudioProcessor })
                                .setEnableFloatOutput(enableFloatOutput)
                                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                                .build();
                return new DiscontinuityIgnoringAudioSink(baseSink);
            }
        };
        renderersFactory.setEnableDecoderFallback(!DeviceQuirks.isHuaweiEc6108v9())
                .setAllowedVideoJoiningTimeMs(5_000L)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            // Optimized buffer tuning for live IPTV & 1GB RAM STBs:
            // Fast start (1.2s) and capped maxBuffer (30s) to prevent OOM / GC stalls
            .setBufferDurationsMs(
                15_000,
                30_000,
                1_200,
                2_500
            )
            .setBackBuffer(0, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build();

        player = new ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
                .build();

        // Helps with correct audio routing & focus behavior on modern Android.
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        playerView.setPlayer(player);
        applyMedia3VideoDisplay();

        player.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                isZapping = false;
                errorRetryCount = 0;
                idleHandler.removeCallbacks(retryRunnable);
                if (player != null) {
                    player.setVolume(1f);
                }
                if (loadingView != null) {
                    loadingView.setVisibility(View.GONE);
                }
            }

            @Override
            public void onTracksChanged(@NonNull androidx.media3.common.Tracks tracks) {
                if (visionPlusPlayback && trackSelector != null) {
                    PlayerQualityHelper.apply(trackSelector,
                            PlaybackPrefs.getVisionVideoQuality(PlayerActivity.this));
                }
            }

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                applyMedia3VideoDisplay(videoSize);
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                if (loadingView != null) loadingView.setVisibility(View.GONE);

                // 1. Live sliding window recovery: immediately jump back to live edge without error toast
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    android.util.Log.w("PlayerActivity", "BehindLiveWindowException: seeking back to live edge");
                    if (player != null) {
                        player.seekToDefaultPosition();
                        player.prepare();
                        player.play();
                    }
                    return;
                }

                String msg = "Playback error: " + error.getErrorCodeName();
                Throwable cause = error.getCause();
                ChannelPlaybackMeta drmMeta = ChannelPlaybackMeta.fromIntent(getIntent());
                String playUrl = getIntent().getStringExtra(Constants.EXTRA_URL);
                boolean isDrmError = false;
                while (cause != null) {
                    String cn = cause.getClass().getSimpleName();
                    if (cn.contains("Drm") || cn.contains("MediaDrm")) {
                        isDrmError = true;
                        if (DrmCapabilities.isDashClearKeyUnsupportedOnThisDevice(drmMeta, playUrl)) {
                            msg = DrmCapabilities.getDashClearKeyUnsupportedMessage();
                        } else {
                            msg = "DRM gagal — periksa drm_key / url_license / header di playlist JSON";
                        }
                        break;
                    }
                    cause = cause.getCause();
                }
                cause = error.getCause();
                boolean isDrm = drmMeta != null && drmMeta.requiresExoDrm();
                boolean isDecoderOrFormatError = (cause instanceof MediaCodecRenderer.DecoderInitializationException)
                        || (cause instanceof MediaCodecVideoDecoderException)
                        || error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                        || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                        || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                        || error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                        || error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;

                if (!isDecoderOrFormatError && error instanceof ExoPlaybackException) {
                    // Heuristic: common when stream is H.264 High Profile beyond emulator codec or malformed TS
                    String detail = error.getMessage();
                    if (detail != null && (detail.contains("NO_EXCEEDS_CAPABILITIES")
                            || detail.contains("DecoderInitException")
                            || detail.contains("UnrecognizedInputFormatException"))) {
                        isDecoderOrFormatError = true;
                    }
                }

                // 2. Auto-reconnect with exponential backoff for transient network/source hiccups
                boolean isTransientNetworkOrSourceError = !isDecoderOrFormatError && !isDrmError && (
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                        || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                        || error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                        || error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
                        || error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                        || error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT
                        || error.errorCode == PlaybackException.ERROR_CODE_UNSPECIFIED
                        || error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                        || error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                );

                if (isTransientNetworkOrSourceError && errorRetryCount < MAX_AUTO_RETRIES) {
                    errorRetryCount++;
                    long delayMs = errorRetryCount == 1 ? 1_500L : (errorRetryCount == 2 ? 3_000L : 5_000L);
                    android.util.Log.w("PlayerActivity", "Auto-retry (" + errorRetryCount + "/" + MAX_AUTO_RETRIES
                            + ") in " + delayMs + "ms due to " + error.getErrorCodeName());
                    idleHandler.removeCallbacks(retryRunnable);
                    idleHandler.postDelayed(retryRunnable, delayMs);
                    return;
                }

                if (isDecoderOrFormatError) {
                    msg = "Format/decoder tidak didukung player utama, beralih...";
                }

                Throwable root = error.getCause();
                while (root != null && root.getCause() != null) root = root.getCause();
                if (root != null && root.getMessage() != null
                        && root.getMessage().toLowerCase().contains("connection")) {
                    ChannelPlaybackMeta m = ChannelPlaybackMeta.fromIntent(getIntent());
                    if (m == null || !m.isActive()) {
                        msg = "Koneksi gagal — playlist tanpa header Vision+. Logout lalu login ulang.";
                    } else {
                        msg = "Koneksi gagal — periksa jaringan / header_iptv channel";
                    }
                }
                isZapping = false;
                if (player != null) player.setVolume(1f);
                Toast.makeText(PlayerActivity.this, msg, Toast.LENGTH_LONG).show();

                // Smart tiered fallback logic (hanya untuk non-DRM, karena player cadangan tidak mendukung lisensi DRM)
                if (!isDrm) {
                    if (android.os.Build.VERSION.SDK_INT <= 19) {
                        // Android 4.4 and below: always fall back to Native (MediaPlayer)
                        releasePlayer();
                        Intent nativeIntent = new Intent(PlayerActivity.this, NativePlayerActivity.class);
                        nativeIntent.putExtras(getIntent());
                        startActivity(nativeIntent);
                        finish();
                    } else if (isDecoderOrFormatError) {
                        // Modern Android versions: fall back to VLC on codec/format errors
                        int mode = PlaybackPrefs.getPlayerMode(PlayerActivity.this);
                        if (mode != PlaybackPrefs.PLAYER_MODE_VLC) {
                            releasePlayer();
                            Intent vlcIntent = new Intent(PlayerActivity.this, VlcPlayerActivity.class);
                            vlcIntent.putExtras(getIntent());
                            startActivity(vlcIntent);
                            finish();
                        }
                    }
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (loadingView != null) loadingView.setVisibility(View.GONE);
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                    isZapping = false;
                    if (player != null) player.setVolume(1f);
                    if (state == Player.STATE_READY) {
                        applyMedia3VideoDisplay();
                        scheduleMedia3VideoDisplayRetry();
                    }
                }
            }

            @Override
            public void onIsLoadingChanged(boolean isLoading) {
                if (loadingView != null) loadingView.setVisibility(View.GONE);
            }
        });

        Uri uri = Uri.parse(url);
        if (meta != null && meta.isActive()) {
            android.util.Log.d("PlayerActivity", "Vision+ playback jenis=" + meta.getJenis()
                    + " headers=" + VisionPlusPlayback.mergedStreamHeaders(meta).keySet());
            MediaSource mediaSource = VisionPlusPlayback.buildMedia3Source(this, uri, meta);
            player.setMediaSource(mediaSource);
        } else {
            android.util.Log.w("PlayerActivity", "No Vision+ meta for url=" + url
                    + " — playlist JSON/header_iptv may be missing; re-login if needed.");
            MediaSource mediaSource = VisionPlusPlayback.buildMedia3Source(this, uri, meta);
            player.setMediaSource(mediaSource);
        }
        player.prepare();
        player.play();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resetIdleTimer();
        if (!PlaybackAccessEnforcer.ensureAccessOrFinish(this, LoginActivity.DEST_LIVE_TV)) return;
        accessHandler.removeCallbacks(accessTick);
        accessHandler.post(accessTick);
    }

    @Override
    protected void onPause() {
        stopIdleTimer();
        accessHandler.removeCallbacks(accessTick);
        if (player != null) {
            player.pause();
        }
        super.onPause();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (idleDialog != null && idleDialog.isShowing()) {
            dismissIdleConfirmationDialog();
        }
        resetIdleTimer();
    }

    private void applyMedia3VideoDisplay() {
        if (playerView == null) return;
        VideoSize vs = player != null ? player.getVideoSize() : VideoSize.UNKNOWN;
        applyMedia3VideoDisplay(vs);
    }

    private void applyMedia3VideoDisplay(@NonNull VideoSize videoSize) {
        if (playerView == null) return;
        VideoDisplayHelper.applyToMedia3PlayerView(
                playerView,
                this,
                videoSize.width,
                videoSize.height,
                videoSize.pixelWidthHeightRatio);
    }

    private void scheduleMedia3VideoDisplayRetry() {
        if (playerView == null) return;
        playerView.post(() -> applyMedia3VideoDisplay());
        playerView.postDelayed(this::applyMedia3VideoDisplay, 150);
        playerView.postDelayed(this::applyMedia3VideoDisplay, 500);
    }

    private static boolean isProbablyEmulator() {
        String fingerprint = android.os.Build.FINGERPRINT;
        String model = android.os.Build.MODEL;
        String manufacturer = android.os.Build.MANUFACTURER;
        String brand = android.os.Build.BRAND;
        String device = android.os.Build.DEVICE;
        String product = android.os.Build.PRODUCT;

        if (fingerprint != null && (fingerprint.contains("generic") || fingerprint.contains("unknown"))) return true;
        if (model != null && (model.contains("google_sdk") || model.contains("Emulator") || model.contains("Android SDK built for"))) return true;
        if (manufacturer != null && manufacturer.toLowerCase().contains("genymotion")) return true;
        if (brand != null && device != null && brand.startsWith("generic") && device.startsWith("generic")) return true;
        return product != null && product.contains("sdk");
    }

    private boolean isZapping = false;
    private long zapSequence = 0;
    @Nullable private volatile Call activeZapCall = null;
    private static final ExecutorService ZAP_EXECUTOR = Executors.newSingleThreadExecutor();

    private void refreshCurrentChannelStream(@NonNull Channel channel) {
        performChannelStreamLoad(channel, true);
    }

    public void switchChannelInPlace(@Nullable Channel channel) {
        if (channel == null || isFinishing()) return;

        int currentId = getIntent().getIntExtra(Constants.EXTRA_CHANNEL_ID, 0);
        if (channel.getId() == currentId) return;

        if (!LoginGuard.ensureLoggedIn(this, LoginActivity.DEST_LIVE_TV)) return;
        if (!SubscriptionGuard.ensureNotExpired(this)) return;

        errorRetryCount = 0;
        idleHandler.removeCallbacks(retryRunnable);

        RecentChannelsStore.record(this, channel);
        PresenceReporter.reportOnlineLaunch(this, channel.getTitle(), channel.getUrl());

        if (channelOverlay != null) {
            channelOverlay.setCurrentChannelId(channel.getId());
            if (!channelOverlay.isVisible()) {
                channelOverlay.showQuickInfoBannerForChannelId(channel.getId());
            }
        }

        performChannelStreamLoad(channel, false);
    }

    private void performChannelStreamLoad(@NonNull Channel channel, boolean bypassCache) {
        isZapping = true;
        final long currentZapSeq = ++zapSequence;

        // Cancel any previous in-flight HTTP request so rapid CH+/CH- doesn't queue up sequentially
        Call prevCall = activeZapCall;
        if (prevCall != null) {
            try { prevCall.cancel(); } catch (Exception ignored) {}
        }

        // Keep screen on last frame, do not show center loading spinner
        if (loadingView != null) {
            loadingView.setVisibility(View.GONE);
        }
        // Mute audio immediately so previous channel stops playing instantly
        if (player != null) {
            player.setVolume(0f);
        }

        final int targetId = channel.getId();
        final String targetTitle = channel.getTitle();

        if (!bypassCache) {
            StreamInfoCache.Entry cached = StreamInfoCache.get(targetId);
            if (cached != null) {
                applyResolvedChannelStream(channel, targetId, targetTitle, cached.streamUrl, cached.meta, currentZapSeq);
                return;
            }
        }

        ZAP_EXECUTOR.execute(() -> {
            if (currentZapSeq != zapSequence) return;
            try {
                String baseUrl = AuthPrefs.getBaseUrl(PlayerActivity.this);
                String token = AuthPrefs.getAccessToken(PlayerActivity.this);
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
                            } catch (Exception ignored) { }
                        }
                        final String finalErrorMsg = errorMsg;
                        runOnUiThread(() -> {
                            if (isFinishing() || currentZapSeq != zapSequence) return;
                            isZapping = false;
                            if (player != null) player.setVolume(1f);
                            if ((code == 401 || code == 403) && finalErrorMsg.toLowerCase().contains("subscription tidak aktif")) {
                                SubscriptionGuard.showExpired(PlayerActivity.this);
                            } else {
                                Toast.makeText(PlayerActivity.this, finalErrorMsg, Toast.LENGTH_LONG).show();
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
                            if (player != null) player.setVolume(1f);
                            Toast.makeText(PlayerActivity.this, "Stream tidak tersedia", Toast.LENGTH_SHORT).show();
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

                    runOnUiThread(() -> applyResolvedChannelStream(channel, targetId, targetTitle, streamUrl, meta, currentZapSeq));
                }
            } catch (Exception e) {
                if (activeZapCall != null && activeZapCall.isCanceled()) return;
                runOnUiThread(() -> {
                    if (isFinishing() || currentZapSeq != zapSequence) return;
                    isZapping = false;
                    if (player != null) player.setVolume(1f);
                    Toast.makeText(PlayerActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void applyResolvedChannelStream(@NonNull Channel channel, int targetId, String targetTitle,
                                            @NonNull String streamUrl, @Nullable ChannelPlaybackMeta meta,
                                            long currentZapSeq) {
        if (isFinishing() || currentZapSeq != zapSequence) return;

        // Check if channel is Radio, or preferred player is MX Player or Native / VLC
        Class<?> target = PlayerIntents.getTargetPlayerActivity(PlayerActivity.this, meta);
        boolean isRadio = channel.getGroupTitle() != null && channel.getGroupTitle().toLowerCase().contains("radio");
        boolean useMx = PlaybackPrefs.isUseMxPlayer(PlayerActivity.this) && (meta == null || !meta.requiresExoDrm());

        if (isRadio || useMx || target != PlayerActivity.class) {
            releasePlayer();
            Intent intent = isRadio
                    ? PlayerIntents.createPlayIntent(PlayerActivity.this, channel.getId(), channel.getTitle(), streamUrl, meta)
                    : PlayerIntents.createPreferredPlayIntent(PlayerActivity.this, channel.getId(), channel.getTitle(), streamUrl, meta);
            startActivity(intent);
            finish();
            return;
        }

        if (DrmCapabilities.isDashClearKeyUnsupportedOnThisDevice(meta, streamUrl)) {
            isZapping = false;
            if (player != null) player.setVolume(1f);
            Toast.makeText(PlayerActivity.this, DrmCapabilities.getDashClearKeyUnsupportedMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        // IN-PLACE TRANSITION: Freeze frame remains on screen, swap source seamlessly
        setTitle(targetTitle);
        getIntent().putExtra(Constants.EXTRA_CHANNEL_ID, targetId);
        getIntent().putExtra(Constants.EXTRA_TITLE, targetTitle);
        getIntent().putExtra(Constants.EXTRA_URL, streamUrl);
        if (meta != null) meta.putInIntent(getIntent());

        PresenceReporter.startPlayback(getApplicationContext(), targetTitle, streamUrl);

        visionPlusPlayback = meta != null && meta.isActive();
        if (visionPlusPlayback && trackSelector != null) {
            PlayerQualityHelper.apply(trackSelector, PlaybackPrefs.getVisionVideoQuality(PlayerActivity.this));
        }

        if (channelMixerAudioProcessor != null) {
            channelMixerAudioProcessor.resetDetection();
        }

        MediaSource mediaSource = VisionPlusPlayback.buildMedia3Source(PlayerActivity.this, Uri.parse(streamUrl), meta);
        if (player != null) {
            player.setMediaSource(mediaSource);
            player.prepare();
            player.play();
        }
    }

    private void releasePlayer() {
        isZapping = false;
        idleHandler.removeCallbacks(retryRunnable);
        Call prevCall = activeZapCall;
        if (prevCall != null) {
            try { prevCall.cancel(); } catch (Exception ignored) {}
            activeZapCall = null;
        }
        if (player != null) {
            if (playerView != null) {
                playerView.setPlayer(null);
            }
            try {
                player.stop();
                player.clearMediaItems();
                player.release();
            } catch (Throwable t) {
                android.util.Log.w("PlayerActivity", "releasePlayer error", t);
            }
            player = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        accessHandler.removeCallbacks(accessTick);
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
        trackSelector = null;
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
