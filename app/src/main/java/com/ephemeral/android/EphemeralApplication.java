package com.ephemeral.android;

import android.app.Application;

import com.ephemeral.android.data.api.EphemeralApi;
import com.ephemeral.android.data.cache.CachedEphemeralApi;
import com.ephemeral.android.data.cache.EphemeralDatabase;
import com.ephemeral.android.data.cache.ItemCacheStore;
import com.ephemeral.android.data.session.SessionRepository;

public final class EphemeralApplication extends Application {
    private AppExecutors executors;
    private SessionRepository sessionRepository;
    private EphemeralApi api;
    private EphemeralDatabase database;

    @Override
    public void onCreate() {
        super.onCreate();
        executors = new AppExecutors();
        sessionRepository = new SessionRepository(this);
        database = EphemeralDatabase.create(this);
        EphemeralApi remoteApi = BuildVariantApiFactory.create(this, executors, sessionRepository);
        api = new CachedEphemeralApi(remoteApi, new ItemCacheStore(database, executors),
                sessionRepository);
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
        if (database != null) {
            database.close();
        }
        executors.shutdown();
        super.onTerminate();
    }
}
