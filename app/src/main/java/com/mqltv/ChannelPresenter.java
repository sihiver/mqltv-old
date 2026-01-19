package com.mqltv;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.leanback.widget.Presenter;

public class ChannelPresenter extends Presenter {

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        Context context = parent.getContext();

        TextView textView = new TextView(context);
        textView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        int padding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                16,
                context.getResources().getDisplayMetrics()
        );
        textView.setPadding(padding, padding, padding, padding);
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        textView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        textView.setFocusable(true);
        textView.setFocusableInTouchMode(true);

        textView.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackgroundColor(hasFocus ? 0xFF2D2D2D : Color.TRANSPARENT);
        });

        return new ViewHolder(textView);
    }

    @Override
    public void onBindViewHolder(ViewHolder viewHolder, Object item) {
        Channel channel = (Channel) item;
        TextView textView = (TextView) viewHolder.view;
        textView.setText(channel.getTitle());
    }

    @Override
    public void onUnbindViewHolder(ViewHolder viewHolder) {
        // no-op
    }
}
