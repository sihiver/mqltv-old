package com.mqltv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
    }

    private final List<LauncherAppEntry> items = new ArrayList<>();
    private final Listener listener;

    public LauncherAppsAdapter(Listener listener) {
        this.listener = listener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void submit(List<LauncherAppEntry> list) {
        items.clear();
        if (list != null) items.addAll(list);
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

            holder.itemView.setBackground(createTileBackground(ctx, 0xFF4D5B6A));
        } else {
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

            holder.itemView.setBackground(createTileBackground(ctx, colorFromEntry(e)));
        }

        holder.itemView.setOnClickListener(v -> {
            if (e.isAddButton) {
                if (listener != null) listener.onAddClicked();
            } else {
                if (listener != null) listener.onAppClicked(e);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (e.isAddButton) return true;
            if (listener != null) listener.onAppLongPressed(e);
            return true;
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            float s = hasFocus ? 1.05f : 1.0f;
            v.animate().scaleX(s).scaleY(s).setDuration(120).start();
            v.setActivated(hasFocus);
            if (v.getBackground() != null) v.getBackground().setState(v.getDrawableState());
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

        VH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.launcher_app_icon);
            title = itemView.findViewById(R.id.launcher_app_title);
            subtitle = itemView.findViewById(R.id.launcher_app_subtitle);
        }
    }

    private static Drawable createTileBackground(Context ctx, int baseColor) {
        int radius = dp(ctx, 6);
        int stroke = dp(ctx, 2);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(baseColor);
        normal.setCornerRadius(radius);

        GradientDrawable focused = new GradientDrawable();
        focused.setColor(lighten(baseColor));
        focused.setCornerRadius(radius);
        focused.setStroke(stroke, 0xFF3AA0FF);

        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_focused }, focused);
        s.addState(new int[] { android.R.attr.state_activated }, focused);
        s.addState(new int[] {}, normal);
        return s;
    }

    private static int colorFromEntry(LauncherAppEntry e) {
        String key = null;
        if (e != null && e.component != null) {
            key = e.component.getPackageName();
        }
        if (key == null) key = e != null ? e.label : "app";
        return colorFromString(key);
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
