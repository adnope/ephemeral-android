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
import com.ephemeral.android.data.model.PublicLink;
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
                    fetchActivePublicLinksAndDeliver(value, true, null, callback);
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
                    java.util.List<Item> filtered = new java.util.ArrayList<>();
                    for (Item item : value.getItems()) {
                        if (item.getType() != com.ephemeral.android.data.model.ItemType.TEXT) {
                            filtered.add(item);
                        }
                    }
                    Page<Item> filteredPage = new Page<>(filtered, value.getNextCursor(), value.hasMore());
                    fetchActivePublicLinksAndDeliver(filteredPage, false, query, callback);
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
    public Cancellable downloadZip(String ids, DownloadProgressListener progress,
            ApiCallback<FileDownloadResult> callback) {
        return remote.downloadZip(ids, progress, callback);
    }

    @Override
    public void getPublicLink(long itemId, ApiCallback<PublicLink> callback) {
        remote.getPublicLink(itemId, callback);
    }

    @Override
    public void createPublicLink(long itemId, Long expiresInSeconds, ApiCallback<PublicLink> callback) {
        remote.createPublicLink(itemId, expiresInSeconds, callback);
    }

    @Override
    public void revokePublicLink(long itemId, ApiCallback<Void> callback) {
        remote.revokePublicLink(itemId, callback);
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
                fetchActivePublicLinksAndDeliver(value, true, null, new ApiCallback<Page<Item>>() {
                    @Override
                    public void onSuccess(Page<Item> page) {
                        // Already cached by fetchActivePublicLinksAndDeliver
                    }

                    @Override
                    public void onError(ApiError error) {
                    }
                });
            }

            @Override
            public void onError(ApiError error) {
                // SSE-triggered cache refreshes are best effort; visible error handling stays with UI requests.
            }
        });
    }

    private void fetchActivePublicLinksAndDeliver(Page<Item> page, boolean isChat, HistoryQuery query, ApiCallback<Page<Item>> callback) {
        java.util.List<Item> items = page.getItems();
        if (items.isEmpty()) {
            if (isChat) {
                cache.cacheChatPage(page);
            } else {
                cache.cacheHistoryPage(query, page);
            }
            callback.onSuccess(page);
            return;
        }

        java.util.List<Item> targetItems = new java.util.ArrayList<>();
        for (Item item : items) {
            if (item.getType() != com.ephemeral.android.data.model.ItemType.TEXT) {
                targetItems.add(item);
            }
        }

        if (targetItems.isEmpty()) {
            if (isChat) {
                cache.cacheChatPage(page);
            } else {
                cache.cacheHistoryPage(query, page);
            }
            callback.onSuccess(page);
            return;
        }

        int totalRequests = targetItems.size();
        java.util.concurrent.atomic.AtomicInteger completedRequests = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.Map<Long, Boolean> activeStates = new java.util.concurrent.ConcurrentHashMap<>();

        for (Item item : targetItems) {
            remote.getPublicLink(item.getId(), new ApiCallback<PublicLink>() {
                @Override
                public void onSuccess(PublicLink link) {
                    boolean active = link != null && "active".equals(link.getStatus());
                    activeStates.put(item.getId(), active);
                    checkCompletion();
                }

                @Override
                public void onError(ApiError error) {
                    activeStates.put(item.getId(), false);
                    checkCompletion();
                }

                private void checkCompletion() {
                    if (completedRequests.incrementAndGet() == totalRequests) {
                        java.util.List<Item> updatedItems = new java.util.ArrayList<>();
                        for (Item it : items) {
                            if (it.getType() != com.ephemeral.android.data.model.ItemType.TEXT) {
                                Boolean active = activeStates.get(it.getId());
                                updatedItems.add(it.withPublicLinkActive(active != null && active));
                            } else {
                                updatedItems.add(it);
                            }
                        }
                        Page<Item> updatedPage = new Page<>(updatedItems, page.getNextCursor(), page.hasMore());
                        if (isChat) {
                            cache.cacheChatPage(updatedPage);
                        } else {
                            cache.cacheHistoryPage(query, updatedPage);
                        }
                        callback.onSuccess(updatedPage);
                    }
                }
            });
        }
    }

    private boolean isOffline(ApiError error) {
        ApiErrorCategory category = error.getCategory();
        return category == ApiErrorCategory.NETWORK_UNAVAILABLE || category == ApiErrorCategory.TIMEOUT;
    }
}
