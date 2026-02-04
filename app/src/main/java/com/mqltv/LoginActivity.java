package com.mqltv;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LoginActivity extends FragmentActivity {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private EditText baseUrlInput;
    private EditText usernameInput;
    private EditText passwordInput;
    private Button loginBtn;
    private TextView status;

    public static final String EXTRA_AFTER_LOGIN_DEST = "afterLoginDest";
    public static final String DEST_LIVE_TV = "live_tv";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        baseUrlInput = findViewById(R.id.login_base_url);
        usernameInput = findViewById(R.id.login_username);
        passwordInput = findViewById(R.id.login_password);
        loginBtn = findViewById(R.id.login_button);
        status = findViewById(R.id.login_status);

        baseUrlInput.setText(AuthPrefs.getBaseUrl(this));
        usernameInput.setText(AuthPrefs.getUsername(this));

        if (loginBtn != null) {
            loginBtn.setOnClickListener(v -> doLogin());
        }

        // Focus on username by default (TV-friendly).
        if (usernameInput != null) {
            usernameInput.requestFocus();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void setBusy(boolean busy) {
        if (loginBtn != null) loginBtn.setEnabled(!busy);
        if (baseUrlInput != null) baseUrlInput.setEnabled(!busy);
        if (usernameInput != null) usernameInput.setEnabled(!busy);
        if (passwordInput != null) passwordInput.setEnabled(!busy);
        if (status != null) status.setVisibility(View.VISIBLE);
    }

    private void setStatus(String s) {
        if (status != null) {
            status.setText(s == null ? "" : s);
            status.setVisibility(View.VISIBLE);
        }
    }

    private static String joinUrl(String base, String path) {
        if (base == null) base = "";
        if (path == null) path = "";
        base = base.trim();
        path = path.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!path.startsWith("/")) path = "/" + path;
        return base + path;
    }

    private static String normalizeBaseUrl(String input) {
        if (input == null) return "";
        String s = input.trim();
        if (s.isEmpty()) return "";

        // Enforce a URL that looks like: http(s)://host[:port]
        // This avoids common mistakes like using http://host:8088/api (which becomes /api/public/login -> 404)
        Uri u = Uri.parse(s);
        String scheme = u.getScheme();
        String host = u.getHost();
        int port = u.getPort();

        if (scheme == null || host == null) {
            // Keep original to let validation show a clear message.
            return s;
        }

        String out = scheme + "://" + host;
        if (port != -1) {
            out += ":" + port;
        } else {
            // Our backend default is 8088; without a port Android will hit :80 and often return Apache 404.
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                out += ":8088";
            }
        }
        return out;
    }

    private static boolean looksLikeValidBaseUrl(String baseUrl) {
        try {
            Uri u = Uri.parse(baseUrl);
            return u.getScheme() != null && u.getHost() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isLocalhostBaseUrl(String baseUrl) {
        try {
            Uri u = Uri.parse(baseUrl);
            String host = u.getHost();
            if (host == null) return false;
            host = host.trim();
            return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String humanizeLoginError(Throwable e, String baseUrl) {
        if (e instanceof IOException) {
            if (isLocalhostBaseUrl(baseUrl)) {
                return "Gagal konek ke server. Jangan pakai 127.0.0.1/localhost di emulator/device. " +
                        "Pakai IP komputer server (contoh http://192.168.x.x:8088), " +
                        "atau 10.0.2.2:8088 (Android Emulator), atau 10.0.3.2:8088 (Genymotion).";
            }
            String msg = e.getMessage();
            return (msg == null || msg.trim().isEmpty()) ? "Gagal konek ke server" : msg;
        }
        String msg = e.getMessage();
        return (msg == null || msg.trim().isEmpty()) ? "Login gagal" : msg;
    }

    private void doLogin() {
        final String baseUrlRaw = baseUrlInput != null ? String.valueOf(baseUrlInput.getText()).trim() : "";
        final String baseUrl = normalizeBaseUrl(baseUrlRaw);
        final String username = usernameInput != null ? String.valueOf(usernameInput.getText()).trim() : "";
        final String password = passwordInput != null ? String.valueOf(passwordInput.getText()).trim() : "";

        if (baseUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
            setStatus("Base URL, username, dan password wajib diisi");
            return;
        }

        if (!looksLikeValidBaseUrl(baseUrl)) {
            setStatus("Base URL tidak valid. Contoh: http://192.168.56.1:8088");
            return;
        }

        if (baseUrlInput != null && !baseUrl.equals(baseUrlRaw)) {
            baseUrlInput.setText(baseUrl);
        }

        AuthPrefs.setBaseUrl(this, baseUrl);
        setBusy(true);
        setStatus("Logging in...");

        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("username", username);
                payload.put("password", password);

                String url = joinUrl(baseUrl, "/public/login");
                Request req = new Request.Builder()
                        .url(url)
                    .post(RequestBody.create(JSON, payload.toString()))
                        .header("Accept", "application/json")
                        .header("User-Agent", "MQLTV/1.0")
                        .build();

                try (Response resp = NetworkClient.getClient().newCall(req).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        String extra = "";
                        if (resp.code() == 404) {
                            extra = " - endpoint tidak ditemukan. Pastikan Base URL hanya host:port (tanpa /api atau /public).";
                        }
                        throw new RuntimeException("Login gagal (" + resp.code() + ")" + extra + "\nURL: " + url + (body.isEmpty() ? "" : ("\n" + body)));
                    }

                    JSONObject json = new JSONObject(body);
                    String publicPlaylistPath = json.optString("publicPlaylistUrl", "");
                    JSONObject userObj = json.optJSONObject("user");
                    String appKey = userObj != null ? userObj.optString("appKey", "") : "";
                    String expiresAt = userObj != null ? userObj.optString("expiresAt", "") : "";
                    String displayName = userObj != null ? userObj.optString("displayName", "") : "";
                    String plan = userObj != null ? userObj.optString("plan", "") : "";

                    String packagesRaw = "";
                    if (userObj != null) {
                        org.json.JSONArray pkgs = userObj.optJSONArray("packages");
                        if (pkgs != null && pkgs.length() > 0) {
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < pkgs.length(); i++) {
                                String p = pkgs.optString(i, "");
                                if (p == null) p = "";
                                p = p.trim();
                                if (p.isEmpty()) continue;
                                if (sb.length() > 0) sb.append("||");
                                sb.append(p);
                            }
                            packagesRaw = sb.toString();
                        }
                    }

                    if (publicPlaylistPath.trim().isEmpty()) {
                        throw new RuntimeException("Response login tidak valid");
                    }

                    String fullPlaylistUrl = joinUrl(baseUrl, publicPlaylistPath);
                    AuthPrefs.setLogin(getApplicationContext(), username, displayName, appKey, fullPlaylistUrl, plan, packagesRaw, expiresAt);
                }

                mainHandler.post(() -> {
                    setStatus("Login berhasil");

                    Intent i = new Intent(LoginActivity.this, MainActivity.class);
                    // If caller asked to go somewhere after login, keep that intent.
                    String dest = getIntent() != null ? getIntent().getStringExtra(EXTRA_AFTER_LOGIN_DEST) : null;
                    if (dest != null && !dest.trim().isEmpty()) {
                        i.putExtra(EXTRA_AFTER_LOGIN_DEST, dest);
                    }
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                });
            } catch (Throwable e) {
                mainHandler.post(() -> {
                    setBusy(false);
                    setStatus(humanizeLoginError(e, baseUrl));
                });
            }
        });
    }
}
