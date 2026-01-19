package com.mqltv;

import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
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

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(url));
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
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
