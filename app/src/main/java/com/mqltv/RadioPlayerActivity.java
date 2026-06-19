package com.mqltv;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.app.Activity;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;



public class RadioPlayerActivity extends Activity {

    private ExoPlayer player;
    private AudioVisualizerView visualizer;
    private TextView tvStatus;
    private TextView tvTitle;
    private ImageView imgLogo;
    private ImageView imgBg;

    private String url;
    private String title;
    private String logoUrl;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_radio_player);

        visualizer = findViewById(R.id.visualizer);
        tvStatus = findViewById(R.id.tv_status);
        tvTitle = findViewById(R.id.tv_title);
        imgLogo = findViewById(R.id.img_logo);
        imgBg = findViewById(R.id.img_radio_bg);

        Intent intent = getIntent();
        url = intent.getStringExtra(Constants.EXTRA_URL);
        title = intent.getStringExtra(Constants.EXTRA_TITLE);
        logoUrl = intent.getStringExtra(Constants.EXTRA_LOGO);

        if (title != null) tvTitle.setText(title);

        if (logoUrl != null && !logoUrl.isEmpty()) {
            loadImage(logoUrl);
        }

        initializePlayer();
    }

    private void loadImage(String url) {
        new Thread(() -> {
            try {
                java.net.URL imageUrl = new java.net.URL(url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) imageUrl.openConnection();
                conn.setDoInput(true);
                conn.connect();
                java.io.InputStream input = conn.getInputStream();
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(input);
                runOnUiThread(() -> {
                    if (bitmap != null) {
                        imgLogo.setImageBitmap(bitmap);
                    }
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void initializePlayer() {
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "URL Audio tidak valid", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        player = new ExoPlayer.Builder(this).build();
        
        MediaItem mediaItem = MediaItem.fromUri(url);

        player.setMediaItem(mediaItem);
        player.prepare();
        player.setPlayWhenReady(true);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                switch (playbackState) {
                    case Player.STATE_BUFFERING:
                        tvStatus.setText("Memuat...");
                        visualizer.setPlaying(false);
                        break;
                    case Player.STATE_READY:
                        if (player.getPlayWhenReady()) {
                            tvStatus.setText("Sedang Memutar");
                            visualizer.setPlaying(true);
                        } else {
                            tvStatus.setText("Dijeda");
                            visualizer.setPlaying(false);
                        }
                        break;
                    case Player.STATE_ENDED:
                        tvStatus.setText("Selesai");
                        visualizer.setPlaying(false);
                        break;
                    case Player.STATE_IDLE:
                        tvStatus.setText("Siap");
                        visualizer.setPlaying(false);
                        break;
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                visualizer.setPlaying(isPlaying);
                if (isPlaying) {
                    tvStatus.setText("Sedang Memutar");
                } else if (player.getPlaybackState() != Player.STATE_BUFFERING) {
                    tvStatus.setText("Dijeda");
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                tvStatus.setText("Gagal memutar: " + error.getMessage());
                visualizer.setPlaying(false);
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (player != null) {
                if (player.isPlaying()) {
                    player.pause();
                } else {
                    player.play();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null && !player.isPlaying()) {
            player.play();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
        visualizer.setPlaying(false);
    }
}
