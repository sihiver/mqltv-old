package com.mqltv;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class SettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_settings, container, false);

        RadioGroup group = v.findViewById(R.id.player_mode_group);
        RadioButton auto = v.findViewById(R.id.player_mode_auto);
        RadioButton exo = v.findViewById(R.id.player_mode_exo);
        RadioButton exoLegacy = v.findViewById(R.id.player_mode_exo_legacy);
        RadioButton vlc = v.findViewById(R.id.player_mode_vlc);

        int mode = PlayerIntents.getPlayerMode(v.getContext());
        if (mode == PlayerIntents.PLAYER_MODE_VLC) {
            vlc.setChecked(true);
        } else if (mode == PlayerIntents.PLAYER_MODE_EXO_LEGACY) {
            exoLegacy.setChecked(true);
        } else if (mode == PlayerIntents.PLAYER_MODE_EXO) {
            exo.setChecked(true);
        } else {
            auto.setChecked(true);
        }

        group.setOnCheckedChangeListener((g, checkedId) -> {
            int newMode = PlayerIntents.PLAYER_MODE_AUTO;
            String label = "Auto";
            if (checkedId == R.id.player_mode_exo) {
                newMode = PlayerIntents.PLAYER_MODE_EXO;
                label = "ExoPlayer (Media3)";
            } else if (checkedId == R.id.player_mode_exo_legacy) {
                newMode = PlayerIntents.PLAYER_MODE_EXO_LEGACY;
                label = "ExoPlayer 2.13.3";
            } else if (checkedId == R.id.player_mode_vlc) {
                newMode = PlayerIntents.PLAYER_MODE_VLC;
                label = "VLC";
            }

            PlayerIntents.setPlayerMode(v.getContext(), newMode);
            Toast.makeText(v.getContext(), "Player: " + label, Toast.LENGTH_SHORT).show();
        });

        Switch exoLimit = v.findViewById(R.id.setting_exo_limit_480p);
        exoLimit.setChecked(PlaybackPrefs.isExoLimit480p(v.getContext()));
        exoLimit.setOnCheckedChangeListener((buttonView, isChecked) -> {
            PlaybackPrefs.setExoLimit480p(v.getContext(), isChecked);
            Toast.makeText(v.getContext(), isChecked ? "Exo: limit 480p ON" : "Exo: limit 480p OFF", Toast.LENGTH_SHORT).show();
        });

        RadioGroup vlcHwGroup = v.findViewById(R.id.vlc_hw_group);
        int hw = PlaybackPrefs.getVlcHwDecoderMode(v.getContext());
        if (hw == PlaybackPrefs.VLC_HW_ON) {
            ((RadioButton) v.findViewById(R.id.vlc_hw_on)).setChecked(true);
        } else if (hw == PlaybackPrefs.VLC_HW_OFF) {
            ((RadioButton) v.findViewById(R.id.vlc_hw_off)).setChecked(true);
        } else {
            ((RadioButton) v.findViewById(R.id.vlc_hw_auto)).setChecked(true);
        }
        vlcHwGroup.setOnCheckedChangeListener((g, checkedId) -> {
            int hwMode = PlaybackPrefs.VLC_HW_AUTO;
            String label = "Auto";
            if (checkedId == R.id.vlc_hw_on) {
                hwMode = PlaybackPrefs.VLC_HW_ON;
                label = "HW ON";
            } else if (checkedId == R.id.vlc_hw_off) {
                hwMode = PlaybackPrefs.VLC_HW_OFF;
                label = "HW OFF";
            }
            PlaybackPrefs.setVlcHwDecoderMode(v.getContext(), hwMode);
            Toast.makeText(v.getContext(), "VLC: " + label, Toast.LENGTH_SHORT).show();
        });

        Switch vlcTexture = v.findViewById(R.id.setting_vlc_texture);
        vlcTexture.setChecked(PlaybackPrefs.isVlcUseTexture(v.getContext()));
        vlcTexture.setOnCheckedChangeListener((buttonView, isChecked) -> {
            PlaybackPrefs.setVlcUseTexture(v.getContext(), isChecked);
            Toast.makeText(v.getContext(), isChecked ? "VLC output: Texture" : "VLC output: Surface", Toast.LENGTH_SHORT).show();
        });

        RadioGroup voutGroup = v.findViewById(R.id.vlc_vout_group);
        int vout = PlaybackPrefs.getVlcVout(v.getContext());
        if (vout == PlaybackPrefs.VLC_VOUT_ANDROID_DISPLAY) {
            ((RadioButton) v.findViewById(R.id.vlc_vout_android_display)).setChecked(true);
        } else if (vout == PlaybackPrefs.VLC_VOUT_ANDROID_SURFACE) {
            ((RadioButton) v.findViewById(R.id.vlc_vout_android_surface)).setChecked(true);
        } else if (vout == PlaybackPrefs.VLC_VOUT_GLES2) {
            ((RadioButton) v.findViewById(R.id.vlc_vout_gles2)).setChecked(true);
        } else {
            ((RadioButton) v.findViewById(R.id.vlc_vout_auto)).setChecked(true);
        }
        voutGroup.setOnCheckedChangeListener((g, checkedId) -> {
            int newVout = PlaybackPrefs.VLC_VOUT_AUTO;
            String label = "Auto";
            if (checkedId == R.id.vlc_vout_android_display) {
                newVout = PlaybackPrefs.VLC_VOUT_ANDROID_DISPLAY;
                label = "android_display";
            } else if (checkedId == R.id.vlc_vout_android_surface) {
                newVout = PlaybackPrefs.VLC_VOUT_ANDROID_SURFACE;
                label = "android_surface";
            } else if (checkedId == R.id.vlc_vout_gles2) {
                newVout = PlaybackPrefs.VLC_VOUT_GLES2;
                label = "gles2";
            }

            PlaybackPrefs.setVlcVout(v.getContext(), newVout);
            Toast.makeText(v.getContext(), "VLC vout: " + label, Toast.LENGTH_SHORT).show();
        });

        // Make sure something is focusable for TV (post so fragment is committed & laid out).
        v.post(auto::requestFocus);
        return v;
    }
}
