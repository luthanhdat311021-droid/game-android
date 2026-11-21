package com.shadowduel.arena.data;

import android.os.Handler;
import android.os.Looper;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/** Background HTTP with OkHttp; results are posted back to the UI thread. */
public final class Net {
    private Net() {
    }

    public static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    public static void run(Runnable r) {
        POOL.execute(r);
    }

    public static void ui(Runnable r) {
        UI.post(r);
    }

    public static final class Response {
        public final int code;
        public final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    public static Response call(String method, String url, Map<String, String> headers, String body) throws java.io.IOException {
        Request.Builder b = new Request.Builder().url(url);
        for (Map.Entry<String, String> h : headers.entrySet()) b.header(h.getKey(), h.getValue());
        RequestBody rb = body == null ? null : RequestBody.create(body, MediaType.parse("application/json"));
        if ("GET".equals(method)) b.get();
        else b.method(method, rb == null && !"DELETE".equals(method) ? RequestBody.create(new byte[0]) : rb);
        try (okhttp3.Response r = HTTP.newCall(b.build()).execute()) {
            return new Response(r.code(), r.body() == null ? "" : r.body().string());
        }
    }
}
