package com.mqltv;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Resolves Vision+ ClearKey licenses. {@code url_license} is often {@code kid:key} (hex),
 * not an HTTP endpoint — using {@code HttpMediaDrmCallback} alone causes "DRM acquisition failed".
 */
public final class VisionPlusDrmHelper {
    private static final String TAG = "VisionPlusDrm";

  private static final Pattern HEX_KEY_PAIR = Pattern.compile(
            "^[0-9a-fA-F]{8,64}:[0-9a-fA-F]{8,64}$");

    private VisionPlusDrmHelper() {}

    public static boolean isHttpLicenseUrl(@Nullable String urlLicense) {
        if (TextUtils.isEmpty(urlLicense)) return false;
        String u = urlLicense.trim().toLowerCase(Locale.US);
        return u.startsWith("http://") || u.startsWith("https://");
    }

    /** Pre-resolved ClearKey JSON for inline licenses (kid:key, JSON blob). */
    @Nullable
    public static byte[] buildLocalClearKeyLicense(ChannelPlaybackMeta meta) {
        if (meta == null || TextUtils.isEmpty(meta.getUrlLicense())) return null;
        if (isHttpLicenseUrl(meta.getUrlLicense())) return null;
        try {
            return normalizeClearKeyLicense(meta.getUrlLicense().trim(), null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to build local ClearKey license", e);
            return null;
        }
    }

    /** Fetch / transform license for ExoPlayer key requests (HTTP or inline). */
    public static byte[] executeKeyRequest(ChannelPlaybackMeta meta, @Nullable byte[] challenge)
            throws IOException {
        if (meta == null || TextUtils.isEmpty(meta.getUrlLicense())) {
            throw new IOException("missing url_license");
        }
        String lic = meta.getUrlLicense().trim();
        if (isHttpLicenseUrl(lic)) {
            return fetchHttpLicense(meta, challenge);
        }
        return normalizeClearKeyLicense(lic, challenge);
    }

    public static Map<String, String> mergedLicenseHeaders(ChannelPlaybackMeta meta) {
        Map<String, String> headers = new HashMap<>();
        headers.putAll(VisionPlusPlayback.parseHeaderJson(meta.getHeaderLicenseJson()));
        headers.putAll(VisionPlusPlayback.parseHeaderJson(meta.getHeaderIptvJson()));
        return headers;
    }

    private static byte[] fetchHttpLicense(ChannelPlaybackMeta meta, @Nullable byte[] challenge)
            throws IOException {
        String url = meta.getUrlLicense().trim();
        Map<String, String> headers = mergedLicenseHeaders(meta);
        IOException lastError = null;

        // Standard DASH: POST challenge to license server.
        if (challenge != null && challenge.length > 0) {
            try {
                return httpRequest(url, headers, challenge, true);
            } catch (IOException e) {
                lastError = e;
                Log.w(TAG, "License POST failed, trying GET: " + e.getMessage());
            }
        }

        try {
            return httpRequest(url, headers, null, false);
        } catch (IOException e) {
            if (lastError != null) throw lastError;
            throw e;
        }
    }

    private static byte[] httpRequest(String url, Map<String, String> headers,
                                      @Nullable byte[] body, boolean post) throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            builder.header(e.getKey(), e.getValue());
        }
        if (post && body != null) {
            builder.post(RequestBody.create(null, body));
        } else {
            builder.get();
        }

        try (Response response = NetworkClient.getClient().newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("license HTTP " + response.code());
            }
            if (response.body() == null) {
                throw new IOException("empty license body");
            }
            String text = response.body().string();
            Log.d(TAG, "License response len=" + text.length());
            return normalizeClearKeyLicense(text.trim(), body);
        }
    }

    private static byte[] normalizeClearKeyLicense(String raw, @Nullable byte[] challenge)
            throws IOException {
        if (TextUtils.isEmpty(raw)) {
            throw new IOException("empty license");
        }

        String body = raw.trim();
        if (body.startsWith("\uFEFF")) {
            body = body.substring(1).trim();
        }

        // Already ClearKey JSON.
        if (body.startsWith("{")) {
            try {
                JSONObject root = new JSONObject(body);
                if (root.has("keys")) {
                    return body.getBytes(StandardCharsets.UTF_8);
                }
                if (root.has("license")) {
                    return normalizeClearKeyLicense(root.optString("license", ""), challenge);
                }
                if (root.has("data")) {
                    return normalizeClearKeyLicense(root.optString("data", ""), challenge);
                }
                if (root.has("key") && root.has("kid")) {
                    return wrapSingleKey(root.optString("kid", ""), root.optString("key", ""));
                }
            } catch (Exception e) {
                Log.w(TAG, "License JSON parse failed", e);
            }
        }

        // Vision+ / IPTV inline: kid:key (hex), e.g. "a1b2...:c3d4..."
        if (HEX_KEY_PAIR.matcher(body).matches()) {
            int colon = body.indexOf(':');
            String kidHex = body.substring(0, colon);
            String keyHex = body.substring(colon + 1);
            return wrapHexKeyPair(kidHex, keyHex);
        }

        // Single hex key — duplicate for kid (some exports).
        if (isHex(body) && body.length() >= 16 && body.length() <= 64) {
            return wrapHexKeyPair(body, body);
        }

        // Base64-encoded ClearKey JSON
        try {
            byte[] decoded = Base64.decode(body, Base64.DEFAULT);
            String decodedStr = new String(decoded, StandardCharsets.UTF_8).trim();
            if (decodedStr.startsWith("{")) {
                return normalizeClearKeyLicense(decodedStr, challenge);
            }
        } catch (Exception ignored) {
        }

        // Last resort: return as-is (server may already return raw JSON without braces check).
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] wrapSingleKey(String kid, String key) throws IOException {
        if (isHex(kid) && isHex(key)) {
            return wrapHexKeyPair(kid, key);
        }
        return buildClearKeyJson(kid, key);
    }

    private static byte[] wrapHexKeyPair(String kidHex, String keyHex) throws IOException {
        String kidB64 = hexToBase64Url(kidHex);
        String keyB64 = hexToBase64Url(keyHex);
        return buildClearKeyJson(kidB64, keyB64);
    }

    private static byte[] buildClearKeyJson(String kidB64Url, String keyB64Url) throws IOException {
        try {
            JSONObject keyObj = new JSONObject();
            keyObj.put("kty", "oct");
            keyObj.put("kid", kidB64Url);
            keyObj.put("k", keyB64Url);
            JSONArray keys = new JSONArray();
            keys.put(keyObj);
            JSONObject root = new JSONObject();
            root.put("keys", keys);
            root.put("type", "temporary");
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("build ClearKey JSON failed", e);
        }
    }

    private static String hexToBase64Url(String hex) throws IOException {
        byte[] bytes = hexToBytes(hex);
        if (bytes == null) {
            throw new IOException("invalid hex key");
        }
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    @Nullable
    private static byte[] hexToBytes(String hex) {
        if (hex == null) return null;
        hex = hex.trim();
        if ((hex.length() % 2) != 0) return null;
        try {
            int len = hex.length();
            byte[] out = new byte[len / 2];
            for (int i = 0; i < len; i += 2) {
                out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isHex(String s) {
        if (TextUtils.isEmpty(s)) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }
}
