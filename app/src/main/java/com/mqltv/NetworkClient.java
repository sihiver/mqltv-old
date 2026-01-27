package com.mqltv;

import android.content.Context;
import android.os.Build;
import android.util.Log;

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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.TlsVersion;

import org.conscrypt.Conscrypt;

public final class NetworkClient {
    private static final String TAG = "NetworkClient";
    private static volatile OkHttpClient CLIENT;
    private static volatile OkHttpClient UNSAFE_LOGO_CLIENT;
    private static volatile Context APP_CONTEXT;

    private NetworkClient() {
    }

    public static void init(Context context) {
        if (context != null) {
            APP_CONTEXT = context.getApplicationContext();
        }
        Log.d(TAG, "init: context=" + (APP_CONTEXT != null));
        CLIENT = buildClient();
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
    public static OkHttpClient getLogoClient(String host) {
        if (Build.VERSION.SDK_INT > 19) return getClient();
        if (host == null) return getClient();

        // Allowlist only.
        if (host.equalsIgnoreCase("images.indihometv.com") || host.toLowerCase().endsWith(".indihometv.com")) {
            if (UNSAFE_LOGO_CLIENT == null) {
                UNSAFE_LOGO_CLIENT = buildUnsafeLogoClient();
            }
            return UNSAFE_LOGO_CLIENT;
        }

        return getClient();
    }

    private static OkHttpClient buildClient() {
        if (Build.VERSION.SDK_INT <= 19) {
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

        if (Build.VERSION.SDK_INT <= 19) {
            enableTls12(builder, APP_CONTEXT);
        }

        return builder.build();
    }

    private static void enableTls12(OkHttpClient.Builder builder, Context context) {
        try {
            SSLContext sslContext;
            X509TrustManager trustManager;

            try {
                // Prefer Conscrypt trust store on old Android (bundled modern CAs).
                java.security.Provider provider = Conscrypt.newProvider();
                trustManager = getTrustManager(provider, context);
                sslContext = SSLContext.getInstance("TLS", provider);
                if (trustManager != null) {
                    sslContext.init(null, new TrustManager[] { trustManager }, null);
                } else {
                    sslContext.init(null, null, null);
                }
            } catch (Throwable ignored) {
                // Fallback to platform SSLContext.
                trustManager = getTrustManager(null, context);
                sslContext = SSLContext.getInstance("TLSv1.2");
                if (trustManager != null) {
                    sslContext.init(null, new TrustManager[] { trustManager }, null);
                } else {
                    sslContext.init(null, null, null);
                }
            }

            if (trustManager != null) {
                builder.sslSocketFactory(new Tls12SocketFactory(sslContext.getSocketFactory()), trustManager);
            }

            ConnectionSpec tls12 = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
                    .build();

            builder.connectionSpecs(Arrays.asList(tls12, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT));
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
        try (InputStream is = context.getResources().openRawResource(R.raw.digicert_indihome_ca)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);

            int index = 0;
            for (Certificate ca : cf.generateCertificates(is)) {
                ks.setCertificateEntry("extra_ca_" + index, ca);
                index++;
            }
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

    private static OkHttpClient buildUnsafeLogoClient() {
        try {
            final X509TrustManager trustAll = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

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

            HostnameVerifier verifier = (String hostname, SSLSession session) ->
                    hostname != null && (hostname.equalsIgnoreCase("images.indihometv.com")
                            || hostname.toLowerCase().endsWith(".indihometv.com"));

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
