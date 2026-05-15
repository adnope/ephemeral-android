package com.ephemeral.android;

import android.content.Context;

import com.ephemeral.android.data.api.EphemeralApi;
import com.ephemeral.android.data.api.OkHttpEphemeralApi;
import com.ephemeral.android.data.api.SessionCookieStore;
import com.ephemeral.android.data.session.SessionRepository;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public final class BuildVariantApiFactory {
    private BuildVariantApiFactory() {
    }

    public static EphemeralApi create(Context context, AppExecutors executors,
            SessionRepository sessionRepository) {
        SessionCookieStore cookieStore = new SessionCookieStore(context);
        OkHttpClient client = new OkHttpClient.Builder()
                .cookieJar(cookieStore)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build();
        return new OkHttpEphemeralApi(context, client, executors, sessionRepository, cookieStore);
    }

    public static String defaultBaseUrl() {
        return "";
    }
}
