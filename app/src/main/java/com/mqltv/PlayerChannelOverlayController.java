package com.mqltv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class PlayerChannelOverlayController {

    public interface PlayerLauncher {
        void play(Channel channel);
    }

    private static final String TAG = "PlayerOverlay";

    private static final ExecutorService IMAGE_EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
            return value.getByteCount();
        }
    };

    private final Activity activity;
    private final Context appContext;
    private final PlayerLauncher launcher;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private FrameLayout root;
    private View header;
    private TextView categoryText;
    private RecyclerView list;

    private ImageView infoLogoImg;
    private TextView infoLogoText;
    private TextView infoNumber;
    private TextView infoTitle;
    private ProgressBar infoProgress;
    private TextView infoNowTime;
    private TextView infoNowTitle;
    private TextView infoNextTime;
    private TextView infoNextTitle;

    private TextView typedNumberView;

    private final ChannelAdapter adapter = new ChannelAdapter(this);

    private volatile List<Channel> allChannels = Collections.emptyList();
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private Runnable pendingAfterLoad = null;
    private final List<String> categories = new ArrayList<>();
    private final Map<String, List<Channel>> byCategory = new LinkedHashMap<>();
    private int categoryIndex = 0;

    private String currentUrl;

    private static final long NUMBER_COMMIT_DELAY_MS = 1200L;
    private static final long NUMBER_HIDE_DELAY_MS = 1800L;
    private static final long DPAD_REPEAT_THROTTLE_MS = 150L;
    private static final long FOCUS_RETRY_DELAY_MS = 45L;
    private static final int MAX_FOCUS_RETRIES = 12;
    
    private final StringBuilder numberBuffer = new StringBuilder(4);
    private final Runnable commitNumberRunnable = this::commitPendingChannelNumber;
    private final Runnable hideNumberRunnable = this::hideTypedNumber;
    
    // Focus restoration state to handle rapid navigation
    private int pendingFocusPosition = RecyclerView.NO_POSITION;
    private int focusedAdapterPosition = RecyclerView.NO_POSITION;
    private int focusRetryCount = 0;
    private Runnable pendingFocusRunnable = null;
    private long lastDirectionalNavAtMs = 0L;
    private int lastDirectionalNavKey = KeyEvent.KEYCODE_UNKNOWN;

    public PlayerChannelOverlayController(@NonNull Activity activity, @NonNull PlayerLauncher launcher) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();
        this.launcher = launcher;

        root = activity.findViewById(R.id.player_channel_overlay_root);
        header = activity.findViewById(R.id.player_channel_overlay_header);
        categoryText = activity.findViewById(R.id.player_channel_overlay_category);
        list = activity.findViewById(R.id.player_channel_overlay_list);

        View infoLogo = activity.findViewById(R.id.player_channel_overlay_info_logo);
        if (infoLogo != null) {
            infoLogoImg = infoLogo.findViewById(R.id.player_channel_overlay_info_logo_img);
            infoLogoText = infoLogo.findViewById(R.id.player_channel_overlay_info_logo_text);
        }
        infoNumber = activity.findViewById(R.id.player_channel_overlay_info_number);
        infoTitle = activity.findViewById(R.id.player_channel_overlay_info_title);
        infoProgress = activity.findViewById(R.id.player_channel_overlay_info_progress);
        infoNowTime = activity.findViewById(R.id.player_channel_overlay_info_now_time);
        infoNowTitle = activity.findViewById(R.id.player_channel_overlay_info_now_title);
        infoNextTime = activity.findViewById(R.id.player_channel_overlay_info_next_time);
        infoNextTitle = activity.findViewById(R.id.player_channel_overlay_info_next_title);

        typedNumberView = activity.findViewById(R.id.player_overlay_typed_number);

        if (list != null) {
            list.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.VERTICAL, false));
            list.setAdapter(adapter);
            list.setHasFixedSize(false);
            list.setItemViewCacheSize(18);
            list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            list.setFocusable(true);
            list.setFocusableInTouchMode(true);
        }

        if (header != null) {
            header.setOnClickListener(v -> {
                // no-op; header is mainly for category navigation
            });
            header.setFocusable(true);
            header.setFocusableInTouchMode(true);
        }

        if (categoryText != null) {
            categoryText.setFocusable(true);
            categoryText.setFocusableInTouchMode(true);
        }

        View left = activity.findViewById(R.id.player_channel_overlay_arrow_left);
        View right = activity.findViewById(R.id.player_channel_overlay_arrow_right);
        if (left != null) left.setOnClickListener(v -> prevCategory());
        if (right != null) right.setOnClickListener(v -> nextCategory());

        if (root != null) {
            root.setOnClickListener(v -> hide());
        }

        adapter.setListener(new ChannelAdapter.Listener() {
            @Override
            public void onChannelFocused(Channel c, int absoluteIndex) {
                bindInfo(c, absoluteIndex);
            }

            @Override
            public void onChannelClicked(Channel c) {
                if (c == null) return;
                hide();
                launcher.play(c);
            }
        });

        // Pre-load channel list in background immediately so overlay opens instantly.
        startLoad(null);
    }

    public void setCurrentChannel(String url) {
        currentUrl = url;
        adapter.setCurrentUrl(url);
        if (isVisible()) {
            focusCurrentChannel();
        }
    }

    public boolean isVisible() {
        return root != null && root.getVisibility() == View.VISIBLE;
    }

    public void show() {
        if (root == null) return;
        root.setVisibility(View.VISIBLE);
        root.bringToFront();
        root.requestFocus();

        ensureLoadedThenApply();
    }

    public void hide() {
        if (root == null) return;
        root.setVisibility(View.GONE);
        
        // Clean up any pending focus operations
        if (pendingFocusRunnable != null) {
            MAIN.removeCallbacks(pendingFocusRunnable);
            pendingFocusRunnable = null;
        }
        pendingFocusPosition = RecyclerView.NO_POSITION;
        focusRetryCount = 0;
    }

    public void destroy() {
        try {
            worker.shutdownNow();
        } catch (Throwable ignored) {
        }

        try {
            MAIN.removeCallbacks(commitNumberRunnable);
            MAIN.removeCallbacks(hideNumberRunnable);
            if (pendingFocusRunnable != null) {
                MAIN.removeCallbacks(pendingFocusRunnable);
                pendingFocusRunnable = null;
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean handleKeyEvent(@NonNull KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

        int key = event.getKeyCode();
        int repeat = event.getRepeatCount();

        int digit = digitFromKeyCode(key);
        if (digit >= 0) {
            // Numeric channel select: show typed digits on the player (top-right) without opening the channel list.
            if (isVisible()) hide();
            onDigitPressed(digit);
            return true;
        }

        if (key == KeyEvent.KEYCODE_DEL || key == KeyEvent.KEYCODE_FORWARD_DEL) {
            if (numberBuffer.length() > 0) {
                numberBuffer.deleteCharAt(numberBuffer.length() - 1);
                updateTypedNumberUi();
                MAIN.removeCallbacks(commitNumberRunnable);
                MAIN.removeCallbacks(hideNumberRunnable);
                if (numberBuffer.length() > 0) {
                    MAIN.postDelayed(commitNumberRunnable, NUMBER_COMMIT_DELAY_MS);
                    MAIN.postDelayed(hideNumberRunnable, NUMBER_HIDE_DELAY_MS);
                } else {
                    hideTypedNumber();
                }
                return true;
            }
        }

        if (key == KeyEvent.KEYCODE_BACK) {
            if (numberBuffer.length() > 0) {
                clearTypedNumber();
                return true;
            }
            if (isVisible()) {
                hide();
                return true;
            }
            return false;
        }

        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            if (numberBuffer.length() > 0) {
                commitPendingChannelNumber();
                return true;
            }
            if (!isVisible()) {
                show();
                return true;
            }

            // If a row is focused, treat OK as "play".
            if (list != null) {
                View f = activity.getCurrentFocus();
                int pos = getAdapterPositionForView(f);
                if (pos != RecyclerView.NO_POSITION) {
                    Channel c = adapter.getItem(pos);
                    if (c != null) {
                        hide();
                        launcher.play(c);
                    }
                }
            }
            return true;
        }

        if (!isVisible()) return false;

        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (shouldThrottleDirectionalNav(key, repeat, event.getEventTime())) return true;
            // Boundary: when already at the first row, move focus to header instead of forcing
            // a focus/scroll operation that can crash on some TV devices.
            int curPos = getFocusedListAdapterPosition();
            if (curPos == 0) {
                if (header != null && header.requestFocus()) return true;
                if (categoryText != null && categoryText.requestFocus()) return true;
                if (list != null) list.requestFocus();
                return true;
            }
            moveFocusByDelta(-1);
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (shouldThrottleDirectionalNav(key, repeat, event.getEventTime())) return true;
            moveFocusByDelta(1);
            return true;
        }

        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (shouldThrottleDirectionalNav(key, repeat, event.getEventTime())) return true;
            prevCategory();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (shouldThrottleDirectionalNav(key, repeat, event.getEventTime())) return true;
            nextCategory();
            return true;
        }

        return false;
    }

    private int getFocusedListAdapterPosition() {
        View focused = activity.getCurrentFocus();
        return getAdapterPositionForView(focused);
    }

    private int getAdapterPositionForView(View v) {
        if (list == null || v == null) return RecyclerView.NO_POSITION;
        // v can be a nested child (TextView/ImageView) inside a RecyclerView row.
        // getChildAdapterPosition() expects the direct itemView with RecyclerView.LayoutParams.
        View itemView = list.findContainingItemView(v);
        if (itemView == null) return RecyclerView.NO_POSITION;
        return list.getChildAdapterPosition(itemView);
    }

    private void onDigitPressed(int digit) {
        if (digit < 0 || digit > 9) return;

        if (numberBuffer.length() >= 4) {
            // Keep only the last 3 digits then append.
            numberBuffer.delete(0, numberBuffer.length() - 3);
        }
        numberBuffer.append(digit);
        updateTypedNumberUi();

        MAIN.removeCallbacks(commitNumberRunnable);
        MAIN.removeCallbacks(hideNumberRunnable);
        MAIN.postDelayed(commitNumberRunnable, NUMBER_COMMIT_DELAY_MS);
        MAIN.postDelayed(hideNumberRunnable, NUMBER_HIDE_DELAY_MS);
    }

    private void commitPendingChannelNumber() {
        if (numberBuffer.length() == 0) return;

        final int channelNumber;
        try {
            channelNumber = Integer.parseInt(numberBuffer.toString());
        } catch (Throwable ignored) {
            clearTypedNumber();
            return;
        }

        numberBuffer.setLength(0);
        MAIN.removeCallbacks(commitNumberRunnable);

        // Keep the number visible briefly after committing.
        MAIN.removeCallbacks(hideNumberRunnable);
        MAIN.postDelayed(hideNumberRunnable, 650L);

        if (channelNumber <= 0) return;

        ensureLoadedThen(() -> playChannelByNumber(channelNumber));
    }

    private void ensureLoadedThen(@NonNull Runnable action) {
        if (!allChannels.isEmpty()) {
            action.run();
            return;
        }
        startLoad(action);
    }

    private void playChannelByNumber(int channelNumber) {
        int idx = channelNumber - 1;

        // Use the channel list of the currently active category (not the global allChannels list),
        // so pressing "5" plays the 5th channel within the active category, not the 5th globally.
        List<Channel> activeList = currentCategoryChannels();
        if (activeList == null || activeList.isEmpty()) {
            activeList = allChannels;
        }

        if (activeList == null || idx < 0 || idx >= activeList.size()) return;
        Channel c = activeList.get(idx);
        if (c == null) return;

        hide();
        launcher.play(c);
    }

    /** Returns the channel list for the currently selected category. */
    private List<Channel> currentCategoryChannels() {
        if (categories.isEmpty() || categoryIndex < 0 || categoryIndex >= categories.size()) {
            return null;
        }
        return byCategory.get(categories.get(categoryIndex));
    }

    private void updateTypedNumberUi() {
        if (typedNumberView == null) return;
        if (numberBuffer.length() <= 0) {
            typedNumberView.setVisibility(View.GONE);
            return;
        }
        typedNumberView.setText(numberBuffer.toString());
        typedNumberView.setVisibility(View.VISIBLE);
        typedNumberView.bringToFront();
    }

    private void hideTypedNumber() {
        if (typedNumberView == null) return;
        typedNumberView.setVisibility(View.GONE);
    }

    private void clearTypedNumber() {
        numberBuffer.setLength(0);
        MAIN.removeCallbacks(commitNumberRunnable);
        MAIN.removeCallbacks(hideNumberRunnable);
        hideTypedNumber();
    }

    private boolean shouldThrottleDirectionalNav(int key, int repeat, long eventTime) {
        if (repeat <= 0) {
            lastDirectionalNavKey = key;
            lastDirectionalNavAtMs = eventTime;
            return false;
        }

        if (key != lastDirectionalNavKey) {
            lastDirectionalNavKey = key;
            lastDirectionalNavAtMs = eventTime;
            return false;
        }

        if (eventTime - lastDirectionalNavAtMs < DPAD_REPEAT_THROTTLE_MS) {
            return true;
        }

        lastDirectionalNavAtMs = eventTime;
        return false;
    }

    private static int digitFromKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_NUMPAD_0:
                return 0;
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_NUMPAD_1:
                return 1;
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_NUMPAD_2:
                return 2;
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_NUMPAD_3:
                return 3;
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_NUMPAD_4:
                return 4;
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_NUMPAD_5:
                return 5;
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_NUMPAD_6:
                return 6;
            case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_NUMPAD_7:
                return 7;
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_NUMPAD_8:
                return 8;
            case KeyEvent.KEYCODE_9:
            case KeyEvent.KEYCODE_NUMPAD_9:
                return 9;
            default:
                return -1;
        }
    }

    private void ensureLoadedThenApply() {
        if (!allChannels.isEmpty() && !categories.isEmpty()) {
            applyCategory(categoryIndex);
            focusCurrentChannel();
            return;
        }
        // Data is being pre-loaded or not started yet; startLoad will apply when done.
        startLoad(null);
    }

    /**
     * Single entry point for background channel loading.
     * Guards against concurrent loads with {@link #isLoading}.
     * When data is ready: applies category if overlay is visible, then runs {@code onDone} (if any).
     */
    private void startLoad(@Nullable Runnable onDone) {
        if (!allChannels.isEmpty()) {
            // Already loaded.
            if (onDone != null) onDone.run();
            return;
        }
        if (!isLoading.compareAndSet(false, true)) {
            // Another load is already in progress; queue the callback for when it finishes.
            if (onDone != null) {
                final Runnable prev = pendingAfterLoad;
                pendingAfterLoad = () -> {
                    if (prev != null) prev.run();
                    onDone.run();
                };
            }
            return;
        }

        // Show loading hint while fetching.
        if (categoryText != null) categoryText.setText("Memuat...");

        final Runnable callback = onDone;
        worker.execute(() -> {
            List<Channel> loaded = loadChannels(appContext);
            final List<Channel> loadedFinal = (loaded != null) ? loaded : Collections.emptyList();
            final CategoryState state = buildCategories(loadedFinal);

            MAIN.post(() -> {
                isLoading.set(false);
                allChannels = loadedFinal;
                categories.clear();
                categories.addAll(state.labels);
                byCategory.clear();
                byCategory.putAll(state.map);

                // Always pick the initial category so categoryIndex is ready
                // when show() is called later, even if overlay is not yet open.
                int idx = pickInitialCategoryIndex(state, currentUrl);
                categoryIndex = (idx >= 0) ? idx : 0;

                if (isVisible()) {
                    applyCategory(categoryIndex);
                    focusCurrentChannel();
                }

                if (callback != null) callback.run();

                // Flush any callbacks that were queued while loading.
                Runnable queued = pendingAfterLoad;
                pendingAfterLoad = null;
                if (queued != null) queued.run();
            });
        });
    }

    private static List<Channel> loadChannels(Context context) {
        PlaylistRepository repo = new PlaylistRepository();
        String[] urls = AuthPrefs.getPlaylistUrls(context);
        List<Channel> channels = repo.loadFromUrls(context, urls);

        boolean hasServerPlaylist = false;
        try {
            hasServerPlaylist = !AuthPrefs.getPlaylistUrl(context).trim().isEmpty();
        } catch (Throwable ignored) {
        }

        if ((channels == null || channels.isEmpty()) && !hasServerPlaylist) {
            channels = repo.loadDefault(context);
        }

        return channels != null ? channels : Collections.emptyList();
    }

    private void applyCategory(int index) {
        if (categories.isEmpty()) {
            adapter.submit(Collections.emptyList());
            if (categoryText != null) categoryText.setText("Category");
            return;
        }

        if (index < 0) index = 0;
        if (index >= categories.size()) index = categories.size() - 1;
        categoryIndex = index;

        String label = categories.get(index);
        if (categoryText != null) categoryText.setText(label);

        // Sync selected category back to LiveTvFragment SharedPreferences,
        // so next time the overlay opens (or LiveTvFragment resumes) the tab matches.
        appContext.getSharedPreferences(LiveTvFragment.PREFS_LIVETV_SYNC, Context.MODE_PRIVATE)
                  .edit().putString(LiveTvFragment.KEY_LAST_TAB_LABEL, label).apply();

        List<Channel> listForCat = byCategory.get(label);
        if (listForCat == null) listForCat = Collections.emptyList();
        adapter.submit(listForCat);

        // Update activated states.
        adapter.setCurrentUrl(currentUrl);

        // Default info binding.
        Channel c = pickChannelToBind(listForCat, currentUrl);
        if (c != null) {
            int abs = findAbsoluteIndex(allChannels, c);
            bindInfo(c, abs);
        }
    }

    private void prevCategory() {
        if (categories.isEmpty()) return;
        int next = categoryIndex - 1;
        if (next < 0) next = categories.size() - 1;
        applyCategory(next);
        focusCurrentChannel();
    }

    private void nextCategory() {
        if (categories.isEmpty()) return;
        int next = categoryIndex + 1;
        if (next >= categories.size()) next = 0;
        applyCategory(next);
        focusCurrentChannel();
    }

    private void focusCurrentChannel() {
        if (list == null) return;

        int target = adapter.findPositionByUrl(currentUrl);
        if (target < 0) target = 0;

        focusAdapterPosition(target);
    }

    private void moveFocusByDelta(int delta) {
        if (list == null) return;

        int count = adapter.getItemCount();
        if (count <= 0) return;

        // Prefer moving the actual focus highlight (best UX) and only scroll when needed.
        View focused = activity.getCurrentFocus();
        // If the RecyclerView itself is focused, try to focus the current/first row first.
        if (focused == list) {
            int startPos = focusedAdapterPosition;
            if (startPos == RecyclerView.NO_POSITION) startPos = adapter.findPositionByUrl(currentUrl);
            if (startPos == RecyclerView.NO_POSITION) startPos = 0;
            focusAdapterPosition(startPos);
            return;
        }
        if (focused != null) {
            View directNext = focused.focusSearch(delta > 0 ? View.FOCUS_DOWN : View.FOCUS_UP);
            if (directNext != null && directNext != focused && directNext.requestFocus()) {
                int pos = getAdapterPositionForView(directNext);
                if (pos != RecyclerView.NO_POSITION) focusedAdapterPosition = pos;
                pendingFocusPosition = RecyclerView.NO_POSITION;
                if (pendingFocusRunnable != null) {
                    MAIN.removeCallbacks(pendingFocusRunnable);
                    pendingFocusRunnable = null;
                }
                return;
            }
        }

        int current = RecyclerView.NO_POSITION;
        if (focused != null) current = getAdapterPositionForView(focused);
        if (current == RecyclerView.NO_POSITION) {
            current = focusedAdapterPosition;
        }
        if (current == RecyclerView.NO_POSITION) {
            current = adapter.findPositionByUrl(currentUrl);
        }
        if (current == RecyclerView.NO_POSITION) {
            current = 0;
        }

        int next = current + delta;
        if (next < 0) next = 0;
        if (next >= count) next = count - 1;

        focusAdapterPosition(next);
    }

    private void focusAdapterPosition(int target) {
        if (list == null) return;

        int count = adapter.getItemCount();
        if (count <= 0) return;

        if (target < 0) target = 0;
        if (target >= count) target = count - 1;

        if (pendingFocusRunnable != null) {
            MAIN.removeCallbacks(pendingFocusRunnable);
            pendingFocusRunnable = null;
        }

        pendingFocusPosition = target;
        focusedAdapterPosition = target;
        focusRetryCount = 0;

        // Don't pin the row to the top; let RecyclerView keep a natural scroll feel.
        list.scrollToPosition(target);

        final int pos = target;
        list.post(() -> requestFocusForPosition(pos));
    }

    private void requestFocusForPosition(int pos) {
        if (list == null) return;
        if (pos != pendingFocusPosition) return;

        RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(pos);
        if (vh != null && vh.itemView != null && vh.itemView.requestFocus()) {
            focusRetryCount = 0;
            pendingFocusPosition = RecyclerView.NO_POSITION;
            pendingFocusRunnable = null;
            focusedAdapterPosition = pos;
            return;
        }

        attemptFocusRetry(pos);
    }
    
    private void attemptFocusRetry(int pos) {
        if (list == null || pos != pendingFocusPosition) return;

        if (focusRetryCount >= MAX_FOCUS_RETRIES) {
            // Give up and focus the RecyclerView itself as last resort
            if (list != null) {
                list.requestFocus();
            }
            pendingFocusPosition = RecyclerView.NO_POSITION;
            return;
        }
        
        focusRetryCount++;
        long delay = FOCUS_RETRY_DELAY_MS + (focusRetryCount * 10L);
        
        pendingFocusRunnable = () -> {
            if (list == null || pos != pendingFocusPosition) return;
            RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(pos);
            if (vh != null && vh.itemView != null && vh.itemView.requestFocus()) {
                focusRetryCount = 0;
                pendingFocusPosition = RecyclerView.NO_POSITION;
                pendingFocusRunnable = null;
                focusedAdapterPosition = pos;
            } else {
                // Still not bound, retry again
                attemptFocusRetry(pos);
            }
        };
        
        MAIN.postDelayed(pendingFocusRunnable, delay);
    }

    private void bindInfo(Channel c, int absoluteIndex) {
        if (c == null) return;

        if (infoNumber != null) infoNumber.setText(String.valueOf(Math.max(1, absoluteIndex + 1)));
        if (infoTitle != null) infoTitle.setText(c.getTitle() != null ? c.getTitle() : "Channel");

        // Simple schedule placeholders: 30-minute blocks based on local time.
        long now = System.currentTimeMillis();
        long block = 30L * 60L * 1000L;
        long start = (now / block) * block;
        long end = start + block;
        long nextEnd = end + block;

        String nowRange = String.format(Locale.US, "%s - %s", fmtTime(start), fmtTime(end));
        String nextRange = String.format(Locale.US, "%s - %s", fmtTime(end), fmtTime(nextEnd));

        if (infoNowTime != null) infoNowTime.setText(nowRange);
        if (infoNextTime != null) infoNextTime.setText(nextRange);

        String group = c.getGroupTitle();
        String sub = (group == null || group.trim().isEmpty()) ? "LIVE TV" : group.trim();
        if (infoNowTitle != null) infoNowTitle.setText("Now Playing Program");
        if (infoNextTitle != null) infoNextTitle.setText("Next Program");

        if (infoProgress != null) {
            float p = (now - start) / (float) block;
            int progress = (int) (Math.max(0f, Math.min(1f, p)) * 1000f);
            infoProgress.setProgress(progress);
        }

        bindLogoInto(infoLogoImg, infoLogoText, c.getLogoUrl());
    }

    private static String fmtTime(long ms) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(ms);
        int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int m = cal.get(java.util.Calendar.MINUTE);
        return String.format(Locale.US, "%02d:%02d", h, m);
    }

    private static final class CategoryState {
        final List<String> labels;
        final Map<String, List<Channel>> map;
        CategoryState(List<String> labels, Map<String, List<Channel>> map) {
            this.labels = labels;
            this.map = map;
        }
    }

    private static boolean isLocalGroup(String groupTitle) {
        if (groupTitle == null) return false;
        String u = groupTitle.trim().toUpperCase(Locale.US);
        return u.equals("LOCAL") || u.startsWith("LOCAL ") || u.startsWith("LOCAL|")
                || u.contains("|LOCAL") || u.contains("LOCAL|");
    }

    private static CategoryState buildCategories(List<Channel> channels) {
        List<String> labels = new ArrayList<>();
        Map<String, List<Channel>> map = new LinkedHashMap<>();

        String all = "ALL CHANNELS";
        labels.add(all);

        // For ALL CHANNELS: Local channels first, then the rest.
        List<Channel> localAll = new ArrayList<>();
        List<Channel> restAll  = new ArrayList<>();
        if (channels != null) {
            for (Channel c : channels) {
                if (c == null) continue;
                if (isLocalGroup(c.getGroupTitle())) localAll.add(c);
                else restAll.add(c);
            }
        }
        List<Channel> orderedAll = new ArrayList<>(localAll);
        orderedAll.addAll(restAll);
        map.put(all, orderedAll);

        // Collect per-category buckets: Local groups first, then others.
        Map<String, List<Channel>> localGroups = new LinkedHashMap<>();
        Map<String, List<Channel>> otherGroups = new LinkedHashMap<>();

        if (channels != null) {
            for (Channel c : channels) {
                if (c == null) continue;
                String g = c.getGroupTitle();
                g = g == null ? "" : g.trim();
                if (g.isEmpty()) continue;
                if (isLocalGroup(g)) {
                    if (!localGroups.containsKey(g)) localGroups.put(g, new ArrayList<>());
                    localGroups.get(g).add(c);
                } else {
                    if (!otherGroups.containsKey(g)) otherGroups.put(g, new ArrayList<>());
                    otherGroups.get(g).add(c);
                }
            }
        }

        for (Map.Entry<String, List<Channel>> e : localGroups.entrySet()) {
            labels.add(e.getKey());
            map.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, List<Channel>> e : otherGroups.entrySet()) {
            labels.add(e.getKey());
            map.put(e.getKey(), e.getValue());
        }

        return new CategoryState(labels, map);
    }

    private int pickInitialCategoryIndex(CategoryState state, String currentUrl) {
        if (state == null || state.labels == null || state.labels.isEmpty()) return 0;

        // 1. Check the tab that was last active in LiveTvFragment (synced via SharedPreferences).
        String savedTab = appContext
                .getSharedPreferences(LiveTvFragment.PREFS_LIVETV_SYNC, Context.MODE_PRIVATE)
                .getString(LiveTvFragment.KEY_LAST_TAB_LABEL, null);

        if (savedTab != null && !savedTab.trim().isEmpty()) {
            for (int i = 0; i < state.labels.size(); i++) {
                if (savedTab.equalsIgnoreCase(state.labels.get(i))) {
                    return i;
                }
            }
        }

        // 2. Fall back: find the category of the currently playing channel,
        //    skipping "ALL CHANNELS" so the URL is not matched against the catch-all bucket first.
        if (currentUrl != null && !currentUrl.trim().isEmpty()) {
            for (Map.Entry<String, List<Channel>> e : state.map.entrySet()) {
                if ("ALL CHANNELS".equalsIgnoreCase(e.getKey())) continue;
                List<Channel> list = e.getValue();
                if (list == null) continue;
                for (Channel c : list) {
                    if (c != null && currentUrl.equals(c.getUrl())) {
                        int idx = state.labels.indexOf(e.getKey());
                        return idx >= 0 ? idx : 0;
                    }
                }
            }
        }

        return 0;
    }

    private static Channel pickChannelToBind(List<Channel> list, String currentUrl) {
        if (list == null || list.isEmpty()) return null;
        if (currentUrl != null) {
            for (Channel c : list) {
                if (c != null && currentUrl.equals(c.getUrl())) return c;
            }
        }
        return list.get(0);
    }

    private static int findAbsoluteIndex(List<Channel> all, Channel target) {
        if (all == null || all.isEmpty() || target == null) return 0;
        String url = target.getUrl();
        if (url != null) {
            for (int i = 0; i < all.size(); i++) {
                Channel c = all.get(i);
                if (c != null && url.equals(c.getUrl())) return i;
            }
        }
        // Fallback: try by title.
        String t = target.getTitle();
        if (t != null) {
            for (int i = 0; i < all.size(); i++) {
                Channel c = all.get(i);
                if (c != null && t.equals(c.getTitle())) return i;
            }
        }
        return 0;
    }

    private static void bindLogoInto(ImageView imageView, TextView placeholder, String logoUrl) {
        if (imageView == null || placeholder == null) return;

        placeholder.setVisibility(View.VISIBLE);
        imageView.setImageDrawable(null);
        imageView.setVisibility(View.GONE);

        if (TextUtils.isEmpty(logoUrl)) return;

        imageView.setTag(logoUrl);
        Bitmap cached = CACHE.get(logoUrl);
        if (cached != null) {
            placeholder.setVisibility(View.GONE);
            imageView.setImageBitmap(cached);
            imageView.setVisibility(View.VISIBLE);
            return;
        }

        IMAGE_EXECUTOR.execute(() -> {
            Bitmap bmp = downloadBitmap(logoUrl);
            if (bmp != null) CACHE.put(logoUrl, bmp);
            MAIN.post(() -> {
                Object tag = imageView.getTag();
                if (tag != null && tag.equals(logoUrl) && bmp != null) {
                    placeholder.setVisibility(View.GONE);
                    imageView.setImageBitmap(bmp);
                    imageView.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private static Bitmap downloadBitmap(String urlString) {
        Bitmap bmp = downloadBitmapOnce(urlString);
        if (bmp == null && urlString != null && urlString.startsWith("https://")) {
            String httpUrl = "http://" + urlString.substring("https://".length());
            Log.w(TAG, "Retry logo over HTTP: " + httpUrl);
            bmp = downloadBitmapOnce(httpUrl);
        }
        return bmp;
    }

    private static Bitmap downloadBitmapOnce(String urlString) {
        try {
            String host = null;
            try {
                host = Uri.parse(urlString).getHost();
            } catch (Exception ignored) {
            }
            Request request = new Request.Builder()
                    .url(urlString)
                    .header("User-Agent", "MQLTV/1.0")
                    .build();
            try (Response response = NetworkClient.getLogoClient(host).newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.w(TAG, "Logo HTTP " + response.code() + " for " + urlString);
                    return null;
                }
                ResponseBody body = response.body();
                if (body == null) return null;
                byte[] bytes = body.bytes();
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }
        } catch (IOException e) {
            Log.w(TAG, "Logo download failed for " + urlString + ": " + e.getMessage());
            return null;
        }
    }

    private static final class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.VH> {

        interface Listener {
            void onChannelFocused(Channel c, int absoluteIndex);
            void onChannelClicked(Channel c);
        }

        private final PlayerChannelOverlayController controller;
        private Listener listener;
        private final List<Channel> items = new ArrayList<>();
        private String currentUrl;

        ChannelAdapter(PlayerChannelOverlayController controller) {
            this.controller = controller;
        }

        void setListener(Listener l) {
            listener = l;
        }

        void setCurrentUrl(String url) {
            // Only refresh the rows that changed (previous/current) to avoid full
            // adapter refresh which can cause focus loss during rapid navigation.
            String prev = currentUrl;
            if (prev == null ? url == null : prev.equals(url)) {
                currentUrl = url;
                return;
            }
            int prevPos = findPositionByUrl(prev);
            currentUrl = url;
            int newPos = findPositionByUrl(url);
            if (prevPos >= 0) notifyItemChanged(prevPos);
            if (newPos >= 0 && newPos != prevPos) notifyItemChanged(newPos);
        }

        Channel getItem(int position) {
            if (position < 0 || position >= items.size()) return null;
            return items.get(position);
        }

        int findPositionByUrl(String url) {
            if (url == null) return -1;
            for (int i = 0; i < items.size(); i++) {
                Channel c = items.get(i);
                if (c != null && url.equals(c.getUrl())) return i;
            }
            return -1;
        }

        void submit(List<Channel> channels) {
            items.clear();
            if (channels != null) items.addAll(channels);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_player_channel_overlay_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            int adapterPos = holder.getBindingAdapterPosition();
            if (adapterPos == RecyclerView.NO_POSITION || adapterPos >= items.size()) return;

            Channel c = items.get(adapterPos);
            final Channel boundChannel = c;

            holder.number.setText(String.valueOf(adapterPos + 1));
            holder.title.setText(c != null && c.getTitle() != null ? c.getTitle() : "Channel");

            String group = c != null ? c.getGroupTitle() : null;
            group = group == null ? "" : group.trim();
            if (group.isEmpty()) group = "LIVE TV";
            holder.subtitle.setText("Program info " + group);

            boolean isCurrent = c != null && currentUrl != null && currentUrl.equals(c.getUrl());
            holder.itemView.setActivated(isCurrent);

            // Progress indicator: just a subtle animation based on time so it looks alive.
            long now = System.currentTimeMillis();
            int p = (int) ((now / 1000L) % 1000L);
            holder.progress.setProgress(p);

            String logoUrl = c != null ? c.getLogoUrl() : null;
            bindLogoInto(holder.logoImg, holder.logoText, logoUrl);

            holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && listener != null) {
                    int livePos = holder.getBindingAdapterPosition();
                    if (livePos == RecyclerView.NO_POSITION) return;
                    controller.focusedAdapterPosition = livePos;
                    controller.pendingFocusPosition = RecyclerView.NO_POSITION;
                    if (controller.pendingFocusRunnable != null) {
                        MAIN.removeCallbacks(controller.pendingFocusRunnable);
                        controller.pendingFocusRunnable = null;
                    }
                    listener.onChannelFocused(boundChannel, livePos);
                }
            });

            holder.itemView.setOnClickListener(v -> {
                if (listener == null) return;
                int livePos = holder.getBindingAdapterPosition();
                if (livePos == RecyclerView.NO_POSITION) return;
                listener.onChannelClicked(boundChannel);
            });

            holder.itemView.setFocusable(true);
            holder.itemView.setFocusableInTouchMode(true);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView number;
            final ImageView logoImg;
            final TextView logoText;
            final TextView title;
            final TextView subtitle;
            final ProgressBar progress;

            VH(@NonNull View itemView) {
                super(itemView);
                number = itemView.findViewById(R.id.player_channel_row_number);

                View logo = itemView.findViewById(R.id.player_channel_row_logo);
                logoImg = logo != null ? logo.findViewById(R.id.player_channel_row_logo_img) : null;
                logoText = logo != null ? logo.findViewById(R.id.player_channel_row_logo_text) : null;

                title = itemView.findViewById(R.id.player_channel_row_title);
                subtitle = itemView.findViewById(R.id.player_channel_row_subtitle);
                progress = itemView.findViewById(R.id.player_channel_row_progress);
            }
        }
    }
}
