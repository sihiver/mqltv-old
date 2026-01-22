package com.mqltv;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class HomeSectionAdapter extends RecyclerView.Adapter<HomeSectionAdapter.VH> {

    private final List<HomeSection> items = new ArrayList<>();

    public void submit(List<HomeSection> sections) {
        items.clear();
        if (sections != null) items.addAll(sections);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.row_home_section, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        HomeSection section = items.get(position);
        int count = section.getChannels() != null ? section.getChannels().size() : 0;
        holder.title.setText(formatTitle(holder.title, section.getTitle(), count));

        if (holder.rowList.getLayoutManager() == null) {
            holder.rowList.setLayoutManager(new LinearLayoutManager(holder.rowList.getContext(), RecyclerView.HORIZONTAL, false));
            holder.rowList.setHasFixedSize(true);
            holder.rowList.setItemViewCacheSize(18);
        }

        ChannelCardAdapter adapter = (ChannelCardAdapter) holder.rowList.getAdapter();
        if (adapter == null) {
            adapter = new ChannelCardAdapter();
            holder.rowList.setAdapter(adapter);
        }
        adapter.submit(section.getChannels());
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final RecyclerView rowList;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.section_title);
            rowList = itemView.findViewById(R.id.section_list);
        }
    }

    private static CharSequence formatTitle(TextView tv, String title, int count) {
        String safeTitle = title != null ? title : "";
        String countText = String.format(" (%02d)", count);
        SpannableString ss = new SpannableString(safeTitle + countText);
        int start = safeTitle.length();
        int end = ss.length();
        int color = tv.getResources().getColor(R.color.mql_accent);
        ss.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }
}
