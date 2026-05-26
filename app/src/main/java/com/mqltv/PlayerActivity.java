package com.mqltv;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.fragment.app.FragmentActivity;

import androidx.media3.common.MediaItem;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.video.MediaCodecVideoDecoderException;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;

public class PlayerActivity extends FragmentActivity {

    private ExoPlayer player;
    private PlayerView playerView;
    private ProgressBar loadingView;

    private PlayerChannelOverlayController channelOverlay;

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
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        playerView = findViewById(R.id.player_view);
        loadingView = findViewById(R.id.player_loading);

        String title = getIntent().getStringExtra(Constants.EXTRA_TITLE);
        if (title != null) {
            setTitle(title);
        }

        channelOverlay = new PlayerChannelOverlayController(this, channel -> {
            if (channel == null) return;
            if (!LoginGuard.ensureLoggedIn(PlayerActivity.this, LoginActivity.DEST_LIVE_TV)) return;
            if (!SubscriptionGuard.ensureNotExpired(PlayerActivity.this)) return;
            RecentChannelsStore.record(PlayerActivity.this, channel);
            PresenceReporter.reportOnlineLaunch(PlayerActivity.this, channel.getTitle(), channel.getUrl());
            Intent i = PlayerIntents.createPreferredPlayIntent(PlayerActivity.this, channel);
            try {
                startActivity(i);
            } catch (Exception e) {
                startActivity(PlayerIntents.createPlayIntent(PlayerActivity.this, channel));
            }
            finish();
        });
    }

    @SuppressLint("RestrictedApi")
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (channelOverlay != null && channelOverlay.handleKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @OptIn(markerClass = UnstableApi.class) @Override
    protected void onStart() {
        super.onStart();

        if (!PlaybackAccessEnforcer.ensureAccessOrFinish(this, LoginActivity.DEST_LIVE_TV)) return;

        String title = getIntent().getStringExtra(Constants.EXTRA_TITLE);
        String url = getIntent().getStringExtra(Constants.EXTRA_URL);
        if (url == null || url.trim().isEmpty()) return;

        if (channelOverlay != null) channelOverlay.setCurrentChannel(url);

        PresenceReporter.startPlayback(getApplicationContext(), title, url);

        DefaultTrackSelector trackSelector = new DefaultTrackSelector(this);
        boolean limit480p = PlaybackPrefs.isExoLimit480p(this);
        if (isProbablyEmulator() || android.os.Build.VERSION.SDK_INT <= 19 || limit480p) {
            trackSelector.setParameters(
                trackSelector.buildUponParameters()
                    .setForceLowestBitrate(true)
                    .setMaxVideoSize(854, 480)
            );
        }

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
            .setEnableDecoderFallback(!DeviceQuirks.isHuaweiEc6108v9())
            // Prefer extension decoders when available (e.g., FFmpeg for MP2 audio).
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            // Larger buffers help avoid rebuffering on unstable IPTV streams.
            .setBufferDurationsMs(
                30_000,
                120_000,
                2_500,
                5_000
            )
            .setBackBuffer(10_000, true)
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
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                applyMedia3VideoDisplay(videoSize);
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                if (loadingView != null) loadingView.setVisibility(View.GONE);
                String msg = "Playback error: " + error.getErrorCodeName();
                Throwable cause = error.getCause();
                while (cause != null) {
                    String cn = cause.getClass().getSimpleName();
                    if (cn.contains("Drm") || cn.contains("MediaDrm")) {
                        msg = "DRM gagal — periksa url_license / header di playlist JSON";
                        break;
                    }
                    cause = cause.getCause();
                }
                cause = error.getCause();
                boolean codecNotSupported = false;
                if (cause instanceof MediaCodecVideoDecoderException) {
                    msg = "Video codec not supported on this device";
                    codecNotSupported = true;
                } else if (error instanceof ExoPlaybackException) {
                    // Heuristic: common when stream is H.264 High Profile beyond emulator codec.
                    String detail = error.getMessage();
                    if (detail != null && detail.contains("NO_EXCEEDS_CAPABILITIES")) {
                        msg = "Stream not supported by device decoder";
                        codecNotSupported = true;
                    }
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
                Toast.makeText(PlayerActivity.this, msg, Toast.LENGTH_LONG).show();

                // If Media3 can't decode, fall back to VLC when not already in VLC mode.
                if (codecNotSupported) {
                    int mode = PlaybackPrefs.getPlayerMode(PlayerActivity.this);
                    if (mode != PlaybackPrefs.PLAYER_MODE_VLC) {
                        Intent vlcIntent = new Intent(PlayerActivity.this, VlcPlayerActivity.class);
                        vlcIntent.putExtras(getIntent());
                        startActivity(vlcIntent);
                        finish();
                    }
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (loadingView == null) return;
                if (state == Player.STATE_BUFFERING || state == Player.STATE_IDLE) {
                    loadingView.setVisibility(View.VISIBLE);
                } else if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                    loadingView.setVisibility(View.GONE);
                    if (state == Player.STATE_READY) {
                        applyMedia3VideoDisplay();
                        scheduleMedia3VideoDisplayRetry();
                    }
                }
            }

            @Override
            public void onIsLoadingChanged(boolean isLoading) {
                if (loadingView == null) return;
                loadingView.setVisibility(isLoading ? View.VISIBLE : View.GONE);
            }
        });

        ChannelPlaybackMeta meta = ChannelPlaybackMeta.fromIntent(getIntent());
        Uri uri = Uri.parse(url);
        if (meta != null && meta.isActive()) {
            android.util.Log.d("PlayerActivity", "Vision+ playback jenis=" + meta.getJenis()
                    + " headers=" + VisionPlusPlayback.mergedStreamHeaders(meta).keySet());
            MediaSource mediaSource = VisionPlusPlayback.buildMedia3Source(this, uri, meta);
            player.setMediaSource(mediaSource);
        } else {
            android.util.Log.w("PlayerActivity", "No Vision+ meta for url=" + url
                    + " — playlist JSON/header_iptv may be missing; re-login if needed.");
            player.setMediaItem(MediaItem.fromUri(uri));
        }
        player.prepare();
        player.play();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!PlaybackAccessEnforcer.ensureAccessOrFinish(this, LoginActivity.DEST_LIVE_TV)) return;
        accessHandler.removeCallbacks(accessTick);
        accessHandler.post(accessTick);
    }

    @Override
    protected void onPause() {
        accessHandler.removeCallbacks(accessTick);
        super.onPause();
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

    @Override
    protected void onStop() {
        super.onStop();
        accessHandler.removeCallbacks(accessTick);
        if (isFinishing()) {
            PresenceReporter.stopPlayback(getApplicationContext());
        }
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (channelOverlay != null) {
            channelOverlay.destroy();
            channelOverlay = null;
        }
        super.onDestroy();
    }
}
