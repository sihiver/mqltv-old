package com.mqltv;

import android.os.Build;

import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.TlsVersion;

public final class NetworkClient {
    private static final OkHttpClient CLIENT = buildClient();

    private NetworkClient() {
    }

    public static OkHttpClient getClient() {
        return CLIENT;
    }

    private static OkHttpClient buildClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true);

        if (Build.VERSION.SDK_INT <= 19) {
            enableTls12(builder);
        }

        return builder.build();
    }

    private static void enableTls12(OkHttpClient.Builder builder) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, null, null);

            X509TrustManager trustManager = getTrustManager();
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

    private static X509TrustManager getTrustManager() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
