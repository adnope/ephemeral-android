package com.ephemeral.android.data.cache;

import com.ephemeral.android.data.api.ApiCallback;
import com.ephemeral.android.data.api.ApiError;
import com.ephemeral.android.data.api.ApiErrorCategory;
import com.ephemeral.android.data.api.AuthResult;
import com.ephemeral.android.data.api.Cancellable;
import com.ephemeral.android.data.api.DownloadProgressListener;
import com.ephemeral.android.data.api.EphemeralApi;
import com.ephemeral.android.data.api.EventSubscription;
import com.ephemeral.android.data.api.FileDownloadRequest;
import com.ephemeral.android.data.api.FileDownloadResult;
import com.ephemeral.android.data.api.ItemEvent;
import com.ephemeral.android.data.api.ItemEventListener;
import com.ephemeral.android.data.api.ItemEventType;
import com.ephemeral.android.data.api.OkHttpEphemeralApi;
import com.ephemeral.android.data.api.RuntimeConfig;
import com.ephemeral.android.data.api.ServerState;
import com.ephemeral.android.data.api.UploadProgressListener;
import com.ephemeral.android.data.api.UploadRequest;
import com.ephemeral.android.data.model.FilePreview;
import com.ephemeral.android.data.model.HistoryQuery;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.Page;
import com.ephemeral.android.data.session.SessionRepository;

import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;

public final class CachedEphemeralApi implements EphemeralApi {
    private static final RuntimeConfig OFFLINE_RUNTIME_CONFIG =
            new RuntimeConfig(20, 30, 128L * 1024L * 1024L, 512L * 1024L, 2);

    private final EphemeralApi remote;
    private final ItemCacheStore cache;
    private final SessionRepository sessionRepository;
    private volatile RuntimeConfig runtimeConfig = OFFLINE_RUNTIME_CONFIG;

    public CachedEphemeralApi(EphemeralApi remote, ItemCacheStore cache,
            SessionRepository sessionRepository) {
        this.remote = remote;
        this.cache = cache;
        this.sessionRepository = sessionRepository;
    }

    public OkHttpClient getOkHttpClient() {
        if (remote instanceof OkHttpEphemeralApi) {
            return ((OkHttpEphemeralApi) remote).getClient();
        }
        return null;
    }

    @Override
    public void getServerState(ApiCallback<ServerState> callback) {
        remote.getServerState(callback);
    }

    @Override
    public void createFirstAccount(String username, String password, ApiCallback<AuthResult> callback) {
        remote.createFirstAccount(username, password, authCachingCallback(callback));
    }

    @Override
    public void login(String username, String password, ApiCallback<AuthResult> callback) {
        remote.login(username, password, authCachingCallback(callback));
    }

    @Override
    public void logout(ApiCallback<Void> callback) {
        cache.clear();
        remote.logout(callback);
    }

    @Override
    public void validateSession(ApiCallback<AuthResult> callback) {
        remote.validateSession(new ApiCallback<AuthResult>() {
            @Override
            public void onSuccess(AuthResult value) {
                callback.onSuccess(value);
            }

            @Override
            public void onError(ApiError error) {
                if (isOffline(error) && sessionRepository.hasStoredSession()) {
                    callback.onSuccess(new AuthResult(true, sessionRepository.getUsername()));
                    return;
                }
                if (error.isAuthenticationFailure()) {
                    cache.clear();
                }
                callback.onError(error);
            }
        });
    }

    @Override
    public void getRuntimeConfig(ApiCallback<RuntimeConfig> callback) {
        remote.getRuntimeConfig(new ApiCallback<RuntimeConfig>() {
            @Override
            public void onSuccess(RuntimeConfig value) {
                runtimeConfig = value;
                callback.onSuccess(value);
            }

            @Override
            public void onError(ApiError error) {
                if (isOffline(error) && sessionRepository.hasStoredSession()) {
                    callback.onSuccess(runtimeConfig);
                    return;
                }
                callback.onError(error);
            }
        });
    }

    @Override
    public void loadChatPage(long cursor, ApiCallback<Page<Item>> callback) {
        AtomicBoolean deliveredCache = new AtomicBoolean(false);
        cache.readChatPage(cursor, runtimeConfig.getChatPageSize(), page -> {
            if (!page.getItems().isEmpty()) {
                deliveredCache.set(true);
                callback.onSuccess(page);
            }
            remote.loadChatPage(cursor, new ApiCallback<Page<Item>>() {
                @Override
                public void onSuccess(Page<Item> value) {
                    cache.cacheChatPage(value);
                    callback.onSuccess(value);
                }

                @Override
                public void onError(ApiError error) {
                    if (!deliveredCache.get()) {
                        callback.onError(error);
                    }
                }
            });
        });
    }

    @Override
    public void sendTextMessage(String text, ApiCallback<Item> callback) {
        remote.sendTextMessage(text, itemCachingCallback(callback));
    }

    @Override
    public Cancellable uploadFile(UploadRequest request, UploadProgressListener progress,
            ApiCallback<Item> callback) {
        return remote.uploadFile(request, progress, itemCachingCallback(callback));
    }

    @Override
    public void deleteItem(long itemId, ApiCallback<Void> callback) {
        remote.deleteItem(itemId, new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void value) {
                cache.deleteItem(itemId);
                callback.onSuccess(value);
            }

            @Override
            public void onError(ApiError error) {
                callback.onError(error);
            }
        });
    }

    @Override
    public void loadHistoryPage(HistoryQuery query, ApiCallback<Page<Item>> callback) {
        AtomicBoolean deliveredCache = new AtomicBoolean(false);
        cache.readHistoryPage(query, runtimeConfig.getHistoryPageSize(), page -> {
            if (!page.getItems().isEmpty()) {
                deliveredCache.set(true);
                callback.onSuccess(page);
            }
            remote.loadHistoryPage(query, new ApiCallback<Page<Item>>() {
                @Override
                public void onSuccess(Page<Item> value) {
                    cache.cacheHistoryPage(query, value);
                    callback.onSuccess(value);
                }

                @Override
                public void onError(ApiError error) {
                    if (!deliveredCache.get()) {
                        callback.onError(error);
                    }
                }
            });
        });
    }

    @Override
    public void loadTextPreview(long itemId, ApiCallback<FilePreview> callback) {
        remote.loadTextPreview(itemId, callback);
    }

    @Override
    public Cancellable downloadFile(FileDownloadRequest request, DownloadProgressListener progress,
            ApiCallback<FileDownloadResult> callback) {
        return remote.downloadFile(request, progress, callback);
    }

    @Override
    public EventSubscription observeItemEvents(ItemEventListener listener) {
        return remote.observeItemEvents(new ItemEventListener() {
            @Override
            public void onEvent(ItemEvent event) {
                if (event.getType() == ItemEventType.DELETED) {
                    cache.deleteItem(event.getItemId());
                } else {
                    refreshLatestItems();
                }
                listener.onEvent(event);
            }

            @Override
            public void onError(ApiError error) {
                listener.onError(error);
            }
        });
    }

    private ApiCallback<AuthResult> authCachingCallback(ApiCallback<AuthResult> callback) {
        return new ApiCallback<AuthResult>() {
            @Override
            public void onSuccess(AuthResult value) {
                cache.clear();
                callback.onSuccess(value);
            }

            @Override
            public void onError(ApiError error) {
                callback.onError(error);
            }
        };
    }

    private ApiCallback<Item> itemCachingCallback(ApiCallback<Item> callback) {
        return new ApiCallback<Item>() {
            @Override
            public void onSuccess(Item value) {
                cache.cacheItem(value);
                callback.onSuccess(value);
            }

            @Override
            public void onError(ApiError error) {
                callback.onError(error);
            }
        };
    }

    private void refreshLatestItems() {
        remote.loadChatPage(0, new ApiCallback<Page<Item>>() {
            @Override
            public void onSuccess(Page<Item> value) {
                cache.cacheChatPage(value);
            }

            @Override
            public void onError(ApiError error) {
                // SSE-triggered cache refreshes are best effort; visible error handling stays with UI requests.
            }
        });
    }

    private boolean isOffline(ApiError error) {
        ApiErrorCategory category = error.getCategory();
        return category == ApiErrorCategory.NETWORK_UNAVAILABLE || category == ApiErrorCategory.TIMEOUT;
    }
}
