package com.mqltv;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.fragment.app.FragmentActivity;

import androidx.media3.common.MediaItem;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.video.MediaCodecVideoDecoderException;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;

public class PlayerActivity extends FragmentActivity {

    private ExoPlayer player;
    private PlayerView playerView;

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

        String title = getIntent().getStringExtra(Constants.EXTRA_TITLE);
        if (title != null) {
            setTitle(title);
        }
    }

    @OptIn(markerClass = UnstableApi.class) @Override
    protected void onStart() {
        super.onStart();

        if (!PlaybackAccessEnforcer.ensureAccessOrFinish(this, LoginActivity.DEST_LIVE_TV)) return;

        String title = getIntent().getStringExtra(Constants.EXTRA_TITLE);
        String url = getIntent().getStringExtra(Constants.EXTRA_URL);
        if (url == null || url.trim().isEmpty()) return;

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

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                String msg = "Playback error: " + error.getErrorCodeName();
                Throwable cause = error.getCause();
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

                Toast.makeText(PlayerActivity.this, msg, Toast.LENGTH_LONG).show();

                // If ExoPlayer can't decode, try the legacy ExoPlayer engine first (and only then VLC).
                if (codecNotSupported) {
                    int mode = PlaybackPrefs.getPlayerMode(PlayerActivity.this);
                    if (mode != PlaybackPrefs.PLAYER_MODE_VLC) {
                        String title = getIntent().getStringExtra(Constants.EXTRA_TITLE);
                        String playUrl = getIntent().getStringExtra(Constants.EXTRA_URL);
                        startActivity(new Intent(PlayerActivity.this, LegacyExoPlayerActivity.class)
                                .putExtra(Constants.EXTRA_TITLE, title)
                                .putExtra(Constants.EXTRA_URL, playUrl));
                        finish();
                    }
                }
            }
        });

        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(url));
        player.setMediaItem(mediaItem);
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
}
