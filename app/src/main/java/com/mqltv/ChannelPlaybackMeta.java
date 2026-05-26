package com.mqltv;

import android.content.Intent;
import android.text.TextUtils;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Vision+ / IndiHome-style playback metadata from JSON playlist entries.
 */
public final class ChannelPlaybackMeta {
    public static final String EXTRA_JSON = "extra_playback_meta_json";

    private final String jenis;
    private final String urlLicense;
    private final String headerIptvJson;
    private final String headerLicenseJson;
    private final String rawJson;

    public ChannelPlaybackMeta(String jenis, String urlLicense, String headerIptvJson,
                               String headerLicenseJson, String rawJson) {
        this.jenis = jenis != null ? jenis.trim() : "";
        this.urlLicense = urlLicense != null ? urlLicense.trim() : "";
        this.headerIptvJson = headerIptvJson != null ? headerIptvJson.trim() : "";
        this.headerLicenseJson = headerLicenseJson != null ? headerLicenseJson.trim() : "";
        this.rawJson = rawJson != null ? rawJson : "";
    }

    public static ChannelPlaybackMeta fromVisionPlusObject(JSONObject o) {
        if (o == null) return null;

        String jenis = o.optString("jenis", "").trim();
        String urlLicense = o.optString("url_license", "").trim();
        String drmKey = o.optString("drm_key", "").trim();
        if (urlLicense.isEmpty() && !drmKey.isEmpty()) {
            urlLicense = drmKey;
        }

        String drmType = o.optString("drm_type", "").trim().toLowerCase(Locale.US);
        if (jenis.isEmpty() && drmType.contains("clearkey")) {
            jenis = "dash-clearkey";
        } else if (jenis.isEmpty() && drmType.contains("widevine")) {
            jenis = "dash-widevine";
        } else if (jenis.isEmpty()
                && !urlLicense.isEmpty()
                && !VisionPlusDrmHelper.isHttpLicenseUrl(urlLicense)
                && looksLikeInlineClearKey(urlLicense)) {
            jenis = "dash-clearkey";
        }

        String headerIptv = o.optString("header_iptv", "");
        String userAgent = o.optString("user_agent", "").trim();
        if (!userAgent.isEmpty()) {
            headerIptv = mergeHeaderField(headerIptv, "User-Agent", userAgent);
        }
        String referer = o.optString("referer", "").trim();
        if (referer.isEmpty()) referer = o.optString("Referer", "").trim();
        if (!referer.isEmpty()) {
            headerIptv = mergeHeaderField(headerIptv, "Referer", referer);
        }
        String origin = o.optString("origin", "").trim();
        if (origin.isEmpty()) origin = o.optString("Origin", "").trim();
        if (!origin.isEmpty()) {
            headerIptv = mergeHeaderField(headerIptv, "Origin", origin);
        }

        String headerLicense = o.optString("header_license", "");
        ChannelPlaybackMeta meta = new ChannelPlaybackMeta(jenis, urlLicense, headerIptv, headerLicense, o.toString());
        return meta.isActive() ? meta : null;
    }

    private static boolean looksLikeInlineClearKey(String license) {
        if (license == null) return false;
        int colon = license.indexOf(':');
        if (colon <= 0 || colon >= license.length() - 1) return false;
        String kid = license.substring(0, colon).trim();
        String key = license.substring(colon + 1).trim();
        return isHexKeyMaterial(kid) && isHexKeyMaterial(key);
    }

    private static boolean isHexKeyMaterial(String s) {
        if (s == null || s.length() < 8 || s.length() > 64) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    private static String mergeHeaderField(String headerJson, String field, String value) {
        try {
            JSONObject o;
            if (TextUtils.isEmpty(headerJson) || "{}".equals(headerJson.trim())) {
                o = new JSONObject();
            } else {
                String body = headerJson.trim();
                if (body.startsWith("\"")) {
                    body = new JSONObject("{\"v\":" + body + "}").optString("v", body);
                }
                o = new JSONObject(body);
            }
            o.put(field, value);
            return o.toString();
        } catch (Exception e) {
            JSONObject o = new JSONObject();
            try {
                o.put(field, value);
                return o.toString();
            } catch (Exception ignored) {
                return headerJson;
            }
        }
    }

    public static ChannelPlaybackMeta fromIntent(Intent intent) {
        if (intent == null) return null;
        String raw = intent.getStringExtra(EXTRA_JSON);
        if (TextUtils.isEmpty(raw)) return null;
        try {
            return fromVisionPlusObject(new JSONObject(raw));
        } catch (Exception e) {
            return null;
        }
    }

    public void putInIntent(Intent intent) {
        if (intent == null || TextUtils.isEmpty(rawJson)) return;
        intent.putExtra(EXTRA_JSON, rawJson);
    }

    public String getJenis() {
        return jenis;
    }

    public String getUrlLicense() {
        return urlLicense;
    }

    public String getHeaderIptvJson() {
        return headerIptvJson;
    }

    public String getHeaderLicenseJson() {
        return headerLicenseJson;
    }

    public String getRawJson() {
        return rawJson;
    }

    /** True when JSON carries headers and/or DRM fields needed for playback. */
    public boolean isActive() {
        if (!TextUtils.isEmpty(urlLicense)) return true;
        if (!TextUtils.isEmpty(headerIptvJson) && !"{}".equals(headerIptvJson)) return true;
        if (!TextUtils.isEmpty(headerLicenseJson) && !"{}".equals(headerLicenseJson)) return true;
        String j = jenis.toLowerCase(Locale.US);
        return j.contains("clearkey") || j.contains("dash") || j.contains("widevine");
    }

    public boolean requiresExoDrm() {
        if (!TextUtils.isEmpty(urlLicense)) return true;
        String j = jenis.toLowerCase(Locale.US);
        return j.contains("clearkey") || j.contains("widevine");
    }

    public boolean isWidevine() {
        return jenis.toLowerCase(Locale.US).contains("widevine");
    }

    public boolean isClearKey() {
        String j = jenis.toLowerCase(Locale.US);
        return j.contains("clearkey")
                || (!isWidevine() && !TextUtils.isEmpty(urlLicense)
                && !VisionPlusDrmHelper.isHttpLicenseUrl(urlLicense));
    }

    public boolean preferDashSource(String streamUrl) {
        String j = jenis.toLowerCase(Locale.US);
        if (j.contains("dash")) return true;
        String u = streamUrl != null ? streamUrl.toLowerCase(Locale.US) : "";
        return u.contains(".mpd");
    }

    public boolean preferHlsSource(String streamUrl) {
        String j = jenis.toLowerCase(Locale.US);
        if (j.contains("hls")) return true;
        String u = streamUrl != null ? streamUrl.toLowerCase(Locale.US) : "";
        return u.contains(".m3u8");
    }
}
