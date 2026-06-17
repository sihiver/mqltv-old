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
import android.view.TextureView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

@OptIn(markerClass = UnstableApi.class)
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

    private ExoPlayer liveTvBgPlayer;
    private boolean liveTvBgPlayerInitializing;
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

    /**
     * Call this as early as possible (e.g. from Activity.onCreate) to pre-warm the
     * SSL client and ExoPlayer on a deferred main-thread post so the launcher UI
     * appears immediately without waiting for network/codec initialization.
     */
    public void preWarm(Context context) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (liveTvBgPlayer == null && !liveTvBgFailed && !liveTvBgPlayerInitializing) {
                ensureLiveTvPlayer(context.getApplicationContext());
            }
        });
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
            liveTvBgPlayerInitializing = false;
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
        if (card == null) return RecyclerView.NO_ID;

        // Stable ID must be unique per logical item, not only per destination type.
        // Using destination ordinal alone collides when multiple cards share destination.
        long h = 1125899906842597L; // prime seed

        NavDestination dest = card.getDestination();
        h = 31L * h + (dest != null ? dest.name().hashCode() : 0);

        String title = card.getTitle();
        h = 31L * h + (title != null ? title.hashCode() : 0);

        String subtitle = card.getSubtitle();
        h = 31L * h + (subtitle != null ? subtitle.hashCode() : 0);

        h = 31L * h + card.getIconRes();
        return h;
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
                liveTvBgPlayer.clearVideoTextureView(holder.video);
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
                    liveTvBgPlayer.clearVideoTextureView(holder.video);
                } catch (Exception ignored) {
                }
            }
            return;
        }

        holder.video.setVisibility(View.VISIBLE);
        holder.videoScrim.setVisibility(View.VISIBLE);

        // Defer player attach to after layout so the launcher UI is drawn first.
        // If the player isn't ready yet (still initializing via preWarm), retry shortly.
        holder.video.post(() -> attachPlayerToSurface(holder));
    }

    private static MediaSource buildMediaSource(Context context, Uri uri, boolean local) {
        String userAgent = Util.getUserAgent(context, "MQLTV");
        if (local) {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent(userAgent);
            DefaultDataSource.Factory ds = new DefaultDataSource.Factory(context, http);
            return new ProgressiveMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(uri));
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent(userAgent);
        MediaItem item = MediaItem.fromUri(uri);
        int type = Util.inferContentType(uri);
        if (type == C.CONTENT_TYPE_HLS) {
            return new HlsMediaSource.Factory(http).createMediaSource(item);
        }
        return new ProgressiveMediaSource.Factory(http).createMediaSource(item);
    }

    private static Uri getLocalFallbackUri(Context app) {
        return Uri.parse("android.resource://" + app.getPackageName() + "/" + R.raw.launcher_card_bg);
    }

    @SuppressLint("NotifyDataSetChanged")
    private static final long PLAYER_ATTACH_RETRY_MS = 300L;

    private void attachPlayerToSurface(@NonNull VH holder) {
        if (holder.video == null || holder.video.getWindowToken() == null) return;
        if (liveTvBgFailed) {
            holder.video.setVisibility(View.GONE);
            if (holder.videoScrim != null) holder.videoScrim.setVisibility(View.GONE);
            return;
        }

        ExoPlayer p = liveTvBgPlayer;

        if (p == null) {
            // Player not ready yet (still initializing); retry after a short delay.
            holder.video.postDelayed(() -> attachPlayerToSurface(holder), PLAYER_ATTACH_RETRY_MS);
            return;
        }

        try {
            p.setVideoTextureView(holder.video);
            Log.d(TAG, "attached TextureView to player");
        } catch (Exception e) {
            Log.w(TAG, "failed attaching TextureView", e);
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
    }

    private void switchToLocalFallback(Context app, ExoPlayer p) {
        if (liveTvBgFallbackToLocal) return;
        liveTvBgFallbackToLocal = true;
        liveTvBgRenderedFirstFrame = false;
        try {
            Uri fallbackUri = getLocalFallbackUri(app);
            Log.w(TAG, "switching to local fallback uri=" + fallbackUri);
            MediaSource fallbackSource = buildMediaSource(app, fallbackUri, true);
            p.setPlayWhenReady(false);
            p.stop();
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

    private ExoPlayer ensureLiveTvPlayer(Context context) {
        if (liveTvBgPlayer != null) return liveTvBgPlayer;
        if (context == null) return null;
        if (liveTvBgPlayerInitializing) return null;
        liveTvBgPlayerInitializing = true;

        try {
            Context app = context.getApplicationContext();
            String url = Constants.LAUNCHER_LIVETV_CARD_VIDEO_URL;

            Uri initialUri = Uri.parse(url);
            MediaSource mediaSource = buildMediaSource(app, initialUri, false);
            Log.d(TAG, "init player uri=" + initialUri);

            ExoPlayer p = new ExoPlayer.Builder(app).build();
            p.setPlayWhenReady(false);
            p.setVolume(0f);
            p.setRepeatMode(Player.REPEAT_MODE_ALL);
            p.setMediaSource(mediaSource);
            liveTvBgPrepared = false;

            p.addListener(new Player.Listener() {
                @SuppressLint("NotifyDataSetChanged")
                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    Log.e(TAG, "bg video player error", error);

                    if (!liveTvBgFallbackToLocal && !liveTvBgHttpRetried
                            && isSslError(error) && url.startsWith("https://")) {
                        liveTvBgHttpRetried = true;
                        String httpUrl = "http://" + url.substring("https://".length());
                        Log.w(TAG, "SSL error; retrying over HTTP: " + httpUrl);
                        try {
                            Uri httpUri = Uri.parse(httpUrl);
                            MediaSource httpSource = buildMediaSource(app, httpUri, false);
                            p.setPlayWhenReady(false);
                            p.stop();
                            p.setMediaSource(httpSource);
                            if (liveTvBgPrepared) p.prepare();
                            p.setPlayWhenReady(hostActive);
                        } catch (Exception e2) {
                            Log.e(TAG, "HTTP retry failed", e2);
                            switchToLocalFallback(app, p);
                        }
                        return;
                    }

                    if (!liveTvBgFallbackToLocal) {
                        switchToLocalFallback(app, p);
                        return;
                    }

                    liveTvBgFailed = true;
                    try {
                        p.setPlayWhenReady(false);
                        p.stop();
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

                @Override
                public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                    if (videoSize.width > 0) {
                        liveTvBgRenderedFirstFrame = true;
                        Log.d(TAG, "video size " + videoSize.width + "x" + videoSize.height);
                    }
                }
            });

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (liveTvBgPlayer != p) return;
                if (liveTvBgFailed || liveTvBgFallbackToLocal) return;
                if (!liveTvBgRenderedFirstFrame) {
                    Log.w(TAG, "no first frame after timeout; fallback to local");
                    switchToLocalFallback(app, p);
                }
            }, 6000);

            liveTvBgPlayer = p;
            liveTvBgPlayerInitializing = false;
            return p;
        } catch (Exception e) {
            Log.e(TAG, "failed creating bg player", e);
            liveTvBgFailed = true;
            liveTvBgPlayerInitializing = false;
            return null;
        }
    }

    private static boolean isSslError(PlaybackException error) {
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
        final TextureView video;
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
