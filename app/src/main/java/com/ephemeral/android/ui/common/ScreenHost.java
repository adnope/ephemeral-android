package com.ephemeral.android.ui.common;

import com.ephemeral.android.data.model.Item;

import java.util.List;

public interface ScreenHost {
    void showChat();

    void showHistory();

    void openMediaViewer(List<Item> mediaItems, int startIndex);

    void openTextPreview(Item item);

    void closeOverlay();

    void confirmDelete(Item item, Runnable afterDeleted);

    void downloadItem(Item item);

    void logout();

    void onSessionExpired();

    void showMessage(String message);
}
