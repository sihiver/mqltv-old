package com.mqltv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.SurfaceView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSourceFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.RawResourceDataSource;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoListener;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

public class LauncherCardAdapter extends RecyclerView.Adapter<LauncherCardAdapter.VH> {

    private static final String TAG = "LauncherCardVideo";

    private static final int VIEW_TYPE_DEFAULT = 0;
    private static final int VIEW_TYPE_LIVE_TV = 1;

    public interface Listener {
        void onCardClicked(LauncherCard card);
        void onCardFocused(LauncherCard card);
    }

    private final List<LauncherCard> items = new ArrayList<>();
    private final Listener listener;
    private LauncherCardStyle cardStyle;

    private static final Object PAYLOAD_STYLE = new Object();
    private static final Object PAYLOAD_CONTENT = new Object();
    private static final Object PAYLOAD_LIVETV_BG_STATE = new Object();

    private SimpleExoPlayer liveTvBgPlayer;
    private boolean liveTvBgFailed;
    private boolean liveTvBgFallbackToLocal;
    private boolean liveTvBgHttpRetried;
    private boolean liveTvBgRenderedFirstFrame;
    private boolean liveTvBgPrepared;
    private boolean hostActive = true;

    public LauncherCardAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setCardStyle(LauncherCardStyle style) {
        this.cardStyle = style;
        if (getItemCount() > 0) {
            notifyItemRangeChanged(0, getItemCount(), PAYLOAD_STYLE);
        }
    }

    public void setHostActive(boolean active) {
        hostActive = active;
        if (liveTvBgPlayer != null) {
            try {
                if (active && !liveTvBgFailed) {
                    liveTvBgPlayer.setPlayWhenReady(true);
                } else {
                    liveTvBgPlayer.setPlayWhenReady(false);
                }
            } catch (Exception ignored) {
            }
        }
    }

    public void release() {
        try {
            if (liveTvBgPlayer != null) {
                liveTvBgPlayer.release();
            }
        } catch (Exception ignored) {
        } finally {
            liveTvBgPlayer = null;
            liveTvBgPrepared = false;
        }
    }

    public void submit(List<LauncherCard> cards) {
        if (cards == null) {
            items.clear();
            notifyDataSetChanged();
            return;
        }

        // If the list shape is unchanged (same size and same destinations), update in-place to
        // preserve focused ViewHolders and avoid focus jumping.
        boolean sameShape = items.size() == cards.size();
        if (sameShape) {
            for (int i = 0; i < items.size(); i++) {
                LauncherCard old = items.get(i);
                LauncherCard neu = cards.get(i);
                if (old == null || neu == null || old.getDestination() != neu.getDestination()) {
                    sameShape = false;
                    break;
                }
            }
        }

        if (sameShape) {
            items.clear();
            items.addAll(cards);
            notifyItemRangeChanged(0, items.size(), PAYLOAD_CONTENT);
            return;
        }

        items.clear();
        items.addAll(cards);
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        LauncherCard card = position >= 0 && position < items.size() ? items.get(position) : null;
        if (card == null || card.getDestination() == null) return position;
        return card.getDestination().ordinal();
    }

    private int findLiveTvCardPosition() {
        for (int i = 0; i < items.size(); i++) {
            LauncherCard c = items.get(i);
            if (c != null && c.getDestination() == NavDestination.LIVE_TV) return i;
        }
        return 0;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == VIEW_TYPE_LIVE_TV ? R.layout.item_launcher_card_live_tv : R.layout.item_launcher_card;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v);
    }

    @Override
    public int getItemViewType(int position) {
        LauncherCard card = position >= 0 && position < items.size() ? items.get(position) : null;
        if (card != null && card.getDestination() == NavDestination.LIVE_TV) return VIEW_TYPE_LIVE_TV;
        return VIEW_TYPE_DEFAULT;
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        onBindViewHolder(holder, position, java.util.Collections.emptyList());
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position, @NonNull List<Object> payloads) {
        LauncherCard card = position >= 0 && position < items.size() ? items.get(position) : null;
        if (card == null) return;

        boolean isLiveTv = card.getDestination() == NavDestination.LIVE_TV;
        boolean isRadio = card.getDestination() == NavDestination.SHOWS;

        boolean isPartial = payloads != null && !payloads.isEmpty();
        boolean needsContent = !isPartial || payloads.contains(PAYLOAD_CONTENT);
        boolean needsStyle = !isPartial || payloads.contains(PAYLOAD_STYLE);
        boolean needsLiveTvState = !isPartial || payloads.contains(PAYLOAD_LIVETV_BG_STATE);

        int colorSecondary = ContextCompat.getColor(holder.itemView.getContext(), R.color.mql_text_secondary);
        int colorAccent = ContextCompat.getColor(holder.itemView.getContext(), R.color.mql_accent);

        if (needsContent) {
            holder.title.setText(card.getTitle());
            holder.subtitle.setText(card.getSubtitle() != null ? card.getSubtitle() : "");
            holder.icon.setImageResource(card.getIconRes());
        }
        if (!isPartial) {
            holder.indicator.setVisibility(View.INVISIBLE);
        }

        // Avoid overriding focused tint during payload updates.
        holder.icon.setColorFilter(holder.itemView.hasFocus() ? colorAccent : colorSecondary);

        if (needsStyle) {
            if (isRadio) {
                if (cardStyle != null) {
                    StateListDrawable bg = createCardBackground(holder.itemView.getContext(), cardStyle, 18);
                    bg.setAlpha(204); // ~80% opacity
                    holder.itemView.setBackground(bg);
                } else {
                    holder.itemView.setBackgroundResource(R.drawable.launcher_card_bg_radio_80);
                }
            } else {
                if (cardStyle != null) {
                    holder.itemView.setBackground(createCardBackground(holder.itemView.getContext(), cardStyle, isLiveTv ? 0 : 18));
                } else {
                    holder.itemView.setBackgroundResource(isLiveTv ? R.drawable.launcher_card_bg_square : R.drawable.launcher_card_bg);
                }
            }
        }

        if (!isPartial) {
            bindLiveTvVideo(holder, card);
        } else if (needsLiveTvState && isLiveTv) {
            bindLiveTvVideo(holder, card);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onCardClicked(card);
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            float s = (isLiveTv ? 1.0f : (hasFocus ? 1.03f : 1.0f));
            v.animate().scaleX(s).scaleY(s).setDuration(120).start();

            // Live TV card: keep flat (no shadow-like elevation on focus).
            if (isLiveTv) {
                ViewCompat.setElevation(v, 0f);
            }
            v.setActivated(hasFocus);
            // Ensure stateful background updates when we drive activated.
            if (v.getBackground() != null) {
                v.getBackground().setState(v.getDrawableState());
            }
            holder.indicator.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE);

            // Keep focused card fully visible (avoid partial cut on the left).
            if (hasFocus) {
                if (listener != null) listener.onCardFocused(card);
                holder.icon.setColorFilter(colorAccent);
                View parent = (View) v.getParent();
                if (parent instanceof RecyclerView) {
                    int pos = holder.getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        ((RecyclerView) parent).smoothScrollToPosition(pos);
                    }
                }
            } else {
                holder.icon.setColorFilter(colorSecondary);
            }
        });
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        if (holder.video != null && liveTvBgPlayer != null) {
            try {
                liveTvBgPlayer.clearVideoSurfaceView(holder.video);
            } catch (Exception ignored) {
            }
        }
    }

    private void bindLiveTvVideo(@NonNull VH holder, LauncherCard card) {
        if (holder.video == null || holder.videoScrim == null) return;

        boolean isLiveTv = card != null && card.getDestination() == NavDestination.LIVE_TV;
        if (!isLiveTv || liveTvBgFailed) {
            holder.video.setVisibility(View.GONE);
            holder.videoScrim.setVisibility(View.GONE);
            if (liveTvBgPlayer != null) {
                try {
                    liveTvBgPlayer.clearVideoSurfaceView(holder.video);
                } catch (Exception ignored) {
                }
            }
            return;
        }

        holder.video.setVisibility(View.VISIBLE);
        holder.videoScrim.setVisibility(View.VISIBLE);

        SimpleExoPlayer p = ensureLiveTvPlayer(holder.itemView.getContext());
        if (p == null) {
            holder.video.setVisibility(View.GONE);
            holder.videoScrim.setVisibility(View.GONE);
            return;
        }

        // Attach after layout pass.
        holder.video.post(() -> {
            if (liveTvBgFailed) return;
            try {
                p.setVideoSurfaceView(holder.video);
                Log.d(TAG, "attached SurfaceView to player");
            } catch (Exception e) {
                Log.w(TAG, "failed attaching SurfaceView", e);
                return;
            }
            try {
                if (!liveTvBgPrepared) {
                    liveTvBgPrepared = true;
                    p.prepare();
                    Log.d(TAG, "prepared bg player after surface attach");
                }
            } catch (Exception e) {
                Log.e(TAG, "failed preparing bg player", e);
                liveTvBgFailed = true;
                return;
            }
            try {
                p.setPlayWhenReady(hostActive);
            } catch (Exception ignored) {
            }
        });
    }

    private static MediaSource buildMediaSource(Context context, Uri uri, DataSource.Factory dataSourceFactory) {
        int type = Util.inferContentType(uri);
        MediaItem item = MediaItem.fromUri(uri);
        if (type == com.google.android.exoplayer2.C.TYPE_HLS) {
            return new HlsMediaSource.Factory(dataSourceFactory).createMediaSource(item);
        }
        return new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item);
    }

    private static Uri getLocalFallbackUri() {
        return RawResourceDataSource.buildRawResourceUri(R.raw.launcher_card_bg);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void switchToLocalFallback(Context app, SimpleExoPlayer p, DataSource.Factory localFactory) {
        if (liveTvBgFallbackToLocal) return;
        liveTvBgFallbackToLocal = true;
        liveTvBgRenderedFirstFrame = false;
        try {
            Uri fallbackUri = getLocalFallbackUri();
            Log.w(TAG, "switching to local fallback uri=" + fallbackUri);
            MediaSource fallbackSource = buildMediaSource(app, fallbackUri, localFactory);
            p.setPlayWhenReady(false);
            p.stop(true);
            p.setMediaSource(fallbackSource);
            // Prepare will be called after surface attach if not prepared yet.
            if (liveTvBgPrepared) {
                p.prepare();
            }
            p.setPlayWhenReady(hostActive);
            int livePos = findLiveTvCardPosition();
            if (livePos >= 0 && livePos < getItemCount()) {
                notifyItemChanged(livePos, PAYLOAD_LIVETV_BG_STATE);
            } else {
                notifyDataSetChanged();
            }
        } catch (Exception e) {
            Log.e(TAG, "failed switching to local fallback", e);
            liveTvBgFailed = true;
        }
    }

    private SimpleExoPlayer ensureLiveTvPlayer(Context context) {
        if (liveTvBgPlayer != null) return liveTvBgPlayer;
        if (context == null) return null;

        try {
            Context app = context.getApplicationContext();

            // Prefer remote URL when configured; fall back to a bundled baseline MP4 if decode/network fails.
            String url = Constants.LAUNCHER_LIVETV_CARD_VIDEO_URL;
            boolean hasRemote = true;

            // Use an SSL-tolerant client for background video: old Android (API 21-22) often lacks
            // trust anchors for modern CAs (e.g. Let's Encrypt). This is decorative video only,
            // so bypassing strict SSL validation is acceptable here.
            DataSource.Factory remoteFactory = new OkHttpDataSourceFactory(buildBgVideoClient(), "MQLTV/1.0");
            String userAgent = Util.getUserAgent(app, "MQLTV");
            DataSource.Factory localFactory = new DefaultDataSourceFactory(app, userAgent);

            Uri initialUri = Uri.parse(url);
            MediaSource mediaSource = buildMediaSource(app, initialUri, remoteFactory);
            Log.d(TAG, "init player uri=" + initialUri + " (remote=" + hasRemote + ")");

            SimpleExoPlayer p = new SimpleExoPlayer.Builder(app).build();
            p.setPlayWhenReady(false);
            p.setVolume(0f);
            p.setRepeatMode(Player.REPEAT_MODE_ALL);
            p.setMediaSource(mediaSource);
            // NOTE: Don't prepare until we have attached a TextureView surface.
            liveTvBgPrepared = false;

            p.addVideoListener(new VideoListener() {
                @Override
                public void onRenderedFirstFrame() {
                    liveTvBgRenderedFirstFrame = true;
                    Log.d(TAG, "rendered first frame");
                }

                @Override
                public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                    Log.d(TAG, "video size " + width + "x" + height + " rot=" + unappliedRotationDegrees);
                }
            });

            p.addListener(new Player.EventListener() {
                @SuppressLint("NotifyDataSetChanged")
                @Override
                public void onPlayerError(@NonNull ExoPlaybackException error) {
                    Log.e(TAG, "bg video player error", error);

                    // If HTTPS failed due to SSL (trust anchor), retry with HTTP before
                    // giving up and switching to local fallback.
                    if (!liveTvBgFallbackToLocal && !liveTvBgHttpRetried
                            && isSslError(error) && url.startsWith("https://")) {
                        liveTvBgHttpRetried = true;
                        String httpUrl = "http://" + url.substring("https://".length());
                        Log.w(TAG, "SSL error; retrying over HTTP: " + httpUrl);
                        try {
                            Uri httpUri = Uri.parse(httpUrl);
                            MediaSource httpSource = buildMediaSource(app, httpUri, remoteFactory);
                            p.setPlayWhenReady(false);
                            p.stop(true);
                            p.setMediaSource(httpSource);
                            if (liveTvBgPrepared) p.prepare();
                            p.setPlayWhenReady(hostActive);
                        } catch (Exception e2) {
                            Log.e(TAG, "HTTP retry failed", e2);
                            switchToLocalFallback(app, p, localFactory);
                        }
                        return;
                    }

                    if (!liveTvBgFallbackToLocal) {
                        switchToLocalFallback(app, p, localFactory);
                        return;
                    }

                    liveTvBgFailed = true;
                    try {
                        p.setPlayWhenReady(false);
                        p.stop(true);
                    } catch (Exception ignored) {
                    }
                    int livePos = findLiveTvCardPosition();
                    if (livePos >= 0 && livePos < getItemCount()) {
                        notifyItemChanged(livePos, PAYLOAD_LIVETV_BG_STATE);
                    } else {
                        notifyDataSetChanged();
                    }
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    Log.d(TAG, "state=" + state + " playWhenReady=" + p.getPlayWhenReady());
                }
            });

            // If remote is configured but never produces frames (codec unsupported, etc), fall back quickly.
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (liveTvBgPlayer != p) return;
                if (liveTvBgFailed || liveTvBgFallbackToLocal) return;
                if (!liveTvBgRenderedFirstFrame) {
                    Log.w(TAG, "no first frame after timeout; fallback to local");
                    switchToLocalFallback(app, p, localFactory);
                }
            }, 6000);

            liveTvBgPlayer = p;
            return p;
        } catch (Exception e) {
            Log.e(TAG, "failed creating bg player", e);
            liveTvBgFailed = true;
            return null;
        }
    }

    /**
     * OkHttpClient for background decorative video that bypasses SSL certificate validation.
     * Old Android (API 21-22) lacks trust anchors for modern CAs; since this is non-sensitive
     * decorative content, strict validation is not required.
     */
    @SuppressLint("TrustAllX509TrustManager")
    private static OkHttpClient buildBgVideoClient() {
        try {
            X509TrustManager trustAll = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{trustAll}, new SecureRandom());
            return new OkHttpClient.Builder()
                    .sslSocketFactory(sc.getSocketFactory(), trustAll)
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build();
        } catch (Exception e) {
            Log.w(TAG, "Failed to build SSL-tolerant client, using default", e);
            return NetworkClient.getClient();
        }
    }

    /** Returns true if the ExoPlaybackException was caused by an SSL handshake failure. */
    private static boolean isSslError(ExoPlaybackException error) {
        Throwable t = error.getCause();
        while (t != null) {
            if (t instanceof javax.net.ssl.SSLHandshakeException
                    || t instanceof java.security.cert.CertificateException
                    || t instanceof java.security.cert.CertPathValidatorException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static StateListDrawable createCardBackground(Context context, LauncherCardStyle style, int radiusDp) {
        int radius = dp(context, radiusDp);

        GradientDrawable focused = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { style.focusTop, style.focusBottom }
        );
        focused.setCornerRadius(radius);

        GradientDrawable activated = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { style.focusTop, style.focusBottom }
        );
        activated.setCornerRadius(radius);

        GradientDrawable normal = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { style.normalTop, style.normalBottom }
        );
        normal.setCornerRadius(radius);

        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_activated }, activated);
        s.addState(new int[] { android.R.attr.state_focused }, focused);
        s.addState(new int[] {}, normal);
        return (StateListDrawable) s.mutate();
    }

    private static int dp(Context context, int dp) {
        float d = context != null ? context.getResources().getDisplayMetrics().density : 1f;
        return Math.max(1, (int) (dp * d + 0.5f));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        final View indicator;
        final SurfaceView video;
        final View videoScrim;

        VH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.launcher_card_icon);
            title = itemView.findViewById(R.id.launcher_card_title);
            subtitle = itemView.findViewById(R.id.launcher_card_subtitle);
            indicator = itemView.findViewById(R.id.launcher_card_indicator);
            video = itemView.findViewById(R.id.launcher_card_video);
            videoScrim = itemView.findViewById(R.id.launcher_card_video_scrim);
        }
    }
}
