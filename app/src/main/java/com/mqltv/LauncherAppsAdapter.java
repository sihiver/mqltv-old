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

public class LauncherAppsAdapter extends RecyclerView.Adapter<LauncherAppsAdapter.VH> {

    public interface Listener {
        void onAppClicked(LauncherAppEntry entry);
        void onAppLongPressed(LauncherAppEntry entry);
        void onAddClicked();
        void onAppFocused(LauncherAppEntry entry, int position);
    }

    private final List<LauncherAppEntry> items = new ArrayList<>();
    private final Listener listener;

    public LauncherAppsAdapter(Listener listener) {
        this.listener = listener;
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
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_launcher_app, parent, false);
        return new VH(v);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        LauncherAppEntry e = items.get(position);
        Context ctx = holder.itemView.getContext();

        if (holder.icon != null) holder.icon.setContentDescription(e.label);
        if (holder.title != null) holder.title.setText(e.label);

        if (e.isAddButton) {
            assert holder.icon != null;
            holder.icon.setImageResource(android.R.drawable.ic_input_add);
            holder.icon.setColorFilter(ContextCompat.getColor(holder.itemView.getContext(), R.color.mql_text_primary));

            if (holder.subtitle != null) {
                holder.subtitle.setText("Tambah");
                holder.subtitle.setVisibility(View.VISIBLE);
            }

            if (holder.banner != null) holder.banner.setVisibility(View.GONE);
            if (holder.contentGroup != null) holder.contentGroup.setVisibility(View.VISIBLE);

            if (holder.title != null) holder.title.setTextColor(Color.WHITE);
            if (holder.subtitle != null) holder.subtitle.setTextColor(0xFFD9E2F2);
            holder.itemView.setBackground(createTileBackground(ctx, 0xFF4D5B6A));
        } else if (e.isLiveTv) {
            if (holder.banner != null) holder.banner.setVisibility(View.GONE);
            if (holder.contentGroup != null) holder.contentGroup.setVisibility(View.VISIBLE);

            if (holder.icon != null) {
                if (e.icon != null) {
                    holder.icon.setImageDrawable(e.icon);
                } else {
                    holder.icon.setImageResource(R.mipmap.ic_launcher);
                }
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

            holder.itemView.setBackground(createTileBackground(ctx, 0xFF7C3AED));
        } else {
            if (e.banner != null) {
                if (holder.banner != null) {
                    holder.banner.setImageDrawable(e.banner);
                    holder.banner.setVisibility(View.VISIBLE);
                }
                if (holder.contentGroup != null) holder.contentGroup.setVisibility(View.GONE);
                
                holder.itemView.setBackground(createTileBackground(ctx, 0xFF2A2E35));
                if (holder.title != null) holder.title.setTextColor(Color.WHITE);
                if (holder.subtitle != null) holder.subtitle.setTextColor(0xFFD9E2F2);
            } else {
                if (holder.banner != null) holder.banner.setVisibility(View.GONE);
                if (holder.contentGroup != null) holder.contentGroup.setVisibility(View.VISIBLE);

                assert holder.icon != null;
                holder.icon.setImageDrawable(e.icon);
                holder.icon.clearColorFilter();

                if (holder.subtitle != null) {
                    String sub = resolveSubtitle(ctx, e);
                    if (sub == null || sub.trim().isEmpty()) {
                        holder.subtitle.setVisibility(View.GONE);
                    } else {
                        holder.subtitle.setText(sub);
                        holder.subtitle.setVisibility(View.VISIBLE);
                    }
                }

                int bgColor = colorFromEntry(e);
                holder.itemView.setBackground(createTileBackground(ctx, bgColor));
                boolean isDark = isColorDark(bgColor);
                if (holder.title != null) {
                    holder.title.setTextColor(isDark ? Color.WHITE : 0xFF222222);
                }
                if (holder.subtitle != null) {
                    holder.subtitle.setTextColor(isDark ? 0xFFD9E2F2 : 0xFF555555);
                }
            }
        }

        if (holder.itemView instanceof android.widget.FrameLayout) {
            ((android.widget.FrameLayout) holder.itemView).setForeground(createTileForeground(ctx));
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
            float s = hasFocus ? 1.12f : 1.0f;
            float z = hasFocus ? 14f : 0f;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                v.animate().scaleX(s).scaleY(s).translationZ(z).setDuration(160)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start();
            } else {
                v.animate().scaleX(s).scaleY(s).setDuration(160)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start();
            }
            v.setActivated(hasFocus);
            if (v.getBackground() != null) v.getBackground().setState(v.getDrawableState());
            if (v instanceof android.widget.FrameLayout) {
                Drawable fg = ((android.widget.FrameLayout) v).getForeground();
                if (fg != null) fg.setState(v.getDrawableState());
            }

            if (hasFocus && listener != null) {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    listener.onAppFocused(e, pos);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        final ImageView banner;
        final View contentGroup;

        VH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.launcher_app_icon);
            title = itemView.findViewById(R.id.launcher_app_title);
            subtitle = itemView.findViewById(R.id.launcher_app_subtitle);
            banner = itemView.findViewById(R.id.launcher_app_banner);
            contentGroup = itemView.findViewById(R.id.launcher_app_content);

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
                itemView.setOutlineProvider(roundOutline);
                itemView.setClipToOutline(true);

                if (banner != null) {
                    banner.setOutlineProvider(roundOutline);
                    banner.setClipToOutline(true);
                }

                if (icon != null) {
                    final int iconRadius = dp(itemView.getContext(), 10);
                    icon.setOutlineProvider(new ViewOutlineProvider() {
                        @Override
                        public void getOutline(View view, Outline outline) {
                            if (view.getWidth() > 0 && view.getHeight() > 0) {
                                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), iconRadius);
                            } else {
                                outline.setEmpty();
                            }
                        }
                    });
                    icon.setClipToOutline(true);
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
        int radius = dp(ctx, 16);
        int stroke = Math.round(dp(ctx, 2));

        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.TRANSPARENT);
        focused.setCornerRadius(radius);
        focused.setStroke(stroke, 0xF5FFFFFF);

        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_focused }, focused);
        s.addState(new int[] { android.R.attr.state_activated }, focused);
        s.addState(new int[] {}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    private static int blend(int bg, int fg, float ratio) {
        int r = (int) (Color.red(bg) * (1f - ratio) + Color.red(fg) * ratio);
        int g = (int) (Color.green(bg) * (1f - ratio) + Color.green(fg) * ratio);
        int b = (int) (Color.blue(bg) * (1f - ratio) + Color.blue(fg) * ratio);
        return Color.rgb(Math.max(0, Math.min(255, r)), Math.max(0, Math.min(255, g)), Math.max(0, Math.min(255, b)));
    }

    private static int colorFromEntry(LauncherAppEntry e) {
        if (e != null && e.isLiveTv) {
            return 0xFF7C3AED; // tvOS purple / violet accent
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
