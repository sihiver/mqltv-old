package com.mqltv;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public final class LiveTvCategoryAdapter extends RecyclerView.Adapter<LiveTvCategoryAdapter.VH> {

    public interface Listener {
        void onCategorySelected(int position);
    }

    private final List<String> labels = new ArrayList<>();
    private final Listener listener;
    private int selected = 0;

    private final Handler debounce = new Handler(Looper.getMainLooper());
    private Runnable pendingSelect = null;

    public LiveTvCategoryAdapter(Listener listener) {
        this.listener = listener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void submit(List<String> categoryLabels) {
        labels.clear();
        if (categoryLabels != null) labels.addAll(categoryLabels);
        if (selected >= labels.size()) selected = 0;
        notifyDataSetChanged();
    }

    public void setSelected(int position) {
        if (position < 0 || position >= labels.size()) return;
        if (selected == position) return;
        int old = selected;
        selected = position;
        notifyItemChanged(old);
        notifyItemChanged(selected);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_live_tv_category, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        String label = labels.get(position);
        holder.text.setText(label);
        holder.text.setActivated(position == selected);
        holder.text.setOnClickListener(v -> {
            if (listener != null) listener.onCategorySelected(holder.getBindingAdapterPosition());
        });
        holder.text.setOnFocusChangeListener((v, hasFocus) -> {
            float s = hasFocus ? 1.04f : 1.0f;
            v.animate().scaleX(s).scaleY(s).setDuration(120).start();

            if (hasFocus) {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;

                final int old = selected;
                selected = pos;
                v.setActivated(true);

                // Post to the next looper cycle so both focus-change events (old=false, new=true)
                // have completed before we rebind the outgoing tab — this ensures it reads the
                // already-updated `selected` and correctly sets setActivated(false).
                if (old != pos) {
                    debounce.post(() -> notifyItemChanged(old));
                }

                // Debounce grid data update: only reload after focus settles.
                if (pendingSelect != null) debounce.removeCallbacks(pendingSelect);
                pendingSelect = () -> {
                    if (listener != null) listener.onCategorySelected(pos);
                };
                debounce.postDelayed(pendingSelect, 150);
            } else {
                // When losing focus, reflect whether this tab is still the active category.
                v.setActivated(selected == holder.getBindingAdapterPosition());
            }
        });
    }

    @Override
    public int getItemCount() {
        return labels.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView text;

        VH(@NonNull View itemView) {
            super(itemView);
            text = (TextView) itemView;
        }
    }
}
