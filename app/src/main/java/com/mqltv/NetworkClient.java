package com.mqltv;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public final class NetworkClient {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private NetworkClient() {
    }

    public static OkHttpClient getClient() {
        return CLIENT;
    }
}
