package com.mqltv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.InputStream;
import java.security.SecureRandom;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.TlsVersion;

import org.conscrypt.Conscrypt;

public final class NetworkClient {
    private static final String TAG = "NetworkClient";
    private static volatile OkHttpClient CLIENT;
    private static volatile OkHttpClient UNSAFE_LOGO_CLIENT;
    @SuppressLint("StaticFieldLeak")
    private static volatile Context APP_CONTEXT;

    private NetworkClient() {
    }

    public static void init(Context context) {
        if (context != null) {
            APP_CONTEXT = context.getApplicationContext();
        }
        Log.d(TAG, "init: context=" + (APP_CONTEXT != null));
        installLegacyHttpTlsDefaults(APP_CONTEXT);
        CLIENT = buildClient();
    }

    /** Media3 DefaultHttpDataSource uses HttpURLConnection; enable TLS 1.2 + modern CAs on API 25 and below. */
    private static void installLegacyHttpTlsDefaults(Context context) {
        if (Build.VERSION.SDK_INT > 25) return;
        try {
            SSLContext sslContext = createLegacySslContext(context);
            if (sslContext != null) {
                if (Build.VERSION.SDK_INT <= 19) {
                    HttpsURLConnection.setDefaultSSLSocketFactory(new Tls12SocketFactory(sslContext.getSocketFactory()));
                } else {
                    HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
                }
                Log.d(TAG, "HttpsURLConnection TLS defaults installed (Conscrypt)");
            }
        } catch (Exception e) {
            Log.w(TAG, "HttpsURLConnection TLS setup failed", e);
        }
    }

    @Nullable
    private static SSLContext createLegacySslContext(Context context) {
        try {
            java.security.Provider provider = Conscrypt.newProvider();
            X509TrustManager trustManager = getTrustManager(provider, context);
            if (trustManager != null) {
                SSLContext sslContext = SSLContext.getInstance("TLS", provider);
                sslContext.init(null, new TrustManager[] { trustManager }, null);
                return sslContext;
            }
        } catch (Throwable ignored) {
        }
        try {
            X509TrustManager trustManager = getTrustManager(null, context);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            if (trustManager != null) {
                sslContext.init(null, new TrustManager[] { trustManager }, null);
            } else {
                sslContext.init(null, null, null);
            }
            return sslContext;
        } catch (Exception e) {
            return null;
        }
    }

    public static OkHttpClient getClient() {
        if (CLIENT == null) {
            CLIENT = buildClient();
        }
        return CLIENT;
    }

    /**
     * For a few CDNs that fail TLS validation on Android 4.2/4.4 due to missing CA store,
     * we use a scoped "unsafe" client to download channel logos only.
     */
    public static OkHttpClient getLogoClient(String ignoredHost) {
        if (Build.VERSION.SDK_INT > 19) return getClient();
        if (UNSAFE_LOGO_CLIENT == null) {
            UNSAFE_LOGO_CLIENT = buildUnsafeLogoClient();
        }
        return UNSAFE_LOGO_CLIENT;
    }

    private static volatile OkHttpClient RAW_CLIENT;

    private static OkHttpClient getRawClient() {
        if (RAW_CLIENT == null) {
            synchronized (NetworkClient.class) {
                if (RAW_CLIENT == null) {
                    OkHttpClient.Builder builder = new OkHttpClient.Builder()
                            .connectTimeout(8, TimeUnit.SECONDS)
                            .readTimeout(15, TimeUnit.SECONDS)
                            .writeTimeout(15, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true);
                    if (Build.VERSION.SDK_INT <= 25) {
                        enableTls12(builder, APP_CONTEXT);
                    }
                    RAW_CLIENT = builder.build();
                }
            }
        }
        return RAW_CLIENT;
    }

    private static OkHttpClient buildClient() {
        if (Build.VERSION.SDK_INT <= 25) {
            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1);
            } catch (Exception ignored) {
            }
        }

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true);

        builder.addInterceptor(chain -> {
            okhttp3.Request original = chain.request();
            if (APP_CONTEXT != null) {
                String token = AuthPrefs.getAccessToken(APP_CONTEXT);
                if (!token.trim().isEmpty()) {
                    // Jangan timpa header Authorization jika request sudah memilikinya (misalnya saat request stream info dengan token tersendiri)
                    if (original.header("Authorization") == null) {
                        okhttp3.Request request = original.newBuilder()
                                .header("Authorization", "Bearer " + token)
                                .build();
                        return chain.proceed(request);
                    }
                }
            }
            return chain.proceed(original);
        });

        builder.authenticator((route, response) -> {
            if (responseCount(response) >= 3) {
                return null;
            }

            if (APP_CONTEXT == null) return null;

            String refreshToken = AuthPrefs.getRefreshToken(APP_CONTEXT);
            String baseUrl = AuthPrefs.getBaseUrl(APP_CONTEXT);
            String username = AuthPrefs.getUsername(APP_CONTEXT);
            String password = AuthPrefs.getPassword(APP_CONTEXT);

            if (baseUrl.trim().isEmpty()) return null;

            synchronized (NetworkClient.class) {
                String currentToken = AuthPrefs.getAccessToken(APP_CONTEXT);
                String reqAuth = response.request().header("Authorization");
                if (reqAuth != null && reqAuth.startsWith("Bearer ")) {
                    String tokenInReq = reqAuth.substring(7).trim();
                    if (!tokenInReq.equals(currentToken) && !currentToken.isEmpty()) {
                        return response.request().newBuilder()
                                .header("Authorization", "Bearer " + currentToken)
                                .build();
                    }
                }

                // 1. Try refreshing with refresh token
                if (!refreshToken.trim().isEmpty()) {
                    try {
                        org.json.JSONObject payload = new org.json.JSONObject();
                        payload.put("refreshToken", refreshToken);

                        String refreshUrl = joinUrl(baseUrl, "/api/auth/refresh");
                        okhttp3.Request refreshReq = new okhttp3.Request.Builder()
                                .url(refreshUrl)
                                .post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), payload.toString()))
                                .header("Accept", "application/json")
                                .header("User-Agent", "MQLTV/1.0")
                                .build();

                        try (Response refreshResp = getRawClient().newCall(refreshReq).execute()) {
                            if (refreshResp.isSuccessful() && refreshResp.body() != null) {
                                String bodyStr = refreshResp.body().string();
                                org.json.JSONObject json = new org.json.JSONObject(bodyStr);
                                String newToken = json.optString("token", "");
                                String newRefreshToken = json.optString("refreshToken", json.optString("refresh_token", ""));

                                if (!newToken.trim().isEmpty()) {
                                    AuthPrefs.updateTokens(APP_CONTEXT, newToken, newRefreshToken);
                                    return response.request().newBuilder()
                                            .header("Authorization", "Bearer " + newToken)
                                            .build();
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Automatic token refresh failed: " + e.getMessage());
                    }
                }

                // 2. Fallback: Auto-relogin if refresh token expired (e.g. STB off > 30 days)
                if (!username.trim().isEmpty() && !password.isEmpty()) {
                    try {
                        org.json.JSONObject loginPayload = new org.json.JSONObject();
                        loginPayload.put("email", username.trim());
                        loginPayload.put("password", password);

                        String loginUrl = joinUrl(baseUrl, "/api/auth/login");
                        okhttp3.Request loginReq = new okhttp3.Request.Builder()
                                .url(loginUrl)
                                .post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), loginPayload.toString()))
                                .header("Accept", "application/json")
                                .header("User-Agent", "MQLTV/1.0")
                                .build();

                        try (Response loginResp = getRawClient().newCall(loginReq).execute()) {
                            if (loginResp.isSuccessful() && loginResp.body() != null) {
                                String bodyStr = loginResp.body().string();
                                org.json.JSONObject json = new org.json.JSONObject(bodyStr);
                                String newToken = json.optString("token", "");
                                String newRefreshToken = json.optString("refreshToken", json.optString("refresh_token", ""));
                                org.json.JSONObject userObj = json.optJSONObject("user");
                                String displayName = userObj != null ? userObj.optString("name", "") : "";
                                String plan = userObj != null ? userObj.optString("plan", "") : "";
                                String expiresAt = userObj != null ? userObj.optString("expiresAt", "") : "";

                                if (!newToken.trim().isEmpty()) {
                                    AuthPrefs.setLogin(APP_CONTEXT, username, password, displayName, newToken, newRefreshToken, plan, "", expiresAt);
                                    return response.request().newBuilder()
                                            .header("Authorization", "Bearer " + newToken)
                                            .build();
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Auto-relogin fallback failed: " + e.getMessage());
                    }
                }
            }
            return null;
        });

        if (Build.VERSION.SDK_INT <= 25) {
            enableTls12(builder, APP_CONTEXT);
        }

        return builder.build();
    }

    private static int responseCount(Response response) {
        int result = 1;
        while ((response = response.priorResponse()) != null) {
            result++;
        }
        return result;
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

    private static void enableTls12(OkHttpClient.Builder builder, Context context) {
        try {
            SSLContext sslContext = createLegacySslContext(context);
            X509TrustManager trustManager = getTrustManager(Conscrypt.newProvider(), context);
            if (trustManager == null) {
                trustManager = getTrustManager(null, context);
            }
            if (sslContext != null && trustManager != null) {
                if (Build.VERSION.SDK_INT <= 19) {
                    builder.sslSocketFactory(new Tls12SocketFactory(sslContext.getSocketFactory()), trustManager);
                } else {
                    builder.sslSocketFactory(sslContext.getSocketFactory(), trustManager);
                }
            }

            if (Build.VERSION.SDK_INT <= 19) {
                ConnectionSpec tls12 = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
                        .build();

                builder.connectionSpecs(Arrays.asList(tls12, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT));
            }
        } catch (Exception ignored) {
            // If TLS 1.2 setup fails, fall back to default behavior.
        }
    }

    private static X509TrustManager getTrustManager(java.security.Provider provider, Context context) {
        try {
            TrustManagerFactory tmf = provider == null
                    ? TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    : TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm(), provider);
            tmf.init((KeyStore) null);
            X509TrustManager systemTm = null;
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    systemTm = (X509TrustManager) tm;
                    break;
                }
            }

            X509TrustManager extraTm = buildExtraTrustManager(provider, context);
            if (systemTm != null && extraTm != null) {
                return new CompositeTrustManager(systemTm, extraTm);
            }
            if (systemTm != null) return systemTm;
            if (extraTm != null) return extraTm;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static X509TrustManager buildExtraTrustManager(java.security.Provider provider, Context context) {
        if (context == null) return null;
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);

            int index = 0;
            index = loadExtraCaFromRaw(context, cf, ks, R.raw.digicert_indihome_ca, index);
            index = loadExtraCaFromRaw(context, cf, ks, R.raw.digicert_global_root_g2, index);
            index = loadExtraCaFromRaw(context, cf, ks, R.raw.digicert_global_root_g3, index);
            index = loadExtraCaFromRaw(context, cf, ks, R.raw.isrg_root_x1, index);
            Log.d(TAG, "extra CA loaded count=" + index);

            TrustManagerFactory tmf = provider == null
                    ? TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    : TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm(), provider);
            tmf.init(ks);

            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "extra CA load failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    private static int loadExtraCaFromRaw(Context context, CertificateFactory cf, KeyStore ks, int resId, int index) {
        if (context == null || cf == null || ks == null) return index;
        try (InputStream is = context.getResources().openRawResource(resId)) {
            for (Certificate ca : cf.generateCertificates(is)) {
                ks.setCertificateEntry("extra_ca_" + index, ca);
                index++;
            }
        } catch (Exception e) {
            Log.w(TAG, "extra CA raw=" + resId + " load failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return index;
    }

    private static OkHttpClient buildUnsafeLogoClient() {
        try {
            @SuppressLint("CustomX509TrustManager") final X509TrustManager trustAll = new X509TrustManager() {
                @SuppressLint("TrustAllX509TrustManager")
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @SuppressLint("TrustAllX509TrustManager")
                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] { trustAll }, new SecureRandom());

            HostnameVerifier verifier = (String hostname, SSLSession session) -> true;

            ConnectionSpec tls = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
                    .build();

            return new OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .sslSocketFactory(new Tls12SocketFactory(sslContext.getSocketFactory()), trustAll)
                    .hostnameVerifier(verifier)
                    .connectionSpecs(Arrays.asList(tls, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
                    .build();
        } catch (Exception e) {
            Log.w(TAG, "unsafe logo client init failed: " + e.getMessage());
            return new OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
        }
    }

    @SuppressLint("CustomX509TrustManager")
    private static final class CompositeTrustManager implements X509TrustManager {
        private final X509TrustManager primary;
        private final X509TrustManager secondary;

        private CompositeTrustManager(X509TrustManager primary, X509TrustManager secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                primary.checkClientTrusted(chain, authType);
            } catch (CertificateException e) {
                secondary.checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                primary.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                secondary.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] a = primary.getAcceptedIssuers();
            X509Certificate[] b = secondary.getAcceptedIssuers();
            X509Certificate[] out = new X509Certificate[a.length + b.length];
            System.arraycopy(a, 0, out, 0, a.length);
            System.arraycopy(b, 0, out, a.length, b.length);
            return out;
        }
    }
}
