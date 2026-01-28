package com.mqltv;

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

public class LauncherCardAdapter extends RecyclerView.Adapter<LauncherCardAdapter.VH> {

    public interface Listener {
        void onCardClicked(LauncherCard card);
    }

    private final List<LauncherCard> items = new ArrayList<>();
    private final Listener listener;

    public LauncherCardAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<LauncherCard> cards) {
        items.clear();
        if (cards != null) items.addAll(cards);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_launcher_card, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        LauncherCard card = items.get(position);
        holder.title.setText(card.getTitle());
        holder.subtitle.setText(card.getSubtitle() != null ? card.getSubtitle() : "");
        holder.icon.setImageResource(card.getIconRes());
        holder.indicator.setVisibility(View.INVISIBLE);

        int colorPrimary = ContextCompat.getColor(holder.itemView.getContext(), R.color.mql_text_primary);
        int colorSecondary = ContextCompat.getColor(holder.itemView.getContext(), R.color.mql_text_secondary);
        holder.icon.setColorFilter(colorSecondary);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onCardClicked(card);
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            float s = hasFocus ? 1.03f : 1.0f;
            v.animate().scaleX(s).scaleY(s).setDuration(120).start();
            v.setActivated(hasFocus);
            if (holder.indicator != null) {
                holder.indicator.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE);
            }

            // Keep focused card fully visible (avoid partial cut on the left).
            if (hasFocus) {
                holder.icon.setColorFilter(colorPrimary);
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
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        final View indicator;

        VH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.launcher_card_icon);
            title = itemView.findViewById(R.id.launcher_card_title);
            subtitle = itemView.findViewById(R.id.launcher_card_subtitle);
            indicator = itemView.findViewById(R.id.launcher_card_indicator);
        }
    }
}
