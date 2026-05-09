package com.mqltv;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
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
import com.google.android.exoplayer2.video.MediaCodecVideoDecoderException;
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
            PlaybackAccessEnforcer.refreshThenEnforce(LegacyExoPlayerActivity.this, LoginActivity.DEST_LIVE_TV, () -> {
                accessCheckInFlight = false;
                if (!isFinishing()) accessHandler.postDelayed(accessTick, 30_000);
            });
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_legacy_exo_player);

        surfaceView = findViewById(R.id.legacy_surface);

        String title = getIntent().getStringExtra(Constants.EXTRA_TITLE);
        if (title != null) {
            setTitle(title);
        }

        channelOverlay = new PlayerChannelOverlayController(this, channel -> {
            if (channel == null) return;
            if (!LoginGuard.ensureLoggedIn(LegacyExoPlayerActivity.this, LoginActivity.DEST_LIVE_TV)) return;
            if (!SubscriptionGuard.ensureNotExpired(LegacyExoPlayerActivity.this)) return;
            RecentChannelsStore.record(LegacyExoPlayerActivity.this, channel);
            PresenceReporter.reportOnlineLaunch(LegacyExoPlayerActivity.this, channel.getTitle(), channel.getUrl());
            try {
                startActivity(PlayerIntents.createPreferredPlayIntent(LegacyExoPlayerActivity.this, channel.getTitle(), channel.getUrl()));
            } catch (Exception e) {
                startActivity(PlayerIntents.createPlayIntent(LegacyExoPlayerActivity.this, channel.getTitle(), channel.getUrl()));
            }
            finish();
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (channelOverlay != null && channelOverlay.handleKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
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
        int sdk = android.os.Build.VERSION.SDK_INT;
        if (sdk <= 19 || limit480p) {
            // API ≤19: force lowest bitrate + cap at 480p (very limited decoders).
            trackSelector.setParameters(
                trackSelector.buildUponParameters()
                    .setForceLowestBitrate(true)
                    .setMaxVideoSize(854, 480)
            );
        } else if (sdk <= 22) {
            // API 20–22: OMX.google.h264.decoder often crashes on 1080p streams.
            // Cap at 720p to stay within a safer operating range.
            trackSelector.setParameters(
                trackSelector.buildUponParameters()
                    .setMaxVideoSize(1280, 720)
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

                boolean isDecoderFailure = false;
                String reason = "error=" + error.type;

                if (error.type == ExoPlaybackException.TYPE_RENDERER) {
                    Exception rendererEx = error.getRendererException();

                    if (rendererEx instanceof MediaCodecRenderer.DecoderInitializationException) {
                        // Codec could not be initialized (format/profile unsupported).
                        isDecoderFailure = true;
                        reason = "codec tidak didukung";
                    } else if (rendererEx instanceof MediaCodecVideoDecoderException) {
                        // Decoder crashed at runtime (e.g. SIGSEGV in OMX.google.h264.decoder).
                        isDecoderFailure = true;
                        reason = "decoder video gagal saat runtime";
                    } else if (rendererEx != null) {
                        Throwable cause = rendererEx.getCause();
                        if (cause instanceof IllegalStateException) {
                            // MediaCodec dequeueOutputBuffer() in illegal state.
                            isDecoderFailure = true;
                            reason = "MediaCodec status error";
                        }
                    }
                }

                if (isDecoderFailure) {
                    Log.w(TAG, "Decoder failure (" + reason + "), releasing player and falling back to VLC.");

                    // Release immediately to stop further decoder activity / prevent native crash.
                    if (player != null) {
                        player.stop();
                        player.release();
                        player = null;
                    }

                    Toast.makeText(LegacyExoPlayerActivity.this,
                            "Decoder gagal, beralih ke VLC…", Toast.LENGTH_SHORT).show();

                    String title   = getIntent().getStringExtra(Constants.EXTRA_TITLE);
                    String playUrl = getIntent().getStringExtra(Constants.EXTRA_URL);

                    // Fallback to VLC — it has its own H.264 decoder and is more robust
                    // on STBs where OMX.google.h264.decoder is buggy.
                    // Do NOT use createPlayIntent() on old Android (≤19) because
                    // getTargetPlayerActivity() would return LegacyExoPlayerActivity again.
                    Intent vlcIntent = new Intent(LegacyExoPlayerActivity.this, VlcPlayerActivity.class);
                    vlcIntent.putExtra(Constants.EXTRA_TITLE, title);
                    vlcIntent.putExtra(Constants.EXTRA_URL, playUrl);
                    startActivity(vlcIntent);
                    finish();
                } else {
                    Toast.makeText(LegacyExoPlayerActivity.this,
                            "Playback error: " + reason, Toast.LENGTH_SHORT).show();
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

    @Override
    protected void onStop() {
        super.onStop();
        accessHandler.removeCallbacks(accessTick);
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

    @Override
    protected void onDestroy() {
        if (channelOverlay != null) {
            channelOverlay.destroy();
            channelOverlay = null;
        }
        super.onDestroy();
    }
}
