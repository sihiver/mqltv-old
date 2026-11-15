package com.mqltv;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App Library Dialog.
 * Shows all launchable applications in a full-screen translucent overlay with 16:9 cards,
 * "DI BERANDA" status badges, direct launch, and CleanYourTV-style glass popup menu on long-press.
 */
public final class AppLibraryDialog {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public interface OnPinnedChangeListener {
        void onPinnedChanged();
    }

    private final Activity activity;
    private final OnPinnedChangeListener pinnedListener;
    private final Runnable onDismissListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Dialog dialog;
    private PopupWindow actionPopup;
    private boolean launchedExternalApp = false;

    public AppLibraryDialog(@NonNull Activity activity, OnPinnedChangeListener pinnedListener) {
        this(activity, pinnedListener, null);
    }

    public AppLibraryDialog(@NonNull Activity activity, OnPinnedChangeListener pinnedListener, Runnable onDismissListener) {
        this.activity = activity;
        this.pinnedListener = pinnedListener;
        this.onDismissListener = onDismissListener;
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    public boolean hasLaunchedExternalApp() {
        return launchedExternalApp;
    }

    public void close() {
        mainHandler.removeCallbacksAndMessages(null);
        if (actionPopup != null && actionPopup.isShowing()) {
            actionPopup.dismiss();
            actionPopup = null;
        }
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private void launchEntry(LauncherAppEntry entry) {
        if (entry == null) return;
        Intent launch = entry.buildLaunchIntent();
        if (launch != null) {
            try {
                launchedExternalApp = true;
                activity.startActivity(launch);
                // Biarkan dialog tetap menutup layar selama transisi window aplikasi tujuan,
                // sehingga tidak berkedip menampilkan Beranda terlebih dahulu.
                mainHandler.postDelayed(this::close, 900);
            } catch (Exception e) {
                launchedExternalApp = false;
                Toast.makeText(activity, "Gagal membuka aplikasi", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void show() {
        if (activity == null || activity.isFinishing()) return;

        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.setOnDismissListener(d -> {
            if (actionPopup != null && actionPopup.isShowing()) {
                actionPopup.dismiss();
                actionPopup = null;
            }
            if (onDismissListener != null) {
                onDismissListener.run();
            }
        });
        dialog.setContentView(R.layout.dialog_app_library);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.CENTER);
            if (window.getDecorView() instanceof ViewGroup) {
                ((ViewGroup) window.getDecorView()).setClipChildren(false);
                ((ViewGroup) window.getDecorView()).setClipToPadding(false);
            }
        }

        View header = dialog.findViewById(R.id.dialog_library_header);
        TextView countText = dialog.findViewById(R.id.dialog_library_count);
        RecyclerView grid = dialog.findViewById(R.id.dialog_library_grid);

        float density = activity.getResources().getDisplayMetrics().density;
        int safePad = Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.072f);
        if (safePad > 0) {
            if (header != null) {
                header.setPadding(safePad, header.getPaddingTop(), safePad, header.getPaddingBottom());
            }
            if (grid != null) {
                grid.setPadding(safePad, grid.getPaddingTop(), safePad, grid.getPaddingBottom());
            }
        }
        int spacing = Math.round(24 * density);

        if (header != null) {
            header.setAlpha(0f);
            header.setTranslationY(18f * density);
            header.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(240)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
        }

        grid.setLayoutManager(new GridLayoutManager(activity, 4) {
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                int top = child.getTop() + rect.top;
                int bottom = top + rect.height();
                int limitBottom = getHeight() - getPaddingBottom();
                int dy;
                if (top < getPaddingTop()) dy = top - getPaddingTop();
                else if (bottom <= getHeight() - getPaddingBottom()) dy = 0;
                else dy = Math.min(top - getPaddingTop(), bottom - limitBottom);
                if (dy == 0) return false;
                parent.smoothScrollBy(0, dy);
                return true;
            }
        });
        grid.setHasFixedSize(true);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        RecyclerView.ItemAnimator animator = grid.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
        TopFade.attach(grid, 44 * density);
        grid.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                       @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                int pos = parent.getChildAdapterPosition(view);
                if (pos == RecyclerView.NO_POSITION) return;
                int column = pos % 4;
                outRect.left = column * spacing / 4;
                outRect.right = spacing - (column + 1) * spacing / 4;
                outRect.top = (int) (10 * density);
                outRect.bottom = (int) (10 * density);
            }
        });

        LibraryAdapter adapter = new LibraryAdapter(grid);
        grid.setAdapter(adapter);

        // prepareEntranceTransition() ala ManjayaDrawer (VerticalGridFragment):
        // sembunyikan anak-anak grid sampai data aplikasi selesai dimuat & siap di-layout.
        adapter.prepareEntranceTransition();

        dialog.show();

        final long showTimeMs = android.os.SystemClock.elapsedRealtime();
        EXECUTOR.execute(() -> {
            PackageManager pm = activity.getPackageManager();
            List<LauncherAppEntry> list = loadAllApps(pm, activity);
            Set<String> pinned = new HashSet<>(PinnedAppsStore.load(activity));
            long elapsed = android.os.SystemClock.elapsedRealtime() - showTimeMs;
            // ManjayaDrawer menggunakan postDelayed 100ms sebelum initAppData + createRows + startEntranceTransition()
            long delayMs = Math.max(0L, 100L - elapsed);

            mainHandler.postDelayed(() -> {
                if (dialog == null || !dialog.isShowing()) return;
                if (countText != null) {
                    countText.setText(String.format(Locale.getDefault(), "· %d Aplikasi", list.size()));
                }
                adapter.setData(list, pinned);
                adapter.startEntranceTransition();
            }, delayMs);
        });
    }

    private static List<LauncherAppEntry> loadAllApps(PackageManager pm, Context ctx) {
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> standard = pm.queryIntentActivities(mainIntent, 0);

        Intent tvIntent = new Intent(Intent.ACTION_MAIN, null);
        tvIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> tv = pm.queryIntentActivities(tvIntent, 0);

        Set<String> seenPackages = new HashSet<>();
        List<LauncherAppEntry> out = new ArrayList<>();

        for (ResolveInfo ri : tv) {
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || ctx.getPackageName().equals(pkg)) continue; // ignore MQLTV launcher itself
            if (seenPackages.add(pkg)) {
                out.add(LauncherAppEntry.fromResolveInfo(ri, pm));
            }
        }

        for (ResolveInfo ri : standard) {
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || ctx.getPackageName().equals(pkg)) continue;
            if (seenPackages.add(pkg)) {
                out.add(LauncherAppEntry.fromResolveInfo(ri, pm));
            }
        }

        final Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(out, (a, b) -> {
            String l1 = a != null && a.label != null ? a.label : "";
            String l2 = b != null && b.label != null ? b.label : "";
            return collator.compare(l1, l2);
        });

        return out;
    }

    private final class LibraryAdapter extends RecyclerView.Adapter<LibraryVH> {

        private static final int NUM_COLUMNS = 4;
        private static final long ENTRANCE_DURATION_MS = 350L; // lb_vertical_grid_entrance_transition.xml (350ms)

        private final RecyclerView grid;
        private final List<LauncherAppEntry> items = new ArrayList<>();
        private final Set<String> pinnedKeys = new HashSet<>();
        private final Set<String> pinnedPackages = new HashSet<>();
        private boolean entranceChildrenHidden = false;

        LibraryAdapter(RecyclerView grid) {
            this.grid = grid;
        }

        /**
         * Setara VerticalGridFragment.prepareEntranceTransition() ->
         * VerticalGridPresenter.setEntranceTransitionState(vh, false) ->
         * VerticalGridView.setChildrenVisibility(View.INVISIBLE).
         */
        void prepareEntranceTransition() {
            entranceChildrenHidden = true;
            for (int i = 0, n = grid.getChildCount(); i < n; i++) {
                View child = grid.getChildAt(i);
                if (child != null) {
                    child.setVisibility(View.INVISIBLE);
                }
            }
        }

        /**
         * Setara VerticalGridFragment.startEntranceTransition() ->
         * BaseFragment.onExecuteEntranceTransition() (OnPreDrawListener) ->
         * lb_vertical_grid_entrance_transition.xml (<slide android:duration="350" android:slideEdge="bottom"/>).
         */
        void startEntranceTransition() {
            if (items.isEmpty()) {
                entranceChildrenHidden = false;
                return;
            }
            final ViewTreeObserver vto = grid.getViewTreeObserver();
            vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (grid.getViewTreeObserver().isAlive()) {
                        grid.getViewTreeObserver().removeOnPreDrawListener(this);
                    }
                    int childCount = grid.getChildCount();
                    if (childCount == 0) {
                        entranceChildrenHidden = false;
                        return true;
                    }

                    // 1. SetEntranceTransitionState(true) -> semua child menjadi VISIBLE
                    entranceChildrenHidden = false;

                    int screenW = activity.getResources().getDisplayMetrics().widthPixels;
                    int screenH = activity.getResources().getDisplayMetrics().heightPixels;
                    int gridW = grid.getWidth() > 0 ? grid.getWidth() : screenW;
                    int gridH = grid.getHeight() > 0 ? grid.getHeight() : screenH;

                    // Slide(Gravity.BOTTOM) / SlideKitkat$4: goneY = view.getTranslationY() + gridHeight
                    float slideDistance = Math.max(gridH, screenH * 0.78f);

                    // Fokuskan kartu pertama seperti VerticalGridView default selection
                    View first = grid.getChildAt(0);
                    if (first != null && !first.hasFocus()) {
                        first.requestFocus();
                    }

                    // SidePropagation(Gravity.BOTTOM): kartu meluncur dari batas bawah layar ke atas
                    // dengan propagasi jarak dari titik fokus atas-tengah (epicenter) dan baris.
                    float epicenterX = gridW * 0.5f;
                    float maxDist = Math.max(1f, gridW + gridH);

                    for (int i = 0; i < childCount; i++) {
                        final View child = grid.getChildAt(i);
                        if (child == null) continue;

                        int pos = grid.getChildAdapterPosition(child);
                        int row = pos != RecyclerView.NO_POSITION ? (pos / NUM_COLUMNS) : (i / NUM_COLUMNS);

                        float childCenterX = (child.getLeft() + child.getRight()) * 0.5f;
                        float childCenterY = (child.getTop() + child.getBottom()) * 0.5f;
                        float dist = Math.abs(childCenterX - epicenterX) + Math.max(0f, childCenterY);

                        // SidePropagation delay (propagationSpeed = 3.0f pada durasi 350ms) + cascade per baris
                        long propDelay = Math.round((ENTRANCE_DURATION_MS / 3.0f) * (dist / maxDist));
                        long delayMs = Math.min(propDelay + (row * 28L), 180L);

                        Object oldAnim = child.getTag(R.id.dialog_library_grid);
                        if (oldAnim instanceof Animator) {
                            ((Animator) oldAnim).cancel();
                        }

                        // Posisi awal di bawah layar (INVISIBLE sampai animasi mulai agar tidak tampil di atas dulu)
                        child.setVisibility(View.VISIBLE);
                        child.setAlpha(1f);
                        child.setTranslationY(slideDistance);

                        ObjectAnimator slideAnim = ObjectAnimator.ofFloat(child, View.TRANSLATION_Y, slideDistance, 0f);
                        slideAnim.setStartDelay(delayMs);
                        slideAnim.setDuration(ENTRANCE_DURATION_MS);
                        // lb_decelerator_4 / DecelerateInterpolator khas Leanback VerticalGrid
                        slideAnim.setInterpolator(new DecelerateInterpolator(2.4f));
                        slideAnim.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                child.setTranslationY(0f);
                                child.setTag(R.id.dialog_library_grid, null);
                            }

                            @Override
                            public void onAnimationCancel(Animator animation) {
                                child.setTranslationY(0f);
                                child.setTag(R.id.dialog_library_grid, null);
                            }
                        });
                        child.setTag(R.id.dialog_library_grid, slideAnim);
                        slideAnim.start();
                    }

                    // Return false agar frame ini digambar ulang dengan translationY = slideDistance
                    return false;
                }
            });
            grid.invalidate();
        }

        private boolean isEntryPinned(LauncherAppEntry entry) {
            if (entry == null || entry.component == null) return false;
            String key = entry.component.flattenToString();
            String pkg = entry.component.getPackageName();
            return pinnedKeys.contains(key) || (pkg != null && pinnedPackages.contains(pkg));
        }

        @SuppressLint("NotifyDataSetChanged")
        public void setData(List<LauncherAppEntry> newItems, Set<String> pinned) {
            items.clear();
            if (newItems != null) items.addAll(newItems);
            pinnedKeys.clear();
            pinnedPackages.clear();
            if (pinned != null) {
                pinnedKeys.addAll(pinned);
                for (String s : pinned) {
                    if (s == null) continue;
                    int slash = s.indexOf('/');
                    String pkg = slash > 0 ? s.substring(0, slash).trim() : s.trim();
                    if (!pkg.isEmpty()) pinnedPackages.add(pkg);
                }
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public LibraryVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dialog_library_app, parent, false);
            return new LibraryVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull LibraryVH holder, int position) {
            LauncherAppEntry entry = items.get(position);
            if (entry == null) return;

            holder.title.setText(entry.label != null ? entry.label : "");

            boolean isPinned = isEntryPinned(entry);
            holder.badge.setVisibility(isPinned ? View.VISIBLE : View.GONE);

            // Card outline & background
            float density = holder.itemView.getResources().getDisplayMetrics().density;
            int radius = Math.round(14 * density);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                holder.tile.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                    }
                });
                holder.tile.setClipToOutline(true);
            }

            int baseColor = LauncherAppsAdapter.colorFromEntry(entry);
            GradientDrawable normalBg = new GradientDrawable();
            normalBg.setCornerRadius(radius);
            normalBg.setColor(Color.argb(45, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));
            normalBg.setStroke(Math.round(1 * density), 0x22FFFFFF);
            holder.tile.setBackground(normalBg);

            // Icon / Banner
            if (entry.banner != null) {
                holder.banner.setImageDrawable(entry.banner);
                holder.banner.setVisibility(View.VISIBLE);
                holder.icon.setVisibility(View.GONE);
            } else {
                holder.banner.setVisibility(View.GONE);
                holder.icon.setVisibility(View.VISIBLE);
                if (entry.icon != null) {
                    holder.icon.setImageDrawable(entry.icon);
                } else {
                    holder.icon.setImageResource(R.mipmap.ic_launcher);
                }
            }

            final boolean[] longPressed = {false};
            final Runnable longPressTrigger = () -> {
                longPressed[0] = true;
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) pos = position;
                View card = holder.tile != null ? holder.tile : holder.itemView;
                showAppOptions(entry, pos, card);
            };

            // Click listener -> Open App
            holder.itemView.setOnClickListener(v -> {
                if (longPressed[0]) {
                    longPressed[0] = false;
                    return;
                }
                launchEntry(entry);
            });

            // Long click -> Show CleanYourTV glass popup menu next to card
            holder.itemView.setOnLongClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) pos = position;
                View card = holder.tile != null ? holder.tile : holder.itemView;
                showAppOptions(entry, pos, card);
                return true;
            });

            holder.itemView.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (event.getRepeatCount() == 0) {
                            longPressed[0] = false;
                            v.removeCallbacks(longPressTrigger);
                            v.postDelayed(longPressTrigger, 550);
                        }
                    } else if (event.getAction() == KeyEvent.ACTION_UP) {
                        v.removeCallbacks(longPressTrigger);
                        if (longPressed[0]) {
                            longPressed[0] = false;
                            return true;
                        }
                    }
                } else if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    // Navigasi D-pad wrap-around antar baris ala ManjayaDrawer (AppsGridFragment$7)
                    int pos = holder.getBindingAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) pos = position;
                    int size = items.size();
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (pos % NUM_COLUMNS == NUM_COLUMNS - 1 && pos < size - 1) {
                            int target = pos + 1;
                            grid.smoothScrollToPosition(target);
                            grid.post(() -> {
                                RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(target);
                                if (vh != null && vh.itemView != null) vh.itemView.requestFocus();
                            });
                            return true;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        if (pos / NUM_COLUMNS > 0 && pos % NUM_COLUMNS == 0) {
                            int target = pos - 1;
                            grid.smoothScrollToPosition(target);
                            grid.post(() -> {
                                RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(target);
                                if (vh != null && vh.itemView != null) vh.itemView.requestFocus();
                            });
                            return true;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        int rem = size % NUM_COLUMNS;
                        int lastRowStart = (size / NUM_COLUMNS) * NUM_COLUMNS;
                        if (rem > 0 && pos >= lastRowStart - NUM_COLUMNS && pos < lastRowStart && (pos % NUM_COLUMNS) >= rem) {
                            int target = size - 1;
                            grid.smoothScrollToPosition(target);
                            grid.post(() -> {
                                RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(target);
                                if (vh != null && vh.itemView != null) vh.itemView.requestFocus();
                            });
                            return true;
                        }
                    }
                }
                return false;
            });

            // Focus listener -> Scale animation & glowing stroke (FocusHighlight.ZOOM_FACTOR_MEDIUM)
            GradientDrawable focusStroke = new GradientDrawable();
            focusStroke.setCornerRadius(radius);
            focusStroke.setColor(Color.TRANSPARENT);
            focusStroke.setStroke(Math.round(2.5f * density), 0xFFFFFFFF);
            if (holder.tile instanceof android.widget.FrameLayout) {
                ((android.widget.FrameLayout) holder.tile).setForeground(null);
            }

            holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                float s = hasFocus ? 1.08f : 1.0f;
                float z = hasFocus ? 8 * density : 0f;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    v.animate()
                            .scaleX(s)
                            .scaleY(s)
                            .translationZ(z)
                            .setDuration(150)
                            .setInterpolator(new DecelerateInterpolator(1.7f))
                            .start();
                } else {
                    v.animate()
                            .scaleX(s)
                            .scaleY(s)
                            .setDuration(150)
                            .setInterpolator(new DecelerateInterpolator(1.7f))
                            .start();
                }
                if (hasFocus) {
                    GradientDrawable focusBg = new GradientDrawable();
                    focusBg.setCornerRadius(radius);
                    focusBg.setColor(Color.argb(85, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));
                    focusBg.setStroke(Math.round(2.5f * density), 0xFFFFFFFF);
                    holder.tile.setBackground(focusBg);
                    if (holder.tile instanceof android.widget.FrameLayout) {
                        ((android.widget.FrameLayout) holder.tile).setForeground(focusStroke);
                    }
                    holder.title.setSelected(true); // enable marquee if long
                } else {
                    holder.tile.setBackground(normalBg);
                    if (holder.tile instanceof android.widget.FrameLayout) {
                        ((android.widget.FrameLayout) holder.tile).setForeground(null);
                    }
                    holder.title.setSelected(false);
                }
            });

            // Setara VerticalGridView.setChildrenVisibility: INVISIBLE saat prepareEntranceTransition,
            // lalu VISIBLE ketika startEntranceTransition berjalan.
            Object runningAnim = holder.itemView.getTag(R.id.dialog_library_grid);
            if (!(runningAnim instanceof Animator) || !((Animator) runningAnim).isRunning()) {
                holder.itemView.setTranslationY(0f);
                holder.itemView.setAlpha(1f);
            }
            holder.itemView.setVisibility(entranceChildrenHidden ? View.INVISIBLE : View.VISIBLE);
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull LibraryVH holder) {
            super.onViewDetachedFromWindow(holder);
            Object runningAnim = holder.itemView.getTag(R.id.dialog_library_grid);
            if (runningAnim instanceof Animator) {
                ((Animator) runningAnim).cancel();
                holder.itemView.setTag(R.id.dialog_library_grid, null);
            }
            holder.itemView.animate().cancel();
            holder.itemView.setAlpha(1f);
            holder.itemView.setTranslationY(0f);
        }

        @Override
        public void onBindViewHolder(@NonNull LibraryVH holder, int position, @NonNull List<Object> payloads) {
            if (!payloads.isEmpty()) {
                Object p = payloads.get(0);
                if ("ADD_TO_HOME".equals(p)) {
                    animateAddToHome(holder);
                    return;
                } else if ("REMOVE_FROM_HOME".equals(p)) {
                    animateRemoveFromHome(holder);
                    return;
                }
            }
            onBindViewHolder(holder, position);
        }

        private void animateAddToHome(LibraryVH holder) {
            if (holder == null) return;

            // 1. Tampilkan center circle loading di tengah ubin kartu
            if (holder.centerLoading != null) {
                holder.centerLoading.setVisibility(View.VISIBLE);
                holder.centerLoading.setAlpha(0f);
                holder.centerLoading.setScaleX(0.7f);
                holder.centerLoading.setScaleY(0.7f);
                holder.centerLoading.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(160)
                        .start();
            }

            // Redupkan icon/banner sejenak agar circle loading di tengah terlihat jelas
            if (holder.icon != null && holder.icon.getVisibility() == View.VISIBLE) {
                holder.icon.animate().alpha(0.35f).setDuration(160).start();
            }
            if (holder.banner != null && holder.banner.getVisibility() == View.VISIBLE) {
                holder.banner.animate().alpha(0.35f).setDuration(160).start();
            }

            // 2. Setelah loading berputar sejenak (~400ms), sembunyikan loading dan tampilkan badge 'DI BERANDA'
            holder.itemView.postDelayed(() -> {
                if (holder.centerLoading != null) {
                    holder.centerLoading.animate()
                            .alpha(0f)
                            .scaleX(0.7f)
                            .scaleY(0.7f)
                            .setDuration(160)
                            .withEndAction(() -> {
                                holder.centerLoading.setVisibility(View.GONE);
                                holder.centerLoading.setAlpha(1f);
                                holder.centerLoading.setScaleX(1f);
                                holder.centerLoading.setScaleY(1f);
                            })
                            .start();
                }

                // Pulihkan opasitas icon/banner
                if (holder.icon != null && holder.icon.getVisibility() == View.VISIBLE) {
                    holder.icon.animate().alpha(1f).setDuration(200).start();
                }
                if (holder.banner != null && holder.banner.getVisibility() == View.VISIBLE) {
                    holder.banner.animate().alpha(1f).setDuration(200).start();
                }

                // Munculkan badge 'DI BERANDA'
                if (holder.badge != null) {
                    holder.badge.setVisibility(View.VISIBLE);
                    holder.badge.setAlpha(0f);
                    holder.badge.setScaleX(0.5f);
                    holder.badge.setScaleY(0.5f);
                    holder.badge.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(220)
                            .setInterpolator(new DecelerateInterpolator(1.6f))
                            .start();
                }
            }, 420);
        }

        private void animateRemoveFromHome(LibraryVH holder) {
            if (holder == null) return;

            // 1. Tampilkan center circle loading sejenak
            if (holder.centerLoading != null) {
                holder.centerLoading.setVisibility(View.VISIBLE);
                holder.centerLoading.setAlpha(0f);
                holder.centerLoading.setScaleX(0.7f);
                holder.centerLoading.setScaleY(0.7f);
                holder.centerLoading.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(160)
                        .start();
            }

            if (holder.icon != null && holder.icon.getVisibility() == View.VISIBLE) {
                holder.icon.animate().alpha(0.35f).setDuration(160).start();
            }
            if (holder.banner != null && holder.banner.getVisibility() == View.VISIBLE) {
                holder.banner.animate().alpha(0.35f).setDuration(160).start();
            }

            // 2. Sembunyikan badge 'DI BERANDA'
            holder.itemView.postDelayed(() -> {
                if (holder.centerLoading != null) {
                    holder.centerLoading.animate()
                            .alpha(0f)
                            .scaleX(0.7f)
                            .scaleY(0.7f)
                            .setDuration(160)
                            .withEndAction(() -> {
                                holder.centerLoading.setVisibility(View.GONE);
                                holder.centerLoading.setAlpha(1f);
                                holder.centerLoading.setScaleX(1f);
                                holder.centerLoading.setScaleY(1f);
                            })
                            .start();
                }

                if (holder.icon != null && holder.icon.getVisibility() == View.VISIBLE) {
                    holder.icon.animate().alpha(1f).setDuration(200).start();
                }
                if (holder.banner != null && holder.banner.getVisibility() == View.VISIBLE) {
                    holder.banner.animate().alpha(1f).setDuration(200).start();
                }

                if (holder.badge != null) {
                    holder.badge.animate()
                            .alpha(0f)
                            .scaleX(0.5f)
                            .scaleY(0.5f)
                            .setDuration(180)
                            .setInterpolator(new AccelerateInterpolator(1.5f))
                            .withEndAction(() -> {
                                holder.badge.setVisibility(View.GONE);
                                holder.badge.setAlpha(1f);
                                holder.badge.setScaleX(1f);
                                holder.badge.setScaleY(1f);
                            })
                            .start();
                }
            }, 350);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private void showAppOptions(LauncherAppEntry entry, int position, View cardView) {
            if (entry == null || entry.component == null || cardView == null || dialog == null) return;
            if (actionPopup != null && actionPopup.isShowing()) {
                actionPopup.dismiss();
                return;
            }

            final String key = entry.component.flattenToString();
            final String pkg = entry.component.getPackageName();
            final boolean isPinned = isEntryPinned(entry);

            float density = activity.getResources().getDisplayMetrics().density;
            View decor = dialog.getWindow() != null ? dialog.getWindow().getDecorView() : cardView.getRootView();
            ViewGroup root = (decor instanceof ViewGroup) ? (ViewGroup) decor : null;
            View panel = LayoutInflater.from(activity).inflate(R.layout.popup_inputs, root, false);
            LinearLayout rows = panel.findViewById(R.id.rows);

            int minWidth = Math.round(220f * density);
            final PopupWindow popup = new PopupWindow(panel, minWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true);
            popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popup.setOutsideTouchable(true);
            popup.setAnimationStyle(0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                popup.setElevation(12f * density);
            }
            popup.setOnDismissListener(() -> {
                if (actionPopup == popup) {
                    actionPopup = null;
                }
                if (grid != null && dialog != null && dialog.isShowing()) {
                    grid.post(() -> {
                        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
                        if (vh != null && vh.itemView != null) {
                            vh.itemView.requestFocus();
                        }
                    });
                }
            });
            actionPopup = popup;

            // 1. Buka
            addOptionRow(rows, panel, popup, "Buka", () -> launchEntry(entry));

            // 2. Tambah ke Beranda / Hapus dari Beranda
            addOptionRow(rows, panel, popup, isPinned ? "Hapus dari Beranda" : "Tambah ke Beranda", () -> {
                if (isPinned) {
                    PinnedAppsStore.remove(activity, key);
                    pinnedKeys.remove(key);
                    if (pkg != null) pinnedPackages.remove(pkg);
                    notifyItemChanged(position, "REMOVE_FROM_HOME");
                    Toast.makeText(activity, (entry.label != null ? entry.label : "Aplikasi") + " dihapus dari Beranda", Toast.LENGTH_SHORT).show();
                } else {
                    PinnedAppsStore.add(activity, key);
                    pinnedKeys.add(key);
                    if (pkg != null) pinnedPackages.add(pkg);
                    notifyItemChanged(position, "ADD_TO_HOME");
                    Toast.makeText(activity, (entry.label != null ? entry.label : "Aplikasi") + " ditambahkan ke Beranda", Toast.LENGTH_SHORT).show();
                }
                if (pinnedListener != null) pinnedListener.onPinnedChanged();
            });

            // 3. Info aplikasi
            addOptionRow(rows, panel, popup, "Info aplikasi", () -> {
                if (pkg != null) {
                    SystemSettings.appInfo(activity, pkg);
                }
            });

            panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int w = Math.max(minWidth, panel.getMeasuredWidth());
            int h = panel.getMeasuredHeight();
            popup.setWidth(w);

            int[] loc = new int[2];
            cardView.getLocationOnScreen(loc);
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            int screenH = activity.getResources().getDisplayMetrics().heightPixels;
            int pad24 = Math.round(24f * density);
            int pad10 = Math.round(10f * density);

            int x = loc[0] + (cardView.getWidth() - w) / 2;
            x = Math.max(pad24, Math.min(x, screenW - w - pad24));
            int yBelow = loc[1] + cardView.getHeight() + pad10;
            int yAbove = loc[1] - h - pad10;
            boolean above = yBelow + h > screenH - pad24 && yAbove >= pad24;
            int y = above ? yAbove : yBelow;

            panel.setAlpha(0f);
            panel.setPivotY(above ? h : 0f);
            panel.setScaleY(0.92f);
            panel.setTranslationY(above ? Math.round(8f * density) : -Math.round(8f * density));
            popup.showAtLocation(decor, Gravity.NO_GRAVITY, x, y);
            panel.animate()
                    .alpha(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();

            rows.post(() -> {
                if (rows.getChildCount() > 0) {
                    rows.getChildAt(0).requestFocus();
                }
            });
        }

        private void addOptionRow(LinearLayout rows, View panel, PopupWindow popup, String labelText, Runnable action) {
            View row = LayoutInflater.from(rows.getContext()).inflate(R.layout.item_input, rows, false);
            View icon = row.findViewById(R.id.icon);
            View dot = row.findViewById(R.id.dot);
            View status = row.findViewById(R.id.status);
            if (icon != null) icon.setVisibility(View.GONE);
            if (dot != null) dot.setVisibility(View.GONE);
            if (status != null) status.setVisibility(View.GONE);

            TextView titleView = row.findViewById(R.id.title);
            if (titleView != null) titleView.setText(labelText);

            Runnable executeCloseAndAction = () -> {
                panel.animate()
                        .alpha(0f)
                        .scaleX(0.92f)
                        .scaleY(0.92f)
                        .setDuration(120)
                        .setInterpolator(new AccelerateInterpolator(1.4f))
                        .withEndAction(() -> {
                            try {
                                popup.dismiss();
                            } catch (Exception ignored) {}
                            if (action != null) action.run();
                        })
                        .start();
            };

            row.setOnClickListener(v -> executeCloseAndAction.run());

            final boolean[] pressed = {false};
            row.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                        pressed[0] = true;
                    } else if (event.getAction() == KeyEvent.ACTION_UP && pressed[0]) {
                        pressed[0] = false;
                        executeCloseAndAction.run();
                        return true;
                    }
                }
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int idx = rows.indexOfChild(v);
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && idx == 0) return true;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && idx == rows.getChildCount() - 1) return true;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
                return false;
            });

            rows.addView(row);
        }
    }

    private static final class LibraryVH extends RecyclerView.ViewHolder {
        final View tile;
        final ImageView banner;
        final ImageView icon;
        final TextView badge;
        final TextView title;
        final ProgressBar centerLoading;

        LibraryVH(@NonNull View itemView) {
            super(itemView);
            tile = itemView.findViewById(R.id.library_app_tile);
            banner = itemView.findViewById(R.id.library_app_banner);
            icon = itemView.findViewById(R.id.library_app_icon);
            badge = itemView.findViewById(R.id.library_app_home_badge);
            title = itemView.findViewById(R.id.library_app_title);
            centerLoading = itemView.findViewById(R.id.library_app_center_loading);
        }
    }
}
