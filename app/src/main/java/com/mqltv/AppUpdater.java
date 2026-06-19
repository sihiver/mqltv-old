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
        
        // Lewati pengecekan jika belum 6 jam sejak pengecekan terakhir
        if (System.currentTimeMillis() - lastChecked < COOLDOWN_MS) {
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

    private static void installApk(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(context, "Izinkan MQLTV di Keamanan > Sumber Tidak Dikenal, lalu klik Instal lagi.", Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    context.startActivity(intent);
                } catch (Exception e) {
                    try {
                        Intent intent2 = new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS);
                        context.startActivity(intent2);
                    } catch (Exception ex) {
                        Log.e(TAG, "Gagal membuka setelan keamanan", ex);
                    }
                }
                return; // Stop di sini agar user bisa mengaktifkan izin dulu.
            }
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            File file = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Uri contentUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
                intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
            }
            
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Gagal membuka instalasi: " + e.getMessage());
            Toast.makeText(context, "Gagal membuka instalasi pembaruan.", Toast.LENGTH_LONG).show();
        }
    }
}
