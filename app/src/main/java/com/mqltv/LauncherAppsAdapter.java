package com.mqltv;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

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

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        LauncherAppEntry e = items.get(position);
        if (holder.icon != null) {
            holder.icon.setContentDescription(e.label);
        }

        if (e.isAddButton) {
            holder.icon.setImageResource(android.R.drawable.ic_input_add);
            holder.icon.setColorFilter(ContextCompat.getColor(holder.itemView.getContext(), R.color.mql_text_primary));
        } else {
            holder.icon.setImageDrawable(e.icon);
            holder.icon.clearColorFilter();
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

        VH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.launcher_app_icon);
        }
    }
}
