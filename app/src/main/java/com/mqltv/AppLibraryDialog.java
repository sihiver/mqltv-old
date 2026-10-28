package com.mqltv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
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
 * CleanYourTV-style App Library Dialog.
 * Shows all launchable applications in a full-screen translucent overlay with 16:9 cards,
 * "DI BERANDA" status badges, direct launch, and pin/unpin options.
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

    public AppLibraryDialog(@NonNull Activity activity, OnPinnedChangeListener pinnedListener) {
        this(activity, pinnedListener, null);
    }

    public AppLibraryDialog(@NonNull Activity activity, OnPinnedChangeListener pinnedListener, Runnable onDismissListener) {
        this.activity = activity;
        this.pinnedListener = pinnedListener;
        this.onDismissListener = onDismissListener;
    }

    public void show() {
        if (activity == null || activity.isFinishing()) return;

        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.setOnDismissListener(d -> {
            if (onDismissListener != null) {
                onDismissListener.run();
            }
        });
        dialog.setContentView(R.layout.dialog_app_library);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.CENTER);
        }

        TextView countText = dialog.findViewById(R.id.dialog_library_count);
        RecyclerView grid = dialog.findViewById(R.id.dialog_library_grid);

        float density = activity.getResources().getDisplayMetrics().density;
        int spacing = Math.round(18 * density);

        grid.setLayoutManager(new GridLayoutManager(activity, 4));
        grid.setHasFixedSize(true);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
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

        LibraryAdapter adapter = new LibraryAdapter();
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

        private final List<LauncherAppEntry> items = new ArrayList<>();
        private final Set<String> pinnedKeys = new HashSet<>();

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

            holder.tile.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            holder.tile.setClipToOutline(true);

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

            // Long click -> Show action options (Pin / Unpin / App info)
            holder.itemView.setOnLongClickListener(v -> {
                showAppOptions(entry, holder.getBindingAdapterPosition());
                return true;
            });

            // Focus listener -> Scale animation & glowing stroke
            holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).translationZ(8 * density).setDuration(160).start();
                    GradientDrawable focusBg = new GradientDrawable();
                    focusBg.setCornerRadius(radius);
                    focusBg.setColor(Color.argb(85, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));
                    focusBg.setStroke(Math.round(2.5f * density), 0xFFFFFFFF);
                    holder.tile.setBackground(focusBg);
                    holder.title.setSelected(true); // enable marquee if long
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(160).start();
                    holder.tile.setBackground(normalBg);
                    holder.title.setSelected(false);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private void showAppOptions(LauncherAppEntry entry, int position) {
            if (entry == null || entry.component == null) return;
            String key = entry.component.flattenToString();
            boolean isPinned = pinnedKeys.contains(key);

            Dialog actionDialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar);
            actionDialog.setContentView(R.layout.dialog_app_actions);

            ImageView icon = actionDialog.findViewById(R.id.dialog_app_action_icon);
            TextView title = actionDialog.findViewById(R.id.dialog_app_action_title);

            if (title != null) title.setText(entry.label != null ? entry.label : "Aplikasi");
            if (icon != null) {
                if (entry.icon != null) icon.setImageDrawable(entry.icon);
                else icon.setImageResource(R.mipmap.ic_launcher);
            }

            View btnLeft = actionDialog.findViewById(R.id.btn_move_left);
            View btnRemove = actionDialog.findViewById(R.id.btn_remove);
            View btnRight = actionDialog.findViewById(R.id.btn_move_right);

            // Re-purpose action buttons:
            // btnLeft -> "Buka" (Open)
            if (btnLeft instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) btnLeft;
                ImageView iv = vg.getChildAt(0) instanceof ImageView ? (ImageView) vg.getChildAt(0) : null;
                TextView tv = vg.getChildAt(1) instanceof TextView ? (TextView) vg.getChildAt(1) : null;
                if (iv != null) iv.setImageResource(android.R.drawable.ic_media_play);
                if (tv != null) tv.setText("Buka");
                btnLeft.setOnClickListener(v -> {
                    actionDialog.dismiss();
                    Intent launch = entry.buildLaunchIntent();
                    if (launch != null) {
                        try {
                            activity.startActivity(launch);
                            if (dialog != null) dialog.dismiss();
                        } catch (Exception ignored) {}
                    }
                });
            }

            // btnRemove -> "Sematkan" or "Lepas" (Pin / Unpin)
            if (btnRemove instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) btnRemove;
                ImageView iv = vg.getChildAt(0) instanceof ImageView ? (ImageView) vg.getChildAt(0) : null;
                TextView tv = vg.getChildAt(1) instanceof TextView ? (TextView) vg.getChildAt(1) : null;
                if (iv != null) {
                    iv.setImageResource(isPinned ? android.R.drawable.ic_menu_delete : android.R.drawable.ic_input_add);
                    iv.setColorFilter(isPinned ? 0xFFFF5252 : 0xFF10B981);
                }
                if (tv != null) {
                    tv.setText(isPinned ? "Lepas" : "Sematkan");
                    tv.setTextColor(isPinned ? 0xFFFF5252 : 0xFF10B981);
                }
                btnRemove.setOnClickListener(v -> {
                    actionDialog.dismiss();
                    if (isPinned) {
                        PinnedAppsStore.remove(activity, key);
                        pinnedKeys.remove(key);
                        Toast.makeText(activity, "Dihapus dari Beranda", Toast.LENGTH_SHORT).show();
                    } else {
                        PinnedAppsStore.add(activity, key);
                        pinnedKeys.add(key);
                        Toast.makeText(activity, "Disematkan ke Beranda", Toast.LENGTH_SHORT).show();
                    }
                    notifyItemChanged(position);
                    if (pinnedListener != null) pinnedListener.onPinnedChanged();
                });
            }

            // btnRight -> "Info" (App details settings)
            if (btnRight instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) btnRight;
                ImageView iv = vg.getChildAt(0) instanceof ImageView ? (ImageView) vg.getChildAt(0) : null;
                TextView tv = vg.getChildAt(1) instanceof TextView ? (TextView) vg.getChildAt(1) : null;
                if (iv != null) iv.setImageResource(android.R.drawable.ic_menu_info_details);
                if (tv != null) tv.setText("Info");
                btnRight.setOnClickListener(v -> {
                    actionDialog.dismiss();
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + entry.component.getPackageName()));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(activity, "Gagal membuka info aplikasi", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            actionDialog.show();
            if (btnRemove != null) btnRemove.requestFocus();
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
