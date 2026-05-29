package com.ephemeral.android.data.api;

import com.ephemeral.android.data.model.FilePreview;
import com.ephemeral.android.data.model.HistoryQuery;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.Page;
import com.ephemeral.android.data.model.PublicLink;

public interface EphemeralApi {
    void getServerState(ApiCallback<ServerState> callback);

    void createFirstAccount(String username, String password, ApiCallback<AuthResult> callback);

    void login(String username, String password, ApiCallback<AuthResult> callback);

    void logout(ApiCallback<Void> callback);

    void validateSession(ApiCallback<AuthResult> callback);

    void getRuntimeConfig(ApiCallback<RuntimeConfig> callback);

    void loadChatPage(long cursor, ApiCallback<Page<Item>> callback);

    void sendTextMessage(String text, ApiCallback<Item> callback);

    Cancellable uploadFile(UploadRequest request, UploadProgressListener progress, ApiCallback<Item> callback);

    void deleteItem(long itemId, ApiCallback<Void> callback);

    void loadHistoryPage(HistoryQuery query, ApiCallback<Page<Item>> callback);

    void loadTextPreview(long itemId, ApiCallback<FilePreview> callback);

    Cancellable downloadFile(FileDownloadRequest request, DownloadProgressListener progress,
            ApiCallback<FileDownloadResult> callback);

    Cancellable downloadZip(String ids, DownloadProgressListener progress,
            ApiCallback<FileDownloadResult> callback);

    void getPublicLink(long itemId, ApiCallback<PublicLink> callback);

    void createPublicLink(long itemId, Long expiresInSeconds, ApiCallback<PublicLink> callback);

    void revokePublicLink(long itemId, ApiCallback<Void> callback);

    EventSubscription observeItemEvents(ItemEventListener listener);
}
