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

    void managePublicLink(Item item);

    void deleteItemsOptimistically(List<Item> items);

    void downloadItemsInBackground(List<Item> items);

    void downloadItem(Item item);

    void logout();

    void onSessionExpired();

    void showMessage(String message);

    interface SelectionClient {
        void toggleSelectAll();
        void clearSelection();
        List<Item> getSelectedItems();
        boolean isSelectionMode();
    }

    void onSelectionChanged(SelectionClient client);
}
