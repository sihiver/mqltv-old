package com.mqltv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class NavAdapter extends RecyclerView.Adapter<NavAdapter.VH> {

    public interface Listener {
        void onDestinationClicked(NavDestination destination);
    }

    private final List<NavItem> items;
    private final Listener listener;
    private boolean expanded = false;
    private NavDestination selected;

    public NavAdapter(List<NavItem> items, Listener listener) {
        this.items = items;
        this.listener = listener;
    }

    public void setExpanded(boolean expanded) {
        if (this.expanded == expanded) return;
        this.expanded = expanded;
        notifyDataSetChanged();
    }

    public void setSelected(NavDestination destination) {
        if (selected == destination) return;
        selected = destination;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.nav_item, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        NavItem item = items.get(position);
        holder.icon.setImageResource(item.getIconRes());
        holder.text.setText(item.getTitle());
        holder.text.setVisibility(expanded ? View.VISIBLE : View.GONE);
        holder.root.setActivated(item.getDestination() == selected);
        holder.root.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDestinationClicked(item.getDestination());
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View root;
        final ImageView icon;
        final TextView text;

        VH(@NonNull View itemView) {
            super(itemView);
            root = itemView.findViewById(R.id.nav_item_root);
            icon = itemView.findViewById(R.id.nav_item_icon);
            text = itemView.findViewById(R.id.nav_item_text);
        }
    }
}
