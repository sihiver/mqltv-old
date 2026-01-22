package com.mqltv;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

public class VlcPlayerActivity extends FragmentActivity {

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private VLCVideoLayout videoLayout;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vlc_player);

        videoLayout = findViewById(R.id.vlc_video_layout);

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
        // Network buffering options; tune if needed.
        options.add("--network-caching=1500");
        options.add("--clock-jitter=0");
        options.add("--clock-synchro=0");
        int hwMode = PlaybackPrefs.getVlcHwDecoderMode(this);
        boolean useHw = hwMode != PlaybackPrefs.VLC_HW_OFF;
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
                if (event.type == MediaPlayer.Event.EncounteredError) {
                    Toast.makeText(VlcPlayerActivity.this, "VLC playback error", Toast.LENGTH_LONG).show();
                }
            }
        });

        // Important for some STBs: attach views only after the layout is attached/measured.
        videoLayout.post(() -> {
            if (isFinishing() || mediaPlayer == null || libVLC == null) return;

            boolean useTexture = PlaybackPrefs.isVlcUseTexture(VlcPlayerActivity.this);
            mediaPlayer.attachViews(videoLayout, null, false, useTexture);

            Media media = new Media(libVLC, Uri.parse(url));
            media.setHWDecoderEnabled(useHw, false);
            mediaPlayer.setMedia(media);
            media.release();

            mediaPlayer.play();
            mediaPlayer.updateVideoSurfaces();
        });
    }

    @Override
    protected void onStop() {
        super.onStop();

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
