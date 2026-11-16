package com.mqltv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SettingsFragment extends Fragment {

    private static final int REQ_PICK_WALLPAPER = 501;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView wallpaperStatus;
    private ImageView wallpaperBg;
    private ImageView wallpaperPreviewThumb;
    private View wallpaperDimOverlay;
    private TextView statusDefaultLauncher;
    private TextView valueCacheSize;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_settings, container, false);
        Context appContext = v.getContext().getApplicationContext();

        wallpaperBg = v.findViewById(R.id.settings_wallpaper);
        wallpaperDimOverlay = v.findViewById(R.id.settings_wallpaper_dim);
        wallpaperPreviewThumb = v.findViewById(R.id.wallpaper_preview_thumb);
        wallpaperStatus = v.findViewById(R.id.wallpaper_status);
        statusDefaultLauncher = v.findViewById(R.id.status_default_launcher);
        valueCacheSize = v.findViewById(R.id.value_cache_size);

        setupCategoryTabs(v);
        setupHomeTab(v, appContext);
        setupWallpaperTab(v, appContext);
        setupPlayerTab(v, appContext);
        setupAdvancedTab(v, appContext);
        setupHeaderAndUpdate(v, appContext);

        loadSettingsWallpaper(appContext);
        applySettingsWallpaperDim(appContext);

        v.post(() -> {
            View menuHome = v.findViewById(R.id.menu_home);
            if (menuHome != null) {
                menuHome.requestFocus();
            }
        });

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getContext() != null) {
            Context appContext = getContext().getApplicationContext();
            updateDefaultLauncherStatus(appContext);
            refreshCacheSizeAsync(appContext);
        }
    }

    private void setupCategoryTabs(View v) {
        View menuHome = v.findViewById(R.id.menu_home);
        View menuWallpaper = v.findViewById(R.id.menu_wallpaper);
        View menuPlayer = v.findViewById(R.id.menu_player);
        View menuAdvanced = v.findViewById(R.id.menu_advanced);

        View contentHome = v.findViewById(R.id.content_home);
        View contentWallpaper = v.findViewById(R.id.content_wallpaper);
        View contentPlayer = v.findViewById(R.id.content_player);
        View contentAdvanced = v.findViewById(R.id.content_advanced);

        View[] menus = new View[]{menuHome, menuWallpaper, menuPlayer, menuAdvanced};
        View[] contents = new View[]{contentHome, contentWallpaper, contentPlayer, contentAdvanced};

        View.OnClickListener selectTab = view -> {
            for (int i = 0; i < menus.length; i++) {
                boolean active = menus[i] == view;
                if (menus[i] != null) menus[i].setSelected(active);
                if (contents[i] != null) contents[i].setVisibility(active ? View.VISIBLE : View.GONE);
            }
        };

        View.OnFocusChangeListener focusListener = (view, hasFocus) -> {
            if (hasFocus) selectTab.onClick(view);
        };

        for (View m : menus) {
            if (m != null) {
                m.setOnFocusChangeListener(focusListener);
                m.setOnClickListener(selectTab);
            }
        }
        if (menuHome != null) menuHome.setSelected(true);
    }

    private void setupHomeTab(View v, Context appContext) {
        View btnDockEdit = v.findViewById(R.id.btn_dock_edit);
        if (btnDockEdit != null) {
            btnDockEdit.setOnClickListener(view -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).triggerHomeAction("dock_edit");
                }
            });
        }

        View btnReorder = v.findViewById(R.id.btn_reorder_apps);
        if (btnReorder != null) {
            btnReorder.setOnClickListener(view -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).triggerHomeAction("reorder");
                }
            });
        }

        Switch clock24h = v.findViewById(R.id.setting_clock_24h);
        if (clock24h != null) {
            clock24h.setChecked(LauncherPrefs.isClock24Hour(appContext));
            clock24h.setOnCheckedChangeListener((btn, checked) -> {
                LauncherPrefs.setClock24Hour(appContext, checked);
                Toast.makeText(v.getContext(), checked ? "Format jam: 24 Jam" : "Format jam: 12 Jam", Toast.LENGTH_SHORT).show();
            });
        }

        Switch showRecent = v.findViewById(R.id.setting_show_recent);
        if (showRecent != null) {
            showRecent.setChecked(LauncherPrefs.isShowRecentChannels(appContext));
            showRecent.setOnCheckedChangeListener((btn, checked) -> {
                LauncherPrefs.setShowRecentChannels(appContext, checked);
                Toast.makeText(v.getContext(), checked ? "Saluran terakhir: Tampil" : "Saluran terakhir: Sembunyi", Toast.LENGTH_SHORT).show();
            });
        }

        View btnDefaultLauncher = v.findViewById(R.id.btn_default_launcher);
        if (btnDefaultLauncher != null) {
            btnDefaultLauncher.setOnClickListener(view -> openHomeLauncherSettings(v.getContext()));
        }
        updateDefaultLauncherStatus(appContext);
        lockVerticalFocusBounds(btnDockEdit, btnDefaultLauncher);
    }

    private void setupWallpaperTab(View v, Context appContext) {
        if (wallpaperPreviewThumb != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            final float radius = 10f * getResources().getDisplayMetrics().density;
            wallpaperPreviewThumb.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            wallpaperPreviewThumb.setClipToOutline(true);
        }

        updateWallpaperStatus(appContext);

        View dimRow = v.findViewById(R.id.row_wallpaper_dim);
        TextView dimValue = v.findViewById(R.id.value_wallpaper_dim);
        updateWallpaperDimLabel(appContext, dimValue);
        if (dimRow != null) {
            dimRow.setOnClickListener(view -> {
                final String[] labels = new String[]{"Terang", "Sedang (Default)", "Gelap"};
                int current = LauncherPrefs.getWallpaperDimLevel(appContext);
                showSingleChoiceDialog(v.getContext(), "Tingkat Gelap Latar", labels, current, which -> {
                    LauncherPrefs.setWallpaperDimLevel(appContext, which);
                    updateWallpaperDimLabel(appContext, dimValue);
                    applySettingsWallpaperDim(appContext);
                });
            });
        }

        View pickBtn = v.findViewById(R.id.wallpaper_pick_button);
        if (pickBtn != null) {
            pickBtn.setOnClickListener(view -> pickWallpaperFile());
        }

        View urlBtn = v.findViewById(R.id.wallpaper_set_url_button);
        if (urlBtn != null) {
            urlBtn.setOnClickListener(view -> showWallpaperUrlDialog(appContext));
        }

        View resetBtn = v.findViewById(R.id.wallpaper_reset_button);
        if (resetBtn != null) {
            resetBtn.setOnClickListener(view -> {
                boolean ok = LauncherWallpaper.clear(appContext);
                updateWallpaperStatus(appContext);
                loadSettingsWallpaper(appContext);
                Toast.makeText(v.getContext(), ok ? "Wallpaper di-reset ke Bing Daily" : "Gagal reset wallpaper", Toast.LENGTH_SHORT).show();
            });
        }
        lockVerticalFocusBounds(dimRow, resetBtn);
    }

    private void setupPlayerTab(View v, Context appContext) {
        Switch mxSwitch = v.findViewById(R.id.setting_use_mx_player);
        if (mxSwitch != null) {
            mxSwitch.setChecked(PlaybackPrefs.isUseMxPlayer(appContext));
            mxSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                PlaybackPrefs.setUseMxPlayer(appContext, isChecked);
                Toast.makeText(v.getContext(), isChecked ? "Akan putar di MX Player" : "Putar di player internal", Toast.LENGTH_SHORT).show();
            });
        }

        RadioGroup group = v.findViewById(R.id.player_mode_group);
        RadioButton auto = v.findViewById(R.id.player_mode_auto);
        RadioButton exo = v.findViewById(R.id.player_mode_exo);
        RadioButton nativePlayer = v.findViewById(R.id.player_mode_native);
        RadioButton vlc = v.findViewById(R.id.player_mode_vlc);

        int mode = PlayerIntents.getPlayerMode(v.getContext());
        if (mode == PlayerIntents.PLAYER_MODE_VLC && vlc != null) {
            vlc.setChecked(true);
        } else if (mode == PlayerIntents.PLAYER_MODE_NATIVE && nativePlayer != null) {
            nativePlayer.setChecked(true);
        } else if (mode == PlayerIntents.PLAYER_MODE_EXO && exo != null) {
            exo.setChecked(true);
        } else if (auto != null) {
            auto.setChecked(true);
        }

        if (group != null) {
            group.setOnCheckedChangeListener((g, checkedId) -> {
                int newMode = PlayerIntents.PLAYER_MODE_AUTO;
                String label = "Auto";
                if (checkedId == R.id.player_mode_exo) {
                    newMode = PlayerIntents.PLAYER_MODE_EXO;
                    label = "ExoPlayer (Media3)";
                } else if (checkedId == R.id.player_mode_native) {
                    newMode = PlayerIntents.PLAYER_MODE_NATIVE;
                    label = "Native (MediaPlayer)";
                } else if (checkedId == R.id.player_mode_vlc) {
                    newMode = PlayerIntents.PLAYER_MODE_VLC;
                    label = "VLC";
                }
                PlayerIntents.setPlayerMode(v.getContext(), newMode);
                Toast.makeText(v.getContext(), "Player: " + label, Toast.LENGTH_SHORT).show();
            });
        }

        // Kualitas Video (compact dialog picker)
        View qualityRow = v.findViewById(R.id.row_vision_quality);
        TextView qualityVal = v.findViewById(R.id.value_vision_quality);
        final int[] qualityCodes = new int[]{
                PlayerQualityHelper.QUALITY_AUTO,
                PlayerQualityHelper.QUALITY_1080,
                PlayerQualityHelper.QUALITY_720,
                PlayerQualityHelper.QUALITY_480,
                PlayerQualityHelper.QUALITY_360,
                PlayerQualityHelper.QUALITY_LOWEST
        };
        final String[] qualityLabels = new String[]{
                getString(R.string.player_quality_auto),
                getString(R.string.player_quality_1080),
                getString(R.string.player_quality_720),
                getString(R.string.player_quality_480),
                getString(R.string.player_quality_360),
                getString(R.string.player_quality_lowest)
        };
        updateValueFromArray(qualityVal, qualityLabels, indexOfInt(qualityCodes, PlaybackPrefs.getVisionVideoQuality(v.getContext())));
        if (qualityRow != null) {
            qualityRow.setOnClickListener(view -> {
                int cur = indexOfInt(qualityCodes, PlaybackPrefs.getVisionVideoQuality(v.getContext()));
                showSingleChoiceDialog(v.getContext(), getString(R.string.settings_vision_quality_title), qualityLabels, cur, which -> {
                    PlaybackPrefs.setVisionVideoQuality(v.getContext(), qualityCodes[which]);
                    updateValueFromArray(qualityVal, qualityLabels, which);
                    Toast.makeText(v.getContext(), getString(R.string.player_quality_applied, qualityLabels[which]), Toast.LENGTH_SHORT).show();
                });
            });
        }

        // Skala Tampilan Video (compact dialog picker)
        View displayRow = v.findViewById(R.id.row_video_display);
        TextView displayVal = v.findViewById(R.id.value_video_display);
        final int[] displayCodes = new int[]{
                PlaybackPrefs.VIDEO_DISPLAY_FIT,
                PlaybackPrefs.VIDEO_DISPLAY_FILL,
                PlaybackPrefs.VIDEO_DISPLAY_ZOOM,
                PlaybackPrefs.VIDEO_DISPLAY_16_9,
                PlaybackPrefs.VIDEO_DISPLAY_4_3
        };
        final String[] displayLabels = new String[]{
                getString(R.string.settings_video_display_fit),
                getString(R.string.settings_video_display_fill),
                getString(R.string.settings_video_display_zoom),
                getString(R.string.settings_video_display_16_9),
                getString(R.string.settings_video_display_4_3)
        };
        updateValueFromArray(displayVal, displayLabels, indexOfInt(displayCodes, PlaybackPrefs.getVideoDisplayMode(v.getContext())));
        if (displayRow != null) {
            displayRow.setOnClickListener(view -> {
                int cur = indexOfInt(displayCodes, PlaybackPrefs.getVideoDisplayMode(v.getContext()));
                showSingleChoiceDialog(v.getContext(), getString(R.string.settings_video_display_title), displayLabels, cur, which -> {
                    PlaybackPrefs.setVideoDisplayMode(v.getContext(), displayCodes[which]);
                    updateValueFromArray(displayVal, displayLabels, which);
                });
            });
        }

        // Auto Exit Tanpa Interaksi (removed 1 Menit Tes debug option)
        View idleRow = v.findViewById(R.id.row_idle_timeout);
        TextView idleVal = v.findViewById(R.id.value_idle_timeout);
        final int[] idleCodes = new int[]{
                PlaybackPrefs.IDLE_TIMEOUT_OFF,
                PlaybackPrefs.IDLE_TIMEOUT_1_HOUR,
                PlaybackPrefs.IDLE_TIMEOUT_2_HOURS,
                PlaybackPrefs.IDLE_TIMEOUT_3_HOURS,
                PlaybackPrefs.IDLE_TIMEOUT_4_HOURS
        };
        final String[] idleLabels = new String[]{
                "Nonaktif",
                "1 Jam",
                "2 Jam (Default)",
                "3 Jam",
                "4 Jam"
        };
        int savedIdle = PlaybackPrefs.getIdleTimeoutHours(v.getContext());
        if (savedIdle == PlaybackPrefs.IDLE_TIMEOUT_1_MIN) {
            savedIdle = PlaybackPrefs.IDLE_TIMEOUT_2_HOURS;
            PlaybackPrefs.setIdleTimeoutHours(v.getContext(), savedIdle);
        }
        updateValueFromArray(idleVal, idleLabels, indexOfInt(idleCodes, savedIdle, 2));
        if (idleRow != null) {
            idleRow.setOnClickListener(view -> {
                int cur = indexOfInt(idleCodes, PlaybackPrefs.getIdleTimeoutHours(v.getContext()), 2);
                showSingleChoiceDialog(v.getContext(), "Auto Exit Tanpa Interaksi", idleLabels, cur, which -> {
                    PlaybackPrefs.setIdleTimeoutHours(v.getContext(), idleCodes[which]);
                    updateValueFromArray(idleVal, idleLabels, which);
                });
            });
        }
        lockVerticalFocusBounds(auto, idleRow);
    }

    private void setupAdvancedTab(View v, Context appContext) {
        // VLC Hardware Decoder
        View hwRow = v.findViewById(R.id.row_vlc_hw);
        TextView hwVal = v.findViewById(R.id.value_vlc_hw);
        final boolean ec6108v9 = DeviceQuirks.isHuaweiEc6108v9();
        final int[] hwCodes = ec6108v9
                ? new int[]{PlaybackPrefs.VLC_HW_AUTO, PlaybackPrefs.VLC_HW_ON, PlaybackPrefs.VLC_HW_OFF}
                : new int[]{PlaybackPrefs.VLC_HW_AUTO, PlaybackPrefs.VLC_HW_ON, PlaybackPrefs.VLC_HW_PLUS, PlaybackPrefs.VLC_HW_OFF};
        final String[] hwLabels = ec6108v9
                ? new String[]{"Auto", "HW ON (mediacodec)", "HW OFF (software)"}
                : new String[]{"Auto", "HW ON (mediacodec)", "HW+ (direct render)", "HW OFF (software)"};

        int curHw = PlaybackPrefs.getVlcHwDecoderMode(v.getContext());
        if (ec6108v9 && curHw == PlaybackPrefs.VLC_HW_PLUS) {
            curHw = PlaybackPrefs.VLC_HW_ON;
            PlaybackPrefs.setVlcHwDecoderMode(v.getContext(), curHw);
        }
        updateValueFromArray(hwVal, hwLabels, indexOfInt(hwCodes, curHw, 1));
        if (hwRow != null) {
            hwRow.setOnClickListener(view -> {
                int cur = indexOfInt(hwCodes, PlaybackPrefs.getVlcHwDecoderMode(v.getContext()), 1);
                showSingleChoiceDialog(v.getContext(), "Dekoder Hardware (VLC)", hwLabels, cur, which -> {
                    PlaybackPrefs.setVlcHwDecoderMode(v.getContext(), hwCodes[which]);
                    updateValueFromArray(hwVal, hwLabels, which);
                });
            });
        }

        // VLC Output Video (unified Surface/vout + TextureView)
        View outRow = v.findViewById(R.id.row_vlc_output);
        TextView outVal = v.findViewById(R.id.value_vlc_output);
        final String[] outLabels = new String[]{
                "Auto (SurfaceView)",
                "android_display (STB)",
                "android_surface",
                "gles2 (OpenGL)",
                "TextureView (Kompatibel)"
        };
        updateVlcOutputLabel(v.getContext(), outVal, outLabels);
        if (outRow != null) {
            outRow.setOnClickListener(view -> {
                int cur = getVlcOutputIndex(v.getContext());
                showSingleChoiceDialog(v.getContext(), "Output Video (VLC)", outLabels, cur, which -> {
                    if (which == 4) {
                        PlaybackPrefs.setVlcUseTexture(v.getContext(), true);
                        PlaybackPrefs.setVlcVout(v.getContext(), PlaybackPrefs.VLC_VOUT_AUTO);
                    } else {
                        PlaybackPrefs.setVlcUseTexture(v.getContext(), false);
                        int vout = PlaybackPrefs.VLC_VOUT_AUTO;
                        if (which == 1) vout = PlaybackPrefs.VLC_VOUT_ANDROID_DISPLAY;
                        else if (which == 2) vout = PlaybackPrefs.VLC_VOUT_ANDROID_SURFACE;
                        else if (which == 3) vout = PlaybackPrefs.VLC_VOUT_GLES2;
                        PlaybackPrefs.setVlcVout(v.getContext(), vout);
                    }
                    updateVlcOutputLabel(v.getContext(), outVal, outLabels);
                });
            });
        }

        // VLC Network Caching
        View cachingRow = v.findViewById(R.id.row_vlc_caching);
        TextView cachingVal = v.findViewById(R.id.value_vlc_caching);
        final int[] cachingCodes = new int[]{1500, 3000, 5000, 10000};
        final String[] cachingLabels = new String[]{"1500 ms (Default)", "3000 ms", "5000 ms", "10000 ms (Maksimum)"};
        updateValueFromArray(cachingVal, cachingLabels, indexOfInt(cachingCodes, PlaybackPrefs.getVlcNetworkCaching(v.getContext())));
        if (cachingRow != null) {
            cachingRow.setOnClickListener(view -> {
                int cur = indexOfInt(cachingCodes, PlaybackPrefs.getVlcNetworkCaching(v.getContext()));
                showSingleChoiceDialog(v.getContext(), "Buffer Jaringan (VLC)", cachingLabels, cur, which -> {
                    PlaybackPrefs.setVlcNetworkCaching(v.getContext(), cachingCodes[which]);
                    updateValueFromArray(cachingVal, cachingLabels, which);
                });
            });
        }

        Switch hwForceOnly = v.findViewById(R.id.setting_vlc_hw_force_only);
        if (hwForceOnly != null) {
            hwForceOnly.setChecked(PlaybackPrefs.isVlcHwForceOnly(v.getContext()));
            hwForceOnly.setOnCheckedChangeListener((btn, isChecked) -> PlaybackPrefs.setVlcHwForceOnly(v.getContext(), isChecked));
        }

        Switch vlcDeinterlace = v.findViewById(R.id.setting_vlc_deinterlace);
        if (vlcDeinterlace != null) {
            vlcDeinterlace.setChecked(PlaybackPrefs.isVlcDeinterlaceEnabled(v.getContext()));
            vlcDeinterlace.setOnCheckedChangeListener((btn, isChecked) -> PlaybackPrefs.setVlcDeinterlaceEnabled(v.getContext(), isChecked));
        }

        View resetVlc = v.findViewById(R.id.vlc_reset_button);
        if (resetVlc != null) {
            resetVlc.setOnClickListener(view -> {
                PlaybackPrefs.setVlcHwDecoderMode(v.getContext(), PlaybackPrefs.VLC_HW_ON);
                PlaybackPrefs.setVlcUseTexture(v.getContext(), false);
                PlaybackPrefs.setVlcVout(v.getContext(), PlaybackPrefs.VLC_VOUT_AUTO);
                PlaybackPrefs.setVlcNetworkCaching(v.getContext(), 1500);
                PlaybackPrefs.setVlcDeinterlaceEnabled(v.getContext(), false);
                PlaybackPrefs.setVlcHwDecoderImpl(v.getContext(), PlaybackPrefs.VLC_HW_IMPL_AUTO);
                PlaybackPrefs.setVlcHwForceOnly(v.getContext(), false);

                updateValueFromArray(hwVal, hwLabels, indexOfInt(hwCodes, PlaybackPrefs.VLC_HW_ON, 1));
                updateVlcOutputLabel(v.getContext(), outVal, outLabels);
                updateValueFromArray(cachingVal, cachingLabels, 0);
                if (hwForceOnly != null) hwForceOnly.setChecked(false);
                if (vlcDeinterlace != null) vlcDeinterlace.setChecked(false);

                Toast.makeText(v.getContext(), "Setelan VLC dikembalikan ke default", Toast.LENGTH_SHORT).show();
            });
        }

        // Device Info
        TextView tvDevice = v.findViewById(R.id.tv_device_info);
        if (tvDevice != null) {
            String model = (Build.MANUFACTURER != null ? Build.MANUFACTURER : "") + " " + (Build.MODEL != null ? Build.MODEL : "");
            String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI;
            tvDevice.setText("Perangkat: " + model.trim() + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · " + abi);
        }

        // Clear Cache
        View btnClearCache = v.findViewById(R.id.btn_clear_cache);
        if (btnClearCache != null) {
            btnClearCache.setOnClickListener(view -> clearAppCacheAsync(appContext));
        }
        refreshCacheSizeAsync(appContext);
        lockVerticalFocusBounds(hwRow, btnClearCache);
    }

    private static void lockVerticalFocusBounds(@Nullable View topView, @Nullable View bottomView) {
        if (topView != null) {
            topView.setOnKeyListener((view, keyCode, event) ->
                    event.getAction() == android.view.KeyEvent.ACTION_DOWN
                            && keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP);
            topView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    android.widget.ScrollView sv = findParentScrollView(v);
                    if (sv != null) sv.post(() -> sv.smoothScrollTo(0, 0));
                }
            });
        }
        if (bottomView != null) {
            bottomView.setOnKeyListener((view, keyCode, event) ->
                    event.getAction() == android.view.KeyEvent.ACTION_DOWN
                            && keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN);
            bottomView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    android.widget.ScrollView sv = findParentScrollView(v);
                    if (sv != null && sv.getChildCount() > 0) {
                        sv.post(() -> sv.smoothScrollTo(0, sv.getChildAt(0).getBottom() + sv.getPaddingBottom()));
                    }
                }
            });
        }
    }

    @Nullable
    private static android.widget.ScrollView findParentScrollView(View v) {
        android.view.ViewParent p = v.getParent();
        while (p instanceof View) {
            if (p instanceof android.widget.ScrollView) {
                return (android.widget.ScrollView) p;
            }
            p = p.getParent();
        }
        return null;
    }

    private void setupHeaderAndUpdate(View v, Context appContext) {
        try {
            PackageInfo pInfo = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
            TextView tvVersion = v.findViewById(R.id.tv_app_version);
            if (tvVersion != null) {
                tvVersion.setText("Versi MQLTV: " + pInfo.versionName);
            }
        } catch (Exception ignored) {
        }

        View btnCheckUpdate = v.findViewById(R.id.btn_check_update);
        if (btnCheckUpdate != null) {
            btnCheckUpdate.setOnClickListener(view -> {
                Activity act = getActivity();
                if (act != null) {
                    AppUpdater.checkForUpdates(act, true);
                }
            });
        }
    }

    private void showSingleChoiceDialog(Context ctx, String title, String[] options, int checkedIndex, OnChoiceSelected cb) {
        if (ctx == null) return;
        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setSingleChoiceItems(options, Math.max(0, checkedIndex), (dialog, which) -> {
                    dialog.dismiss();
                    cb.onSelected(which);
                })
                .setNegativeButton("Batal", (d, w) -> d.dismiss())
                .show();
    }

    private interface OnChoiceSelected {
        void onSelected(int index);
    }

    private static int indexOfInt(int[] arr, int val) {
        return indexOfInt(arr, val, 0);
    }

    private static int indexOfInt(int[] arr, int val, int fallback) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == val) return i;
        }
        return Math.min(fallback, Math.max(0, arr.length - 1));
    }

    private static void updateValueFromArray(TextView tv, String[] labels, int index) {
        if (tv == null || labels == null || labels.length == 0) return;
        int safe = Math.max(0, Math.min(index, labels.length - 1));
        tv.setText(labels[safe] + " ›");
    }

    private static int getVlcOutputIndex(Context ctx) {
        if (PlaybackPrefs.isVlcUseTexture(ctx)) return 4;
        int vout = PlaybackPrefs.getVlcVout(ctx);
        if (vout == PlaybackPrefs.VLC_VOUT_ANDROID_DISPLAY) return 1;
        if (vout == PlaybackPrefs.VLC_VOUT_ANDROID_SURFACE) return 2;
        if (vout == PlaybackPrefs.VLC_VOUT_GLES2) return 3;
        return 0;
    }

    private static void updateVlcOutputLabel(Context ctx, TextView tv, String[] labels) {
        updateValueFromArray(tv, labels, getVlcOutputIndex(ctx));
    }

    private static void updateWallpaperDimLabel(Context ctx, TextView tv) {
        if (tv == null) return;
        int level = LauncherPrefs.getWallpaperDimLevel(ctx);
        String text = level == LauncherPrefs.WALLPAPER_DIM_LIGHT ? "Terang"
                : (level == LauncherPrefs.WALLPAPER_DIM_DARK ? "Gelap" : "Sedang");
        tv.setText(text + " ›");
    }

    private void applySettingsWallpaperDim(Context appContext) {
        int level = LauncherPrefs.getWallpaperDimLevel(appContext);
        if (wallpaperDimOverlay != null) {
            wallpaperDimOverlay.setAlpha(level == LauncherPrefs.WALLPAPER_DIM_LIGHT ? 0.65f : 1.0f);
        }
        if (wallpaperPreviewThumb != null) {
            wallpaperPreviewThumb.setAlpha(level == LauncherPrefs.WALLPAPER_DIM_DARK ? 0.60f : 1.0f);
        }
    }

    private void updateDefaultLauncherStatus(Context appContext) {
        if (statusDefaultLauncher == null || appContext == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo res = appContext.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            boolean isDefault = res != null && res.activityInfo != null
                    && appContext.getPackageName().equals(res.activityInfo.packageName);
            statusDefaultLauncher.setText(isDefault ? "Sudah Default" : "Atur ›");
        } catch (Exception ignored) {
            statusDefaultLauncher.setText("Atur ›");
        }
    }

    private void openHomeLauncherSettings(Context ctx) {
        if (ctx == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            Intent chooser = new Intent(Intent.ACTION_MAIN);
            chooser.addCategory(Intent.CATEGORY_HOME);
            chooser.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(chooser, "Pilih Launcher Utama"));
        } catch (Exception e) {
            Toast.makeText(ctx, "Buka Setelan Android > Beranda", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshCacheSizeAsync(Context appContext) {
        if (valueCacheSize == null || appContext == null) return;
        executor.execute(() -> {
            long bytes = dirSize(appContext.getCacheDir()) + dirSize(appContext.getExternalCacheDir());
            String formatted = formatSize(bytes);
            mainHandler.post(() -> {
                if (valueCacheSize != null) {
                    valueCacheSize.setText(formatted + " ›");
                }
            });
        });
    }

    private void clearAppCacheAsync(Context appContext) {
        executor.execute(() -> {
            long before = dirSize(appContext.getCacheDir()) + dirSize(appContext.getExternalCacheDir());
            deleteDirContents(appContext.getCacheDir());
            deleteDirContents(appContext.getExternalCacheDir());
            long after = dirSize(appContext.getCacheDir()) + dirSize(appContext.getExternalCacheDir());
            long freed = Math.max(0L, before - after);
            mainHandler.post(() -> {
                if (!isAdded()) return;
                if (valueCacheSize != null) {
                    valueCacheSize.setText(formatSize(after) + " ›");
                }
                Toast.makeText(getContext(), "Cache dibersihkan (" + formatSize(freed) + ")", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private static long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        File[] files = dir.listFiles();
        if (files == null) return 0L;
        long total = 0L;
        for (File f : files) {
            total += f.isDirectory() ? dirSize(f) : f.length();
        }
        return total;
    }

    private static void deleteDirContents(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                deleteDirContents(f);
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        long kb = bytes / 1024;
        if (kb < 1024) return kb + " KB";
        double mb = kb / 1024.0;
        return String.format(java.util.Locale.US, "%.1f MB", mb);
    }

    private void updateWallpaperStatus(Context appContext) {
        if (wallpaperStatus == null) return;
        try {
            String src = LauncherWallpaper.getSource(appContext);
            File cf = LauncherWallpaper.getFile(appContext);
            File bf = LauncherWallpaper.getBingFile(appContext);

            if (LauncherWallpaper.SOURCE_CUSTOM.equals(src) && cf != null && cf.exists() && cf.length() > 0) {
                long kb = Math.max(1, cf.length() / 1024);
                wallpaperStatus.setText("Status: Custom (" + kb + " KB)");
                return;
            }

            if (bf != null && bf.exists() && bf.length() > 0) {
                String date = LauncherWallpaper.getBingDate(appContext);
                wallpaperStatus.setText(date != null && !date.isEmpty() ? ("Status: Bing (" + date + ")") : "Status: Bing");
                return;
            }

            if (cf != null && cf.exists() && cf.length() > 0) {
                long kb = Math.max(1, cf.length() / 1024);
                wallpaperStatus.setText("Status: Custom (Legacy) (" + kb + " KB)");
                return;
            }

            wallpaperStatus.setText("Status: Default");
        } catch (Exception ignored) {
            wallpaperStatus.setText("Status: Default");
        }
    }

    private void pickWallpaperFile() {
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= 19) {
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
            } else {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
            }
            intent.setType("image/*");
            startActivityForResult(Intent.createChooser(intent, "Pilih wallpaper"), REQ_PICK_WALLPAPER);
        } catch (Exception e) {
            Toast.makeText(getContext(), "Tidak ada aplikasi untuk pilih file", Toast.LENGTH_SHORT).show();
        }
    }

    private void showWallpaperUrlDialog(Context appContext) {
        if (getActivity() == null) return;

        final EditText input = new EditText(getActivity());
        input.setHint("https://example.com/wallpaper.jpg");
        input.setSingleLine(true);

        new AlertDialog.Builder(getActivity())
                .setTitle("Set Wallpaper dari URL")
                .setMessage("Masukkan URL gambar (jpg/png).")
                .setView(input)
                .setNegativeButton("Batal", (d, w) -> d.dismiss())
                .setPositiveButton("Download", (d, w) -> {
                    String url = input.getText() != null ? input.getText().toString().trim() : "";
                    if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
                        Toast.makeText(getContext(), "URL tidak valid", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(getContext(), "Downloading wallpaper...", Toast.LENGTH_SHORT).show();
                    downloadWallpaperUrl(appContext, url);
                })
                .show();
    }

    private void downloadWallpaperUrl(Context appContext, String url) {
        executor.execute(() -> {
            DownloadResult result = downloadWallpaperOnce(appContext, url);

            if (!result.ok && result.sslError && url.startsWith("https://")) {
                String httpUrl = "http://" + url.substring("https://".length());
                result = downloadWallpaperOnce(appContext, httpUrl);
            }

            final boolean finalOk = result.ok;
            final String finalError = result.error;
            mainHandler.post(() -> {
                updateWallpaperStatus(appContext);
                loadSettingsWallpaper(appContext);
                Toast.makeText(getContext(), finalOk ? "Wallpaper tersimpan" : ("Gagal: " + (finalError != null ? finalError : "unknown")), Toast.LENGTH_SHORT).show();
            });
        });
    }

    private static final class DownloadResult {
        final boolean ok;
        final String error;
        final boolean sslError;

        DownloadResult(boolean ok, String error, boolean sslError) {
            this.ok = ok;
            this.error = error;
            this.sslError = sslError;
        }
    }

    private static DownloadResult downloadWallpaperOnce(Context appContext, String url) {
        boolean ok = false;
        String error = null;
        boolean sslError = false;
        Response resp = null;
        try {
            Request req = new Request.Builder().url(url).get().build();
            resp = buildWallpaperClient().newCall(req).execute();
            if (!resp.isSuccessful() || resp.body() == null) {
                error = "HTTP " + (resp != null ? resp.code() : 0);
            } else {
                long len = resp.body().contentLength();
                if (len > 0 && len > 25L * 1024L * 1024L) {
                    error = "File terlalu besar";
                } else {
                    try (InputStream is = resp.body().byteStream()) {
                        ok = LauncherWallpaper.save(appContext, is);
                    }
                    if (!ok) error = "Gagal simpan file";
                }
            }
        } catch (Exception e) {
            sslError = isSslError(e);
            error = e.getMessage();
        } finally {
            try { if (resp != null) resp.close(); } catch (Exception ignored) {}
        }
        return new DownloadResult(ok, error, sslError);
    }

    private static OkHttpClient buildWallpaperClient() {
        OkHttpClient client = NetworkClient.getWallpaperClient();
        return client != null ? client : NetworkClient.getClient();
    }

    private static boolean isSslError(Throwable t) {
        while (t != null) {
            if (t instanceof javax.net.ssl.SSLHandshakeException
                    || t instanceof java.security.cert.CertificateException
                    || t instanceof java.security.cert.CertPathValidatorException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_PICK_WALLPAPER && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null || getContext() == null) return;

            Context appContext = getContext().getApplicationContext();
            Toast.makeText(getContext(), "Menyimpan wallpaper...", Toast.LENGTH_SHORT).show();

            executor.execute(() -> {
                boolean ok = false;
                try (InputStream is = appContext.getContentResolver().openInputStream(uri)) {
                    ok = LauncherWallpaper.save(appContext, is);
                } catch (Exception ignored) {
                    ok = false;
                }

                final boolean finalOk = ok;
                mainHandler.post(() -> {
                    updateWallpaperStatus(appContext);
                    loadSettingsWallpaper(appContext);
                    Toast.makeText(getContext(), finalOk ? "Wallpaper tersimpan" : "Gagal simpan wallpaper", Toast.LENGTH_SHORT).show();
                });
            });
        }
    }

    private void loadSettingsWallpaper(Context appContext) {
        Bitmap cached = LauncherWallpaper.getCachedBitmap();
        if (cached != null) {
            try {
                if (wallpaperBg != null) wallpaperBg.setImageBitmap(cached);
                if (wallpaperPreviewThumb != null) wallpaperPreviewThumb.setImageBitmap(cached);
            } catch (Exception ignored) {
            }
        }

        executor.execute(() -> {
            Bitmap bmp = LauncherWallpaper.tryLoad(appContext);
            mainHandler.post(() -> {
                if (bmp != null) {
                    try {
                        if (wallpaperBg != null) wallpaperBg.setImageBitmap(bmp);
                        if (wallpaperPreviewThumb != null) wallpaperPreviewThumb.setImageBitmap(bmp);
                    } catch (Exception ignored) {
                    }
                }
            });
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        wallpaperBg = null;
        wallpaperPreviewThumb = null;
        wallpaperDimOverlay = null;
        wallpaperStatus = null;
        statusDefaultLauncher = null;
        valueCacheSize = null;
    }
}
