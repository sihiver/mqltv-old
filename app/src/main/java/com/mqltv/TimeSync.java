package com.mqltv;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class TimeSync {
    private static final String TAG = "TimeSync";

    private static volatile long sTimeOffsetMs = 0;
    private static volatile boolean sSynced = false;

    private static final String[] NTP_SERVERS = {
            "time.google.com",
            "id.pool.ntp.org",
            "pool.ntp.org",
            "time.cloudflare.com"
    };

    private static final String[] HTTP_TIME_SERVERS = {
            "https://www.google.com/generate_204",
            "https://www.bing.com",
            "https://www.cloudflare.com"
    };

    private TimeSync() {}

    public static Date getDate() {
        return new Date(currentTimeMillis());
    }

    public static long currentTimeMillis() {
        if (!sSynced) {
            return System.currentTimeMillis();
        }
        return SystemClock.elapsedRealtime() + sTimeOffsetMs;
    }

    public static boolean isSynced() {
        return sSynced;
    }

    public static synchronized void updateServerTime(long serverEpochMs) {
        if (serverEpochMs <= 0) return;
        long elapsed = SystemClock.elapsedRealtime();
        long newOffset = serverEpochMs - elapsed;

        long sysDiff = Math.abs(serverEpochMs - System.currentTimeMillis());
        if (sysDiff > 60_000) {
            Log.w(TAG, "Device clock is out of sync by " + (sysDiff / 1000) + "s! Applying TimeSync offset.");
        }
        sTimeOffsetMs = newOffset;
        sSynced = true;
    }

    public static void syncAsync(Context context) {
        new Thread(() -> {
            // 1. Try to ensure system NTP server is time.google.com if possible
            if (context != null) {
                try {
                    Settings.Global.putString(context.getContentResolver(), "ntp_server", "time.google.com");
                    Settings.Global.putInt(context.getContentResolver(), "auto_time", 1);
                } catch (Exception ignored) {
                }
            }

            // 2. Try SNTP directly
            for (String server : NTP_SERVERS) {
                try {
                    long ntpTime = requestNtpTime(server, 3000);
                    if (ntpTime > 0) {
                        updateServerTime(ntpTime);
                        Log.i(TAG, "Time successfully synced via NTP (" + server + "): " + new Date(ntpTime));
                        return;
                    }
                } catch (Exception e) {
                    Log.d(TAG, "NTP " + server + " failed: " + e.getMessage());
                }
            }

            // 3. Fallback: HTTP Date Header (works even when UDP 123 is blocked by ISP)
            OkHttpClient client = NetworkClient.getWallpaperClient();
            if (client == null) client = NetworkClient.getClient();

            SimpleDateFormat rfc1123 = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            rfc1123.setTimeZone(TimeZone.getTimeZone("GMT"));

            for (String url : HTTP_TIME_SERVERS) {
                Response resp = null;
                try {
                    Request req = new Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0")
                            .head()
                            .build();
                    resp = client.newCall(req).execute();
                    String dateStr = resp.header("Date");
                    if (dateStr != null && !dateStr.trim().isEmpty()) {
                        Date d = rfc1123.parse(dateStr.trim());
                        if (d != null && d.getTime() > 0) {
                            updateServerTime(d.getTime());
                            Log.i(TAG, "Time successfully synced via HTTP Date (" + url + "): " + d);
                            return;
                        }
                    }
                } catch (Exception e) {
                    Log.d(TAG, "HTTP time " + url + " failed: " + e.getMessage());
                } finally {
                    if (resp != null) {
                        try { resp.close(); } catch (Exception ignored) {}
                    }
                }
            }
        }, "TimeSyncThread").start();
    }

    private static long requestNtpTime(String host, int timeoutMs) throws Exception {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(timeoutMs);
            InetAddress address = InetAddress.getByName(host);

            byte[] buffer = new byte[48];
            // LI = 0 (no warning), VN = 3 (IPv4 only), Mode = 3 (Client) -> 0x1B
            buffer[0] = 0x1B;

            long requestTime = System.currentTimeMillis();
            writeTimeStamp(buffer, 40, requestTime);

            DatagramPacket request = new DatagramPacket(buffer, buffer.length, address, 123);
            socket.send(request);

            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);

            long responseTime = System.currentTimeMillis();

            long originateTime = readTimeStamp(buffer, 24);
            long receiveTime = readTimeStamp(buffer, 32);
            long transmitTime = readTimeStamp(buffer, 40);

            long clockOffset = ((receiveTime - originateTime) + (transmitTime - responseTime)) / 2;
            return responseTime + clockOffset;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private static long readTimeStamp(byte[] buffer, int offset) {
        long seconds = 0;
        for (int i = 0; i < 4; i++) {
            seconds = (seconds << 8) | (buffer[offset + i] & 0xFF);
        }
        long fraction = 0;
        for (int i = 4; i < 8; i++) {
            fraction = (fraction << 8) | (buffer[offset + i] & 0xFF);
        }
        // 70 years between 1900 and 1970 in seconds: 2208988800L
        return ((seconds - 2208988800L) * 1000) + ((fraction * 1000) / 0x100000000L);
    }

    private static void writeTimeStamp(byte[] buffer, int offset, long time) {
        long seconds = time / 1000L + 2208988800L;
        buffer[offset++] = (byte) (seconds >> 24);
        buffer[offset++] = (byte) (seconds >> 16);
        buffer[offset++] = (byte) (seconds >> 8);
        buffer[offset++] = (byte) seconds;

        long fraction = ((time % 1000L) * 0x100000000L) / 1000L;
        buffer[offset++] = (byte) (fraction >> 24);
        buffer[offset++] = (byte) (fraction >> 16);
        buffer[offset++] = (byte) (fraction >> 8);
        buffer[offset] = (byte) fraction;
    }
}
