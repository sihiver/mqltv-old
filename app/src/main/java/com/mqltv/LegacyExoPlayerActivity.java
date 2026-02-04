package com.mqltv;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.util.Util;

/**
 * ExoPlayer "legacy" (older line than Media3) for compatibility testing.
 */
public class LegacyExoPlayerActivity extends FragmentActivity {

    private static final String TAG = "LegacyExo";

    private SimpleExoPlayer player;
    private SurfaceView surfaceView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_legacy_exo_player);

        surfaceView = findViewById(R.id.legacy_surface);

        String title = getIntent().getStringExtra(Constants.EXTRA_TITLE);
        if (title != null) {
            setTitle(title);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (!SubscriptionGuard.ensureNotExpired(this)) {
            finish();
            return;
        }

        String title = getIntent().getStringExtra(Constants.EXTRA_TITLE);
        String url = getIntent().getStringExtra(Constants.EXTRA_URL);
        if (url == null || url.trim().isEmpty()) return;

        PresenceReporter.startPlayback(getApplicationContext(), title, url);

        DefaultTrackSelector trackSelector = new DefaultTrackSelector(this);
        boolean limit480p = PlaybackPrefs.isExoLimit480p(this);
        if (android.os.Build.VERSION.SDK_INT <= 19 || limit480p) {
            trackSelector.setParameters(
                trackSelector.buildUponParameters()
                    .setForceLowestBitrate(true)
                    .setMaxVideoSize(854, 480)
            );
        }

        RenderersFactory renderersFactory = new DefaultRenderersFactory(this)
            .setEnableDecoderFallback(!DeviceQuirks.isHuaweiEc6108v9())
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                        30_000,
                        120_000,
                        2_500,
                        5_000
            )
            .build();

        player = new SimpleExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build();
        if (surfaceView != null) {
            player.setVideoSurfaceView(surfaceView);
        }

        player.addListener(new Player.EventListener() {
            @Override
            public void onPlayerError(@NonNull ExoPlaybackException error) {
                Log.e(TAG, "Legacy Exo error type=" + error.type, error);

                boolean codecNotSupported = false;
                if (error.type == ExoPlaybackException.TYPE_RENDERER) {
                    Exception rendererEx = error.getRendererException();
                    if (rendererEx instanceof MediaCodecRenderer.DecoderInitializationException) {
                        codecNotSupported = true;
                    }
                }

                String msg = "Legacy Exo error: " + error.type;
                if (codecNotSupported) msg = "Legacy Exo: codec/decoder not supported";
                Toast.makeText(LegacyExoPlayerActivity.this, msg, Toast.LENGTH_LONG).show();

                // If AUTO chose legacy and it fails, try Media3 ExoPlayer as a fallback.
                if (codecNotSupported && PlaybackPrefs.getPlayerMode(LegacyExoPlayerActivity.this) == PlaybackPrefs.PLAYER_MODE_AUTO) {
                    String title = getIntent().getStringExtra(Constants.EXTRA_TITLE);
                    String playUrl = getIntent().getStringExtra(Constants.EXTRA_URL);
                    startActivity(PlayerIntents.createPlayIntent(LegacyExoPlayerActivity.this, title, playUrl));
                    finish();
                }
            }
        });

        String userAgent = Util.getUserAgent(this, "MQLTV");
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true);
        DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(this, httpFactory);

        Uri uri = Uri.parse(url);

        int type = Util.inferContentType(uri);
        MediaItem item = MediaItem.fromUri(uri);

        MediaSource mediaSource;
        if (type == com.google.android.exoplayer2.C.TYPE_HLS) {
            mediaSource = new HlsMediaSource.Factory(dataSourceFactory).createMediaSource(item);
        } else {
            // Many IPTV endpoints are TS/MP4 streams even when URL doesn't end with .m3u8.
            mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item);
        }

        player.setMediaSource(mediaSource);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isFinishing()) {
            PresenceReporter.stopPlayback(getApplicationContext());
        }
        if (player != null) {
            if (surfaceView != null) {
                player.clearVideoSurfaceView(surfaceView);
            }
            player.release();
            player = null;
        }
    }
}
