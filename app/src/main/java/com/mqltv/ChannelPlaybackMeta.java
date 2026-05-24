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
        String jenis = o.optString("jenis", "");
        String urlLicense = o.optString("url_license", "");
        String headerIptv = o.optString("header_iptv", "");
        String headerLicense = o.optString("header_license", "");
        ChannelPlaybackMeta meta = new ChannelPlaybackMeta(jenis, urlLicense, headerIptv, headerLicense, o.toString());
        return meta.isActive() ? meta : null;
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
        return j.contains("clearkey") || j.contains("dash");
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
