package com.mqltv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

public class AppUpdater {

    private static final String TAG = "AppUpdater";

    private static final String PREFS_NAME = "AppUpdaterPrefs";
    private static final String KEY_LAST_CHECKED = "last_checked_time";
    private static final long COOLDOWN_MS = 6 * 60 * 60 * 1000L; // 6 jam

    public static void checkForUpdates(Activity activity) {
        android.content.SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastChecked = prefs.getLong(KEY_LAST_CHECKED, 0);
        
        boolean isDebug = (activity.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        
        // Lewati pengecekan jika belum 6 jam sejak pengecekan terakhir (kecuali saat Debug / Development)
        if (System.currentTimeMillis() - lastChecked < COOLDOWN_MS && !isDebug) {
            return;
        }

        String baseUrl = AuthPrefs.getBaseUrl(activity);
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        String url = baseUrl + "api/app-updates/latest?appId=" + activity.getPackageName();

        Request request = new Request.Builder()
                .url(url)
                .build();

        NetworkClient.getClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Gagal mengecek update: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    return;
                }
                
                // Simpan waktu pengecekan sukses agar cooldown 6 jam berfungsi
                prefs.edit().putLong(KEY_LAST_CHECKED, System.currentTimeMillis()).apply();
                
                try {
                    String json = response.body().string();
                    JSONObject obj = new JSONObject(json);

                    int latestVersionCode = obj.optInt("versionCode", 0);
                    String latestVersionName = obj.optString("versionName", "");
                    String releaseNotes = obj.optString("releaseNotes", "");
                    String apkUrl = obj.optString("apkUrl", "");
                    
                    // Jika URL pakai localhost, ganti dengan baseUrl server
                    if (apkUrl.contains("localhost")) {
                        String path = apkUrl.substring(apkUrl.indexOf("/public/"));
                        apkUrl = path; // Jadikan relatif
                    }
                    if (apkUrl.startsWith("/")) {
                        String bUrl = AuthPrefs.getBaseUrl(activity);
                        if (bUrl.endsWith("/")) bUrl = bUrl.substring(0, bUrl.length() - 1);
                        apkUrl = bUrl + apkUrl;
                    }
                    
                    boolean isForceUpdate = obj.optBoolean("isForceUpdate", false);

                    int currentVersionCode = 0;
                    try {
                        android.content.pm.PackageInfo pInfo = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                        currentVersionCode = pInfo.versionCode;
                    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                        e.printStackTrace();
                    }

                    final String finalApkUrl = apkUrl;

                    if (latestVersionCode > currentVersionCode && !finalApkUrl.isEmpty()) {
                        new Handler(Looper.getMainLooper()).post(() -> {
                            showUpdateDialog(activity, latestVersionName, releaseNotes, finalApkUrl, isForceUpdate);
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing update json: " + e.getMessage());
                }
            }
        });
    }

    private static void showUpdateDialog(Activity activity, String versionName, String notes, String apkUrl, boolean isForceUpdate) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_app_update, null);
        TextView tvVersion = view.findViewById(R.id.tvUpdateVersion);
        TextView tvNotes = view.findViewById(R.id.tvUpdateNotes);
        Button btnNow = view.findViewById(R.id.btnUpdateNow);
        Button btnLater = view.findViewById(R.id.btnUpdateLater);
        ProgressBar pbProgress = view.findViewById(R.id.pbUpdateProgress);

        tvVersion.setText("Versi Baru: " + versionName);
        tvNotes.setText(notes.isEmpty() ? "Ada pembaruan baru yang tersedia." : notes);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setView(view)
                .setCancelable(!isForceUpdate);

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(!isForceUpdate);

        if (isForceUpdate) {
            btnLater.setVisibility(View.GONE);
        } else {
            btnLater.setOnClickListener(v -> dialog.dismiss());
        }

        btnNow.setOnClickListener(v -> {
            btnNow.setEnabled(false);
            btnLater.setEnabled(false);
            pbProgress.setVisibility(View.VISIBLE);
            startDownload(activity, apkUrl, pbProgress, dialog, btnNow, btnLater);
        });

        dialog.show();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        btnNow.requestFocus();
    }

    private static void startDownload(Activity activity, String apkUrl, ProgressBar pbProgress, AlertDialog dialog, Button btnNow, Button btnLater) {
        String bypassUrl = apkUrl;
        if (bypassUrl.contains("?")) {
            bypassUrl += "&t=" + System.currentTimeMillis();
        } else {
            bypassUrl += "?t=" + System.currentTimeMillis();
        }

        Request request = new Request.Builder()
                .url(bypassUrl)
                .build();

        File file = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk");
        if (file.exists()) {
            file.delete();
        }

        NetworkClient.getClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(activity, "Gagal mengunduh pembaruan: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    btnNow.setEnabled(true);
                    btnLater.setEnabled(true);
                    pbProgress.setVisibility(View.GONE);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(activity, "Gagal mengunduh pembaruan (Server error)", Toast.LENGTH_SHORT).show();
                        btnNow.setEnabled(true);
                        btnLater.setEnabled(true);
                        pbProgress.setVisibility(View.GONE);
                    });
                    return;
                }

                okhttp3.ResponseBody body = response.body();
                if (body == null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(activity, "Gagal mengunduh pembaruan (Data kosong)", Toast.LENGTH_SHORT).show();
                        btnNow.setEnabled(true);
                        btnLater.setEnabled(true);
                        pbProgress.setVisibility(View.GONE);
                    });
                    return;
                }

                long totalBytes = body.contentLength();
                java.io.InputStream inputStream = null;
                java.io.FileOutputStream outputStream = null;
                try {
                    inputStream = body.byteStream();
                    outputStream = new java.io.FileOutputStream(file);

                    byte[] buffer = new byte[4096];
                    long bytesRead = 0;
                    int read;
                    long lastUpdateTime = 0;

                    while ((read = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, read);
                        bytesRead += read;

                        if (totalBytes > 0) {
                            final int progress = (int) ((bytesRead * 100) / totalBytes);
                            long now = System.currentTimeMillis();
                            if (now - lastUpdateTime > 100 || progress == 100) {
                                lastUpdateTime = now;
                                new Handler(Looper.getMainLooper()).post(() -> pbProgress.setProgress(progress));
                            }
                        }
                    }
                    outputStream.flush();

                    new Handler(Looper.getMainLooper()).post(() -> {
                        pbProgress.setProgress(100);
                        btnNow.setText("Instal Sekarang");
                        btnNow.setEnabled(true);
                        btnLater.setEnabled(true);
                        installApk(activity);
                    });

                } catch (IOException e) {
                    if (file.exists()) {
                        file.delete();
                    }
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(activity, "Gagal menulis file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        btnNow.setEnabled(true);
                        btnLater.setEnabled(true);
                        pbProgress.setVisibility(View.GONE);
                    });
                } finally {
                    if (inputStream != null) {
                        try {
                            inputStream.close();
                        } catch (IOException ignored) {}
                    }
                    if (outputStream != null) {
                        try {
                            outputStream.close();
                        } catch (IOException ignored) {}
                    }
                    body.close();
                }
            }
        });
    }

    /** Dialog peringatan izin: muncul sebelum download jika izin belum aktif */
    private static void showPermissionDialog(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);

        // Buat konten dialog secara programatik
        android.widget.LinearLayout layout = new android.widget.LinearLayout(activity);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        TextView tvTitle = new TextView(activity);
        tvTitle.setText("Peringatan Keamanan");
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(0xFFFFFFFF);
        layout.addView(tvTitle);

        TextView tvMsg = new TextView(activity);
        tvMsg.setText("Demi keamanan, TV Anda saat ini tidak diizinkan menginstal aplikasi yang tidak dikenal dari sumber ini.");
        tvMsg.setTextSize(14f);
        tvMsg.setTextColor(0xFFCCCCCC);
        tvMsg.setPadding(0, pad / 2, 0, pad);
        layout.addView(tvMsg);

        Button btnSettings = new Button(activity);
        btnSettings.setText("Buka Setelan");
        layout.addView(btnSettings);

        AlertDialog dialog = builder.setView(layout).setCancelable(true).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        btnSettings.setOnClickListener(v2 -> {
            dialog.dismiss();
            openUnknownSourcesSettings(activity);
        });

        dialog.show();
        btnSettings.requestFocus();
    }

    /** Multi-fallback untuk membuka setelan Sumber Tidak Dikenal (termasuk khusus Android TV) */
    private static void openUnknownSourcesSettings(Activity activity) {
        boolean success = false;
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP;
        java.util.List<Intent> intents = new java.util.ArrayList<>();

        // Strategi Utama: Pilihan langsung dari user (Hanya membuka Pengaturan Aplikasi TV)
        Intent tvApps = new Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS);
        tvApps.setPackage("com.android.tv.settings");
        intents.add(tvApps);

        // Fallback cadangan
        intents.add(new Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS));
        intents.add(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));
        intents.add(new Intent(android.provider.Settings.ACTION_SETTINGS));

        for (int j = 0; j < intents.size(); j++) {
            try {
                Intent i = intents.get(j);
                i.setFlags(flags);
                Log.d(TAG, "Mencoba fallback " + (j + 1) + ": " + i.getAction() + " / " + i.getComponent());
                activity.startActivity(i);
                Log.d(TAG, "Fallback " + (j + 1) + " BERHASIL diluncurkan tanpa exception!");
                success = true;
                break;
            } catch (Exception e) {
                Log.e(TAG, "Fallback " + (j + 1) + " GAGAL: " + e.getMessage());
            }
        }

        if (!success) {
            Log.e(TAG, "SEMUA FALLBACK SETELAN GAGAL DIBUKA!");
            Toast.makeText(activity, "Perangkat menolak membuka setelan. Silakan buka setelan manual.", Toast.LENGTH_LONG).show();
        }
    }

    public static void installApk(Activity activity) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            File file = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Uri contentUri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", file);
                intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
            }

            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Gagal membuka instalasi: " + e.getMessage());
            Toast.makeText(activity, "Gagal membuka instalasi pembaruan.", Toast.LENGTH_LONG).show();
        }
    }
}
