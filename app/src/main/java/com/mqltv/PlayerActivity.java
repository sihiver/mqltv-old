package com.mqltv;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
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

    @Override
    protected void onStart() {
        super.onStart();

        String url = getIntent().getStringExtra(Constants.EXTRA_URL);
        if (url == null || url.trim().isEmpty()) return;

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
            .setEnableDecoderFallback(true);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            // Slightly larger buffers help avoid rebuffering on unstable streams.
            .setBufferDurationsMs(
                15_000,
                60_000,
                2_000,
                4_000
            )
            .build();

        player = new ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
                .build();
        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
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

                // If ExoPlayer can't decode, try LibVLC as a fallback (often more tolerant on STBs).
                if (codecNotSupported && !PlayerIntents.shouldUseVlc(PlayerActivity.this)) {
                    String title = getIntent().getStringExtra(Constants.EXTRA_TITLE);
                    String url = getIntent().getStringExtra(Constants.EXTRA_URL);
                    startActivity(new Intent(PlayerActivity.this, VlcPlayerActivity.class)
                            .putExtra(Constants.EXTRA_TITLE, title)
                            .putExtra(Constants.EXTRA_URL, url));
                    finish();
                }
            }
        });

        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(url));
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
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
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
    }
}
