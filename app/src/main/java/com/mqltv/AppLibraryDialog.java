package com.mqltv;

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
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

    public AppLibraryDialog(@NonNull Activity activity, OnPinnedChangeListener pinnedListener) {
        this(activity, pinnedListener, null);
    }

    public AppLibraryDialog(@NonNull Activity activity, OnPinnedChangeListener pinnedListener, Runnable onDismissListener) {
        this.activity = activity;
        this.pinnedListener = pinnedListener;
        this.onDismissListener = onDismissListener;
    }

    public void close() {
        if (actionPopup != null && actionPopup.isShowing()) {
            actionPopup.dismiss();
            actionPopup = null;
        }
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
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

        TextView countText = dialog.findViewById(R.id.dialog_library_count);
        RecyclerView grid = dialog.findViewById(R.id.dialog_library_grid);

        float density = activity.getResources().getDisplayMetrics().density;
        int spacing = Math.round(18 * density);

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
                outRect.top = (int) (6 * density);
                outRect.bottom = (int) (16 * density);
            }
        });

        LibraryAdapter adapter = new LibraryAdapter(grid);
        grid.setAdapter(adapter);

        dialog.show();

        EXECUTOR.execute(() -> {
            PackageManager pm = activity.getPackageManager();
            List<LauncherAppEntry> list = loadAllApps(pm, activity);
            Set<String> pinned = new HashSet<>(PinnedAppsStore.load(activity));

            mainHandler.post(() -> {
                if (dialog == null || !dialog.isShowing()) return;
                if (countText != null) {
                    countText.setText(String.format(Locale.getDefault(), "· %d Aplikasi", list.size()));
                }
                adapter.setData(list, pinned);
                if (!list.isEmpty()) {
                    grid.post(() -> {
                        View first = grid.getChildAt(0);
                        if (first != null) first.requestFocus();
                    });
                }
            });
        });
    }

    private static List<LauncherAppEntry> loadAllApps(PackageManager pm, Context ctx) {
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> standard = pm.queryIntentActivities(mainIntent, 0);

        Intent tvIntent = new Intent(Intent.ACTION_MAIN, null);
        tvIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> tv = pm.queryIntentActivities(tvIntent, 0);

        Set<String> seen = new HashSet<>();
        List<LauncherAppEntry> out = new ArrayList<>();

        for (ResolveInfo ri : tv) {
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (ctx.getPackageName().equals(pkg)) continue; // ignore MQLTV launcher itself
            String key = pkg + "/" + ri.activityInfo.name;
            if (seen.add(key)) {
                out.add(LauncherAppEntry.fromResolveInfo(ri, pm));
            }
        }

        for (ResolveInfo ri : standard) {
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (ctx.getPackageName().equals(pkg)) continue;
            String key = pkg + "/" + ri.activityInfo.name;
            if (seen.add(key)) {
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

        private final RecyclerView grid;
        private final List<LauncherAppEntry> items = new ArrayList<>();
        private final Set<String> pinnedKeys = new HashSet<>();

        LibraryAdapter(RecyclerView grid) {
            this.grid = grid;
        }

        @SuppressLint("NotifyDataSetChanged")
        public void setData(List<LauncherAppEntry> newItems, Set<String> pinned) {
            items.clear();
            if (newItems != null) items.addAll(newItems);
            pinnedKeys.clear();
            if (pinned != null) pinnedKeys.addAll(pinned);
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

            String key = entry.component != null ? entry.component.flattenToString() : "";
            boolean isPinned = pinnedKeys.contains(key);
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

            // Click listener -> Open App
            holder.itemView.setOnClickListener(v -> {
                Intent launch = entry.buildLaunchIntent();
                if (launch != null) {
                    try {
                        activity.startActivity(launch);
                        if (dialog != null) dialog.dismiss();
                    } catch (Exception e) {
                        Toast.makeText(activity, "Gagal membuka aplikasi", Toast.LENGTH_SHORT).show();
                    }
                }
            });

            // Long click -> Show CleanYourTV glass popup menu next to card
            holder.itemView.setOnLongClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) pos = position;
                View card = holder.tile != null ? holder.tile : holder.itemView;
                showAppOptions(entry, pos, card);
                return true;
            });

            // Focus listener -> Scale animation & glowing stroke
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
                    v.animate().scaleX(s).scaleY(s).translationZ(z).setDuration(160).start();
                } else {
                    v.animate().scaleX(s).scaleY(s).setDuration(160).start();
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
            final boolean isPinned = pinnedKeys.contains(key);

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
            addOptionRow(rows, popup, "Buka", () -> {
                Intent launch = entry.buildLaunchIntent();
                if (launch != null) {
                    try {
                        activity.startActivity(launch);
                        if (dialog != null) dialog.dismiss();
                    } catch (Exception e) {
                        Toast.makeText(activity, "Gagal membuka aplikasi", Toast.LENGTH_SHORT).show();
                    }
                }
            });

            // 2. Tambah ke Beranda / Hapus dari Beranda
            addOptionRow(rows, popup, isPinned ? "Hapus dari Beranda" : "Tambah ke Beranda", () -> {
                if (isPinned) {
                    PinnedAppsStore.remove(activity, key);
                    pinnedKeys.remove(key);
                } else {
                    PinnedAppsStore.add(activity, key);
                    pinnedKeys.add(key);
                }
                notifyItemChanged(position);
                if (pinnedListener != null) pinnedListener.onPinnedChanged();
            });

            // 3. Info aplikasi
            addOptionRow(rows, popup, "Info aplikasi", () -> {
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

        private void addOptionRow(LinearLayout rows, PopupWindow popup, String labelText, Runnable action) {
            View row = LayoutInflater.from(rows.getContext()).inflate(R.layout.item_input, rows, false);
            View icon = row.findViewById(R.id.icon);
            View dot = row.findViewById(R.id.dot);
            View status = row.findViewById(R.id.status);
            if (icon != null) icon.setVisibility(View.GONE);
            if (dot != null) dot.setVisibility(View.GONE);
            if (status != null) status.setVisibility(View.GONE);

            TextView titleView = row.findViewById(R.id.title);
            if (titleView != null) titleView.setText(labelText);

            row.setOnClickListener(v -> {
                popup.dismiss();
                if (action != null) action.run();
            });

            final boolean[] pressed = {false};
            row.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                        pressed[0] = true;
                    } else if (event.getAction() == KeyEvent.ACTION_UP && !pressed[0]) {
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

        LibraryVH(@NonNull View itemView) {
            super(itemView);
            tile = itemView.findViewById(R.id.library_app_tile);
            banner = itemView.findViewById(R.id.library_app_banner);
            icon = itemView.findViewById(R.id.library_app_icon);
            badge = itemView.findViewById(R.id.library_app_home_badge);
            title = itemView.findViewById(R.id.library_app_title);
        }
    }
}
