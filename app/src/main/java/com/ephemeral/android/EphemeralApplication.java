package com.ephemeral.android;

import android.app.Application;

import com.ephemeral.android.data.api.EphemeralApi;
import com.ephemeral.android.data.session.SessionRepository;

public final class EphemeralApplication extends Application {
    private AppExecutors executors;
    private SessionRepository sessionRepository;
    private EphemeralApi api;

    @Override
    public void onCreate() {
        super.onCreate();
        executors = new AppExecutors();
        sessionRepository = new SessionRepository(this);
        api = BuildVariantApiFactory.create(this, executors, sessionRepository);
    }

    public AppExecutors getExecutors() {
        return executors;
    }

    public SessionRepository getSessionRepository() {
        return sessionRepository;
    }

    public EphemeralApi getApi() {
        return api;
    }

    @Override
    public void onTerminate() {
        executors.shutdown();
        super.onTerminate();
    }
}
