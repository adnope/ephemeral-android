package com.ephemeral.android.data.api;

import android.net.Uri;

import com.ephemeral.android.AppExecutors;
import com.ephemeral.android.data.model.FilePreview;
import com.ephemeral.android.data.model.HistoryQuery;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemMetadata;
import com.ephemeral.android.data.model.ItemType;
import com.ephemeral.android.data.model.ItemTypeFilter;
import com.ephemeral.android.data.model.Page;
import com.ephemeral.android.data.model.PublicLink;
import com.ephemeral.android.data.model.VisibilityFilter;
import com.ephemeral.android.data.session.SessionRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class FakeEphemeralApi implements EphemeralApi {
    private final AppExecutors executors;
    private final SessionRepository sessionRepository;
    private final RuntimeConfig runtimeConfig = DebugRuntimeDefaults.create();
    private final List<Item> items = new ArrayList<>();
    private final List<ItemEventListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong nextId = new AtomicLong(2000);
    private final java.util.Map<Long, PublicLink> publicLinks = new java.util.concurrent.ConcurrentHashMap<>();

    public FakeEphemeralApi(AppExecutors executors, SessionRepository sessionRepository) {
        this.executors = executors;
        this.sessionRepository = sessionRepository;
        seed();
    }

    @Override
    public void getServerState(ApiCallback<ServerState> callback) {
        delayedSuccess(callback, new ServerState(false));
    }

    @Override
    public void createFirstAccount(String username, String password, ApiCallback<AuthResult> callback) {
        authenticate(username, password, callback);
    }

    @Override
    public void login(String username, String password, ApiCallback<AuthResult> callback) {
        authenticate(username, password, callback);
    }

    @Override
    public void logout(ApiCallback<Void> callback) {
        sessionRepository.clearSession();
        listeners.clear();
        delayedSuccess(callback, null);
    }

    @Override
    public void validateSession(ApiCallback<AuthResult> callback) {
        if (sessionRepository.hasStoredSession()) {
            delayedSuccess(callback, new AuthResult(true, sessionRepository.getUsername()));
        } else {
            delayedError(callback, new ApiError(ApiErrorCategory.UNAUTHENTICATED, "Session is not authenticated."));
        }
    }

    @Override
    public void getRuntimeConfig(ApiCallback<RuntimeConfig> callback) {
        delayedSuccess(callback, runtimeConfig);
    }

    @Override
    public void loadChatPage(long cursor, ApiCallback<Page<Item>> callback) {
        delayedSuccess(callback, page(filterChatItems(), cursor, runtimeConfig.getChatPageSize()));
    }

    @Override
    public void sendTextMessage(String text, ApiCallback<Item> callback) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            delayedError(callback, new ApiError(ApiErrorCategory.VALIDATION_ERROR, "Message is empty."));
            return;
        }
        executors.network().execute(() -> {
            sleep(450);
            Item item = new Item(nextId.getAndIncrement(), ItemType.TEXT, trimmed, "", -1,
                    ItemMetadata.EMPTY, System.currentTimeMillis(), false);
            synchronized (items) {
                items.add(0, item);
            }
            executors.main().execute(() -> {
                callback.onSuccess(item);
                fire(new ItemEvent(ItemEventType.NEW, item.getId()));
            });
        });
    }

    @Override
    public Cancellable uploadFile(UploadRequest request, UploadProgressListener progress, ApiCallback<Item> callback) {
        UploadCancellation cancellation = new UploadCancellation();
        executors.network().execute(() -> {
            long total = request.getSizeBytes() > 0 ? request.getSizeBytes() : 1024L * 1024L;
            long written = 0;
            for (int step = 0; step < 8; step++) {
                if (cancellation.isCanceled()) {
                    postError(callback, new ApiError(ApiErrorCategory.CANCELED, "Upload canceled."));
                    return;
                }
                written = Math.min(total - 1, written + Math.max(1, total / 8));
                long current = written;
                executors.main().execute(() -> progress.onProgress(current, total));
                sleep(180);
            }
            ItemType type = inferType(request.getMimeType(), request.getDisplayName());
            ItemMetadata metadata = metadataFor(type, request.getMimeType());
            Item item = new Item(nextId.getAndIncrement(), type, "debug:" + request.getDisplayName(),
                    request.getDisplayName(), request.getSizeBytes(), metadata, System.currentTimeMillis(),
                    type == ItemType.FILE);
            synchronized (items) {
                items.add(0, item);
            }
            executors.main().execute(() -> {
                progress.onProgress(total, total);
                callback.onSuccess(item);
                fire(new ItemEvent(ItemEventType.NEW, item.getId()));
            });
        });
        return cancellation;
    }

    @Override
    public void deleteItem(long itemId, ApiCallback<Void> callback) {
        synchronized (items) {
            Iterator<Item> iterator = items.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getId() == itemId) {
                    iterator.remove();
                    break;
                }
            }
        }
        executors.main().execute(() -> {
            callback.onSuccess(null);
            fire(new ItemEvent(ItemEventType.DELETED, itemId));
        });
    }

    @Override
    public void loadHistoryPage(HistoryQuery query, ApiCallback<Page<Item>> callback) {
        List<Item> filtered = new ArrayList<>();
        String q = query.getQuery().toLowerCase(Locale.US);
        synchronized (items) {
            for (Item item : items) {
                if (!matchesTypeFilter(query.getTypeFilter(), item)) {
                    continue;
                }
                if (!q.isEmpty() && !matchesQuery(q, query.isSearchBody(), item)) {
                    continue;
                }
                if (query.getVisibility() == VisibilityFilter.PUBLIC) {
                    PublicLink pl = publicLinks.get(item.getId());
                    if (pl == null || !"active".equals(pl.getStatus())) {
                        continue;
                    }
                } else if (query.getVisibility() == VisibilityFilter.PRIVATE) {
                    PublicLink pl = publicLinks.get(item.getId());
                    if (pl != null && "active".equals(pl.getStatus())) {
                        continue;
                    }
                }
                filtered.add(item);
            }
        }
        delayedSuccess(callback, page(filtered, query.getCursor(), runtimeConfig.getHistoryPageSize()));
    }

    @Override
    public void loadTextPreview(long itemId, ApiCallback<FilePreview> callback) {
        Item item = findItem(itemId);
        if (item == null) {
            delayedError(callback, new ApiError(ApiErrorCategory.NOT_FOUND, "Item not found."));
            return;
        }
        if (!item.isPreviewable() && item.getType() != ItemType.TEXT) {
            delayedError(callback, new ApiError(ApiErrorCategory.UNSUPPORTED_PREVIEW, "Preview is not supported."));
            return;
        }
        String filename = item.getType() == ItemType.TEXT ? "message-" + item.getId() + ".txt" : item.getFilename();
        String content = item.getType() == ItemType.TEXT ? item.getContentRef()
                : "package main\n\nfunc main() {\n    println(\"Ephemeral preview\")\n}\n";
        FilePreview preview = new FilePreview(item.getId(), filename, "text/plain", "auto", content,
                content.length(), item.getCreatedAtEpochMillis(), item.getContentRef());
        delayedSuccess(callback, preview);
    }

    @Override
    public Cancellable downloadFile(FileDownloadRequest request, DownloadProgressListener progress,
            ApiCallback<FileDownloadResult> callback) {
        UploadCancellation cancellation = new UploadCancellation();
        executors.network().execute(() -> {
            long total = 1024L * 256L;
            for (int i = 1; i <= 4; i++) {
                if (cancellation.isCanceled()) {
                    postError(callback, new ApiError(ApiErrorCategory.CANCELED, "Download canceled."));
                    return;
                }
                long current = (total * i) / 4;
                executors.main().execute(() -> progress.onProgress(current, total));
                sleep(120);
            }
            executors.main().execute(() -> callback.onSuccess(new FileDownloadResult(Uri.EMPTY, request.getFilename())));
        });
        return cancellation;
    }

    @Override
    public Cancellable downloadZip(String ids, DownloadProgressListener progress,
            ApiCallback<FileDownloadResult> callback) {
        UploadCancellation cancellation = new UploadCancellation();
        executors.network().execute(() -> {
            long total = 1024L * 512L;
            for (int i = 1; i <= 4; i++) {
                if (cancellation.isCanceled()) {
                    postError(callback, new ApiError(ApiErrorCategory.CANCELED, "Download canceled."));
                    return;
                }
                long current = (total * i) / 4;
                executors.main().execute(() -> progress.onProgress(current, total));
                sleep(120);
            }
            executors.main().execute(() -> callback.onSuccess(new FileDownloadResult(Uri.EMPTY, "ephemeral_download.zip")));
        });
        return cancellation;
    }

    @Override
    public void getPublicLink(long itemId, ApiCallback<PublicLink> callback) {
        PublicLink link = publicLinks.get(itemId);
        if (link == null) {
            link = new PublicLink("none", null, null, null);
        }
        delayedSuccess(callback, link);
    }

    @Override
    public void createPublicLink(long itemId, Long expiresInSeconds, ApiCallback<PublicLink> callback) {
        String token = "fake_token_" + itemId;
        String expiresAt = null;
        if (expiresInSeconds != null) {
            java.time.Instant expiry = java.time.Instant.now().plusSeconds(expiresInSeconds);
            expiresAt = expiry.toString();
        }
        PublicLink link = new PublicLink("active", "/share/" + token, token, expiresAt);
        publicLinks.put(itemId, link);
        delayedSuccess(callback, link);
    }

    @Override
    public void revokePublicLink(long itemId, ApiCallback<Void> callback) {
        publicLinks.remove(itemId);
        delayedSuccess(callback, null);
    }

    @Override
    public EventSubscription observeItemEvents(ItemEventListener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void authenticate(String username, String password, ApiCallback<AuthResult> callback) {
        String cleanUsername = username == null ? "" : username.trim();
        if (cleanUsername.isEmpty() || password == null || password.isEmpty()) {
            delayedError(callback, new ApiError(ApiErrorCategory.VALIDATION_ERROR, "Username and password are required."));
            return;
        }
        if ("fail".equals(password)) {
            delayedError(callback, new ApiError(ApiErrorCategory.UNAUTHENTICATED, "Invalid credentials."));
            return;
        }
        sessionRepository.markAuthenticated(cleanUsername);
        delayedSuccess(callback, new AuthResult(true, cleanUsername));
    }

    private List<Item> filterChatItems() {
        synchronized (items) {
            return new ArrayList<>(items);
        }
    }

    private boolean isLinkActive(long itemId) {
        PublicLink pl = publicLinks.get(itemId);
        return pl != null && "active".equals(pl.getStatus());
    }

    private Page<Item> page(List<Item> source, long cursor, int size) {
        int start = 0;
        if (cursor > 0) {
            for (int i = 0; i < source.size(); i++) {
                if (source.get(i).getId() == cursor) {
                    start = i + 1;
                    break;
                }
            }
        }
        int end = Math.min(source.size(), start + size);
        List<Item> pageItems = source.subList(start, end);
        List<Item> mappedItems = new ArrayList<>();
        for (Item item : pageItems) {
            mappedItems.add(item.withPublicLinkActive(isLinkActive(item.getId())));
        }
        boolean hasMore = end < source.size();
        long nextCursor = hasMore && !mappedItems.isEmpty() ? mappedItems.get(mappedItems.size() - 1).getId() : 0;
        return new Page<>(mappedItems, nextCursor, hasMore);
    }

    private void fire(ItemEvent event) {
        for (ItemEventListener listener : listeners) {
            listener.onEvent(event);
        }
    }

    private Item findItem(long itemId) {
        synchronized (items) {
            for (Item item : items) {
                if (item.getId() == itemId) {
                    return item;
                }
            }
        }
        return null;
    }

    private boolean matchesTypeFilter(ItemTypeFilter filter, Item item) {
        if (filter == ItemTypeFilter.ALL) {
            return item.getType() != com.ephemeral.android.data.model.ItemType.TEXT;
        }
        if (filter == ItemTypeFilter.IMAGES) {
            return item.getType() == ItemType.IMAGE;
        }
        if (filter == ItemTypeFilter.VIDEOS) {
            return item.getType() == ItemType.VIDEO;
        }
        return item.getType() == ItemType.FILE;
    }

    private boolean matchesQuery(String query, boolean searchBody, Item item) {
        String filename = item.getFilename().toLowerCase(Locale.US);
        String content = item.getContentRef().toLowerCase(Locale.US);
        String previewBody = item.isPreviewable() ? "deploy notes ephemeral preview package main" : "";
        return filename.contains(query) || content.contains(query) || (searchBody && previewBody.contains(query));
    }

    private ItemType inferType(String mime, String displayName) {
        String cleanMime = mime == null ? "" : mime.toLowerCase(Locale.US);
        String cleanName = displayName == null ? "" : displayName.toLowerCase(Locale.US);
        if (cleanMime.startsWith("image/") || cleanName.endsWith(".jpg") || cleanName.endsWith(".png")) {
            return ItemType.IMAGE;
        }
        if (cleanMime.startsWith("video/") || cleanName.endsWith(".mp4")) {
            return ItemType.VIDEO;
        }
        return ItemType.FILE;
    }

    private ItemMetadata metadataFor(ItemType type, String mime) {
        if (type == ItemType.IMAGE) {
            return new ItemMetadata(1440, 960, "", mime, "");
        }
        if (type == ItemType.VIDEO) {
            return new ItemMetadata(1920, 1080, "00:12", mime, "");
        }
        return new ItemMetadata(0, 0, "", mime, "");
    }

    private <T> void delayedSuccess(ApiCallback<T> callback, T value) {
        executors.network().execute(() -> {
            sleep(180);
            executors.main().execute(() -> callback.onSuccess(value));
        });
    }

    private <T> void delayedError(ApiCallback<T> callback, ApiError error) {
        executors.network().execute(() -> {
            sleep(180);
            postError(callback, error);
        });
    }

    private <T> void postError(ApiCallback<T> callback, ApiError error) {
        executors.main().execute(() -> callback.onError(error));
    }

    private void seed() {
        long now = System.currentTimeMillis();
        items.add(new Item(1004, ItemType.TEXT, "Paste a note, attach files, then pull them from another device.",
                "", -1, ItemMetadata.EMPTY, now - 60_000L, false));
        items.add(new Item(1003, ItemType.IMAGE, "debug:image:desk", "desk-shot.jpg", 742_912,
                new ItemMetadata(1280, 853, "", "image/jpeg", ""), now - 4 * 60_000L, false));
        items.add(new Item(1002, ItemType.VIDEO, "debug:video:demo", "demo-clip.mp4", 4_812_128,
                new ItemMetadata(1920, 1080, "00:18", "video/mp4", ""), now - 14 * 60_000L, false));
        items.add(new Item(1001, ItemType.FILE, "debug:file:notes", "deploy-notes.txt", 12_842,
                new ItemMetadata(0, 0, "", "text/plain", ""), now - 45 * 60_000L, true));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class UploadCancellation implements Cancellable {
        private final AtomicBoolean canceled = new AtomicBoolean(false);

        @Override
        public void cancel() {
            canceled.set(true);
        }

        @Override
        public boolean isCanceled() {
            return canceled.get();
        }
    }
}
