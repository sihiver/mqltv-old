package com.mqltv;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class PlaceholderFragment extends Fragment {

    private static final String ARG_TITLE = "title";

    public static PlaceholderFragment newInstance(String title) {
        PlaceholderFragment f = new PlaceholderFragment();
        Bundle b = new Bundle();
        b.putString(ARG_TITLE, title);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        TextView tv = new TextView(requireContext());
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(22);
        tv.setText(getArguments() != null ? getArguments().getString(ARG_TITLE, "") : "");
        return tv;
    }
}
