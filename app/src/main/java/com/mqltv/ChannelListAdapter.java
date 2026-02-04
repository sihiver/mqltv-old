package com.mqltv;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class ChannelListAdapter extends RecyclerView.Adapter<ChannelListAdapter.VH> {

    private final List<Channel> items = new ArrayList<>();

    public void submit(List<Channel> channels) {
        items.clear();
        if (channels != null) items.addAll(channels);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.channel_item, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Channel c = items.get(position);
        holder.title.setText(c.getTitle());
        holder.title.setOnClickListener(v -> {
            if (!LoginGuard.ensureLoggedIn(v.getContext())) return;
            if (!SubscriptionGuard.ensureNotExpired(v.getContext())) return;
            RecentChannelsStore.record(v.getContext(), c);
            PresenceReporter.reportOnlineLaunch(v.getContext(), c.getTitle(), c.getUrl());
            Intent intent = PlayerIntents.createPreferredPlayIntent(v.getContext(), c.getTitle(), c.getUrl());
            try {
                v.getContext().startActivity(intent);
            } catch (Exception e) {
                v.getContext().startActivity(PlayerIntents.createPlayIntent(v.getContext(), c.getTitle(), c.getUrl()));
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.channel_title);
        }
    }
}
