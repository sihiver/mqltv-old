package com.mqltv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
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
    private static long downloadId = -1;

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
        String url = baseUrl + "api/app-updates/latest";

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
            File file = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk");
            if (file.exists() && file.length() > 0) {
                installApk(activity);
            } else {
                btnNow.setEnabled(false);
                btnLater.setEnabled(false);
                pbProgress.setVisibility(View.VISIBLE);
                startDownload(activity, apkUrl, pbProgress, dialog, btnNow, btnLater);
            }
        });

        dialog.show();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        btnNow.requestFocus();
    }

    private static void startDownload(Activity activity, String apkUrl, ProgressBar pbProgress, AlertDialog dialog, Button btnNow, Button btnLater) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
        request.setTitle("Mengunduh Pembaruan MQLTV");
        request.setDescription("Sedang mengunduh versi terbaru...");
        // Taruh di internal storage agar tidak perlu WRITE_EXTERNAL_STORAGE di Android >= 10
        request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "update.apk");

        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            Toast.makeText(activity, "DownloadManager tidak tersedia.", Toast.LENGTH_SHORT).show();
            return;
        }

        File file = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk");
        if (file.exists()) {
            file.delete();
        }

        downloadId = dm.enqueue(request);

        // Pantau progress via thread
        new Thread(() -> {
            boolean downloading = true;
            while (downloading) {
                DownloadManager.Query q = new DownloadManager.Query();
                q.setFilterById(downloadId);
                Cursor cursor = dm.query(q);
                if (cursor != null && cursor.moveToFirst()) {
                    int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int downloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                    int totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);

                    if (statusIndex >= 0 && downloadedIndex >= 0 && totalIndex >= 0) {
                        int status = cursor.getInt(statusIndex);
                        int bytesDownloaded = cursor.getInt(downloadedIndex);
                        int bytesTotal = cursor.getInt(totalIndex);

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            downloading = false;
                            new Handler(Looper.getMainLooper()).post(() -> {
                                pbProgress.setProgress(100);
                                btnNow.setText("Instal Sekarang");
                                btnNow.setEnabled(true);
                                btnLater.setEnabled(true);
                                installApk(activity);
                            });
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            downloading = false;
                            new Handler(Looper.getMainLooper()).post(() -> {
                                Toast.makeText(activity, "Gagal mengunduh pembaruan.", Toast.LENGTH_SHORT).show();
                                btnNow.setEnabled(true);
                                btnLater.setEnabled(true);
                                pbProgress.setVisibility(View.GONE);
                            });
                        } else {
                            if (bytesTotal > 0) {
                                int progress = (int) ((bytesDownloaded * 100L) / bytesTotal);
                                new Handler(Looper.getMainLooper()).post(() -> pbProgress.setProgress(progress));
                            }
                        }
                    }
                    cursor.close();
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {}
            }
        }).start();
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
        tvTitle.setText("Izin Diperlukan");
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(0xFFFFFFFF);
        layout.addView(tvTitle);

        TextView tvMsg = new TextView(activity);
        tvMsg.setText("Pembaruan membutuhkan izin \"Sumber Tidak Dikenal\".\n\n1. Tekan \"Buka Setelan\"\n2. Cari menu \"Keamanan & Batasan\" atau \"Instal aplikasi yang tidak diketahui\" di bagian bawah\n3. Aktifkan izin untuk MQLTV\n4. Kembali ke sini dan tekan Update lagi.");
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

        // Strategi 1: ACTION_APPLICATION_DETAILS_SETTINGS (Paling akurat & cuma 1)
        // Intent ini langsung membuka "Info Aplikasi" spesifik untuk MQLTV.
        // Di sini TIDAK AKAN MUNCUL 2 PILIHAN. Pengguna tinggal gulir ke bawah ke "Sumber tidak dikenal".
        Intent appInfo = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        appInfo.setData(Uri.parse("package:" + activity.getPackageName()));
        intents.add(appInfo);

        // Strategi 2: ACTION_APPLICATION_SETTINGS khusus untuk Android TV Settings
        Intent tvApps = new Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS);
        tvApps.setPackage("com.android.tv.settings");
        intents.add(tvApps);

        // Strategi 2: MANAGE_SPECIAL_APP_ACCESS (Akses Aplikasi Khusus) menggunakan literal string
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent i = new Intent("android.settings.MANAGE_SPECIAL_APP_ACCESS");
            intents.add(i);
        }

        // Strategi 3: Android TV Specific (Security)
        Intent tv2 = new Intent();
        tv2.setClassName("com.android.tv.settings", "com.android.tv.settings.security.SecurityActivity");
        intents.add(tv2);

        // Strategi 4: Standar Security Settings
        intents.add(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));

        // Strategi 5: Standar General Settings
        intents.add(new Intent(android.provider.Settings.ACTION_SETTINGS));

        // Strategi 6: ACTION_MANAGE_UNKNOWN_APP_SOURCES (dengan package)
        // Ditaruh PALING BAWAH karena OS Android TV sering menelan intent ini tanpa exception
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            i.setData(Uri.parse("package:" + activity.getPackageName()));
            intents.add(i);
        }

        // Strategi 7: ACTION_MANAGE_UNKNOWN_APP_SOURCES (tanpa package)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            intents.add(i);
        }

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
