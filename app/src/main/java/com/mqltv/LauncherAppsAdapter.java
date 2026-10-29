package com.mqltv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class LauncherAppsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int VIEW_TYPE_APP = 0;
    public static final int VIEW_TYPE_LIBRARY = 1;

    public interface Listener {
        void onAppClicked(LauncherAppEntry entry);
        void onAppLongPressed(LauncherAppEntry entry);
        void onAddClicked();
        default void onAppFocused(LauncherAppEntry entry, int position, View cardView) {}
        default void onLibraryClicked() {}
        default void onLibraryFocused(View view) {}
        default void onLibraryUp() {}
        default void onFocusLibraryRequested() {}
    }

    private final List<LauncherAppEntry> items = new ArrayList<>();
    private final Listener listener;

    public LauncherAppsAdapter(Listener listener) {
        this.listener = listener;
    }

    public int getAppsCount() {
        return items.size();
    }

    public boolean isLibraryAt(int position) {
        return position >= items.size();
    }

    @Override
    public int getItemCount() {
        return items.isEmpty() ? 0 : items.size() + 1;
    }

    @Override
    public int getItemViewType(int position) {
        return isLibraryAt(position) ? VIEW_TYPE_LIBRARY : VIEW_TYPE_APP;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void submit(List<LauncherAppEntry> list) {
        if (list == null) list = new ArrayList<>();
        
        // Prevent flicker and focus loss if the content is exactly the same
        boolean same = items.size() == list.size();
        if (same) {
            for (int i = 0; i < items.size(); i++) {
                LauncherAppEntry oldItem = items.get(i);
                LauncherAppEntry newItem = list.get(i);
                String oldLabel = oldItem != null ? oldItem.label : "";
                String newLabel = newItem != null ? newItem.label : "";
                String oldSub = oldItem != null ? oldItem.customSubtitle : "";
                String newSub = newItem != null ? newItem.customSubtitle : "";
                boolean oldLive = oldItem != null && oldItem.isLiveTv;
                boolean newLive = newItem != null && newItem.isLiveTv;
                if (!oldLabel.equals(newLabel) || oldLive != newLive || (oldSub != null && !oldSub.equals(newSub))) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return;

        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_LIBRARY) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.row_app_library, parent, false);
            return new LibraryVH(v);
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_launcher_app, parent, false);
        return new VH(v);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder rawHolder, int position) {
        if (getItemViewType(position) == VIEW_TYPE_LIBRARY) {
            LibraryVH holder = (LibraryVH) rawHolder;
            if (holder.btnLibrary != null) {
                holder.btnLibrary.setOnClickListener(v -> {
                    if (listener != null) listener.onLibraryClicked();
                });
                holder.btnLibrary.setOnFocusChangeListener((v, hasFocus) -> {
                    float density = v.getResources().getDisplayMetrics().density;
                    float s = hasFocus ? 1.08f : 1.0f;
                    float z = hasFocus ? 6 * density : 0f;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        v.animate().scaleX(s).scaleY(s).translationZ(z).setDuration(160).start();
                    } else {
                        v.animate().scaleX(s).scaleY(s).setDuration(160).start();
                    }
                    if (hasFocus && listener != null) {
                        listener.onLibraryFocused(v);
                    }
                    if (v instanceof TextView) {
                        int tintColor = hasFocus ? 0xFF111111 : 0xFFFFFFFF;
                        for (Drawable d : ((TextView) v).getCompoundDrawables()) {
                            if (d != null) {
                                androidx.core.graphics.drawable.DrawableCompat.setTint(d.mutate(), tintColor);
                            }
                        }
                    }
                });
                holder.btnLibrary.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                    if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                        if (listener != null) listener.onLibraryUp();
                        return true;
                    }
                    return keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                            || keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                            || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
                });
            }
            return;
        }

        VH holder = (VH) rawHolder;
        LauncherAppEntry e = items.get(position);
        Context ctx = holder.itemView.getContext();
        View targetCard = holder.tile != null ? holder.tile : holder.itemView;
        boolean isRow1 = position < 4;

        if (holder.icon != null) holder.icon.setContentDescription(e.label);
        if (holder.title != null) holder.title.setText(e.label);

        if (isRow1) {
            // Row 1: Title besar di atas, tanpa bottom title
            if (holder.bottomTitle != null) {
                holder.bottomTitle.setVisibility(View.GONE);
            }
            if (holder.centerIcon != null) {
                holder.centerIcon.setVisibility(View.GONE);
            }

            if (e.isLiveTv) {
                if (holder.banner != null) holder.banner.setVisibility(View.GONE);
                if (holder.contentGroup != null) holder.contentGroup.setVisibility(View.VISIBLE);
                if (holder.icon != null) {
                    if (e.icon != null) holder.icon.setImageDrawable(e.icon);
                    else holder.icon.setImageResource(R.mipmap.ic_launcher);
                    holder.icon.clearColorFilter();
                }
                if (holder.title != null) {
                    holder.title.setText(e.label != null ? e.label : "Live TV");
                    holder.title.setTextColor(Color.WHITE);
                }
                if (holder.subtitle != null) {
                    holder.subtitle.setText(e.customSubtitle != null ? e.customSubtitle : "+5000 Saluran");
                    holder.subtitle.setVisibility(View.VISIBLE);
                    holder.subtitle.setTextColor(0xFFD9E2F2);
                }
                targetCard.setBackground(createTileBackground(ctx, 0xFF7C3AED));
            } else if (e.banner != null) {
                if (holder.banner != null) {
                    holder.banner.setImageDrawable(e.banner);
                    holder.banner.setVisibility(View.VISIBLE);
                }
                if (holder.contentGroup != null) holder.contentGroup.setVisibility(View.GONE);
                targetCard.setBackground(createTileBackground(ctx, 0xFF2A2E35));
            } else {
                if (holder.banner != null) holder.banner.setVisibility(View.GONE);
                if (holder.contentGroup != null) holder.contentGroup.setVisibility(View.VISIBLE);
                if (holder.icon != null) {
                    holder.icon.setImageDrawable(e.icon);
                    holder.icon.clearColorFilter();
                }
                if (holder.title != null) {
                    holder.title.setText(e.label);
                    holder.title.setTextColor(Color.WHITE);
                }
                if (holder.subtitle != null) {
                    holder.subtitle.setVisibility(View.GONE);
                }
                targetCard.setBackground(createTileBackground(ctx, colorFromEntry(e)));
            }
        } else {
            // Row 2+: Title pindah ke bawah app dengan posisi center font di kecilkan
            if (holder.bottomTitle != null) {
                holder.bottomTitle.setVisibility(View.VISIBLE);
                holder.bottomTitle.setText(e.isAddButton ? "Tambah" : (e.label != null ? e.label : ""));
                holder.bottomTitle.setTextColor(0xB3FFFFFF);
            }
            if (holder.contentGroup != null) {
                holder.contentGroup.setVisibility(View.GONE);
            }

            if (e.isAddButton) {
                if (holder.banner != null) holder.banner.setVisibility(View.GONE);
                if (holder.centerIcon != null) {
                    holder.centerIcon.setVisibility(View.VISIBLE);
                    holder.centerIcon.setImageResource(android.R.drawable.ic_input_add);
                    holder.centerIcon.setColorFilter(Color.WHITE);
                }
                targetCard.setBackground(createTileBackground(ctx, 0xFF4D5B6A));
            } else if (e.banner != null) {
                if (holder.centerIcon != null) holder.centerIcon.setVisibility(View.GONE);
                if (holder.banner != null) {
                    holder.banner.setImageDrawable(e.banner);
                    holder.banner.setVisibility(View.VISIBLE);
                }
                targetCard.setBackground(createTileBackground(ctx, 0xFF2A2E35));
            } else {
                if (holder.banner != null) holder.banner.setVisibility(View.GONE);
                if (holder.centerIcon != null) {
                    holder.centerIcon.setVisibility(View.VISIBLE);
                    holder.centerIcon.setImageDrawable(e.icon);
                    holder.centerIcon.clearColorFilter();
                }
                targetCard.setBackground(createTileBackground(ctx, colorFromEntry(e)));
            }
        }

        if (targetCard instanceof android.widget.FrameLayout) {
            ((android.widget.FrameLayout) targetCard).setForeground(createTileForeground(ctx));
        }

        holder.itemView.setOnClickListener(v -> {
            if (e.isAddButton) {
                if (listener != null) listener.onAddClicked();
            } else {
                if (listener != null) listener.onAppClicked(e);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (e.isAddButton || e.isLiveTv) return true;
            if (listener != null) listener.onAppLongPressed(e);
            return true;
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            float s = hasFocus ? 1.10f : 1.0f;
            float z = hasFocus ? 14f : 0f;
            View animTarget = holder.tile != null ? holder.tile : v;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                animTarget.animate().scaleX(s).scaleY(s).translationZ(z).setDuration(160)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start();
            } else {
                animTarget.animate().scaleX(s).scaleY(s).setDuration(160)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start();
            }
            if (holder.bottomTitle != null) {
                holder.bottomTitle.setTextColor(hasFocus ? 0xFFFFFFFF : 0xB3FFFFFF);
                holder.bottomTitle.setSelected(hasFocus);
            }

            if (hasFocus && listener != null) {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    listener.onAppFocused(e, pos, animTarget);
                }
            }
        });

        holder.itemView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return false;
            int count = items.size();
            int lastRowStart = ((count - 1) / 4) * 4;
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && pos >= lastRowStart) {
                if (listener != null) listener.onFocusLibraryRequested();
                return true;
            }
            return false;
        });
    }

    static class LibraryVH extends RecyclerView.ViewHolder {
        final View btnLibrary;

        LibraryVH(@NonNull View itemView) {
            super(itemView);
            btnLibrary = itemView.findViewById(R.id.btnLibrary);
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final View tile;
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        final ImageView banner;
        final View contentGroup;
        final ImageView centerIcon;
        final TextView bottomTitle;

        VH(@NonNull View itemView) {
            super(itemView);
            tile = itemView.findViewById(R.id.launcher_app_tile);
            icon = itemView.findViewById(R.id.launcher_app_icon);
            title = itemView.findViewById(R.id.launcher_app_title);
            subtitle = itemView.findViewById(R.id.launcher_app_subtitle);
            banner = itemView.findViewById(R.id.launcher_app_banner);
            contentGroup = itemView.findViewById(R.id.launcher_app_content);
            centerIcon = itemView.findViewById(R.id.launcher_app_center_icon);
            bottomTitle = itemView.findViewById(R.id.launcher_app_bottom_title);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                final int radius = dp(itemView.getContext(), 14);
                ViewOutlineProvider roundOutline = new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        if (view.getWidth() > 0 && view.getHeight() > 0) {
                            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                        } else {
                            outline.setEmpty();
                        }
                    }
                };
                if (tile != null) {
                    tile.setOutlineProvider(roundOutline);
                    tile.setClipToOutline(true);
                }

                if (banner != null) {
                    banner.setOutlineProvider(roundOutline);
                    banner.setClipToOutline(true);
                }

                final int iconRadius = dp(itemView.getContext(), 10);
                ViewOutlineProvider iconOutline = new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        if (view.getWidth() > 0 && view.getHeight() > 0) {
                            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), iconRadius);
                        } else {
                            outline.setEmpty();
                        }
                    }
                };
                if (icon != null) {
                    icon.setOutlineProvider(iconOutline);
                    icon.setClipToOutline(true);
                }
                if (centerIcon != null) {
                    centerIcon.setOutlineProvider(iconOutline);
                    centerIcon.setClipToOutline(true);
                }
            }
        }
    }

    private static Drawable createTileBackground(Context ctx, int baseColor) {
        int radius = dp(ctx, 16);
        int darkTop = blend(0xFF242F40, baseColor, 0.25f);
        int darkBottom = blend(0xFF131924, baseColor, 0.15f);
        GradientDrawable normal = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{darkTop, darkBottom});
        normal.setCornerRadius(radius);
        normal.setStroke(dp(ctx, 1), 0x30FFFFFF);
        return normal;
    }

    private static Drawable createTileForeground(Context ctx) {
        return new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);
    }

    private static int blend(int bg, int fg, float ratio) {
        int r = (int) (Color.red(bg) * (1f - ratio) + Color.red(fg) * ratio);
        int g = (int) (Color.green(bg) * (1f - ratio) + Color.green(fg) * ratio);
        int b = (int) (Color.blue(bg) * (1f - ratio) + Color.blue(fg) * ratio);
        return Color.rgb(Math.max(0, Math.min(255, r)), Math.max(0, Math.min(255, g)), Math.max(0, Math.min(255, b)));
    }

    public static int colorFromEntry(LauncherAppEntry e) {
        if (e != null && e.isLiveTv) {
            return 0xFF7C3AED; // tvOS purple / violet accent
        }
        if (e != null && e.isAddButton) {
            return 0xFF3B82F6; // Clean blue
        }
        if (e != null && e.label != null) {
            String lbl = e.label.toLowerCase(java.util.Locale.ROOT);
            if (lbl.contains("youtube") || lbl.contains("smarttube") || lbl.contains("netflix")) {
                return 0xFFEF4444; // Red
            } else if (lbl.contains("spotify") || lbl.contains("music") || lbl.contains("audio") || lbl.contains("radio")) {
                return 0xFF10B981; // Green
            } else if (lbl.contains("disney") || lbl.contains("prime") || lbl.contains("vidio")) {
                return 0xFF0284C7; // Blue
            } else if (lbl.contains("game") || lbl.contains("play")) {
                return 0xFFF59E0B; // Amber
            }
        }
        String key = null;
        if (e != null && e.component != null) {
            key = e.component.getPackageName();
        }
        if (key == null) key = e != null ? e.label : "app";
        return colorFromString(key);
    }

    private static boolean isColorDark(int color) {
        double darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        return darkness >= 0.45;
    }

    private static int colorFromString(String s) {
        int h = s != null ? s.hashCode() : 0;
        float hue = (h & 0xFFFF) % 360f;
        float[] hsv = new float[] { hue, 0.55f, 0.92f };
        return Color.HSVToColor(hsv);
    }

    private static int lighten(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        r = clamp((int) (r + (255 - r) * (float) 0.1));
        g = clamp((int) (g + (255 - g) * (float) 0.1));
        b = clamp((int) (b + (255 - b) * (float) 0.1));
        return Color.rgb(r, g, b);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    private static int dp(Context ctx, int dp) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return Math.round(dp * d);
    }

    private static String resolveSubtitle(Context ctx, LauncherAppEntry e) {
        if (e != null && e.customSubtitle != null) return e.customSubtitle;
        if (e == null || e.component == null) return null;
        String pkg = e.component.getPackageName();

        PackageManager pm = ctx.getPackageManager();
        String installer = null;
        try {
            installer = pm.getInstallerPackageName(pkg);
        } catch (Exception ignored) {
        }
        if (installer == null || installer.trim().isEmpty()) return null;

        // Prefer showing a friendly store/app label (e.g. "Google Play").
        try {
            ApplicationInfo ai = pm.getApplicationInfo(installer, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            return label.toString();
        } catch (Exception ignored) {
            return installer;
        }
    }
}
