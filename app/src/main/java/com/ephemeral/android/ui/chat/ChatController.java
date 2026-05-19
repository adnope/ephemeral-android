package com.ephemeral.android.ui.chat;

import android.app.AlertDialog;
import android.net.Uri;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ephemeral.android.R;
import com.ephemeral.android.data.api.ApiCallback;
import com.ephemeral.android.data.api.ApiError;
import com.ephemeral.android.data.api.EphemeralApi;
import com.ephemeral.android.data.api.ItemEvent;
import com.ephemeral.android.data.api.ItemEventType;
import com.ephemeral.android.data.api.RuntimeConfig;
import com.ephemeral.android.data.api.UploadRequest;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.Page;
import com.ephemeral.android.data.model.SendStatus;
import com.ephemeral.android.ui.common.BackHandler;
import com.ephemeral.android.ui.common.FileResolver;
import com.ephemeral.android.ui.common.ImageLoader;
import com.ephemeral.android.ui.common.ItemEventConsumer;
import com.ephemeral.android.ui.common.ScreenHost;
import com.ephemeral.android.ui.upload.UploadController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class ChatController implements BackHandler, ItemEventConsumer {
    public interface FilePicker {
        void openFilePicker();
    }

    private final View view;
    private final EphemeralApi api;
    private final RuntimeConfig config;
    private final ScreenHost host;
    private final FileResolver fileResolver;
    private final FilePicker filePicker;
    private final RecyclerView list;
    private final ProgressBar loading;
    private final TextView empty;
    private final EditText composer;
    private final View composerPanel;
    private final View selectionActions;
    private final ChatAdapter adapter;
    private final UploadController uploadController;
    private final LinearLayoutManager layoutManager;
    private final AtomicLong nextLocalId = new AtomicLong(-1);
    private final List<ChatEntry> entries = new ArrayList<>();
    private final Set<Long> selectedItemIds = new HashSet<>();
    private long nextCursor;
    private boolean hasMore;
    private boolean requestInFlight;
    private boolean refreshPending;

    public ChatController(LayoutInflater inflater, EphemeralApi api, RuntimeConfig config,
            ScreenHost host, FileResolver fileResolver, ImageLoader imageLoader, FilePicker filePicker) {
        this.api = api;
        this.config = config;
        this.host = host;
        this.fileResolver = fileResolver;
        this.filePicker = filePicker;
        view = inflater.inflate(R.layout.screen_chat, null, false);
        list = view.findViewById(R.id.list_chat);
        loading = view.findViewById(R.id.progress_chat);
        empty = view.findViewById(R.id.text_chat_empty);
        composer = view.findViewById(R.id.input_composer);
        composerPanel = view.findViewById(R.id.panel_composer);
        selectionActions = view.findViewById(R.id.panel_selection_actions);
        adapter = new ChatAdapter(imageLoader, new ChatAdapter.Callback() {
            @Override
            public void retry(ChatEntry entry) {
                retryOptimistic(entry);
            }

            @Override
            public void delete(Item item) {
                host.confirmDelete(item, () -> removeItem(item.getId()));
            }

            @Override
            public void select(Item item) {
                toggleSelection(item);
            }

            @Override
            public void openMedia(Item item) {
                ChatController.this.openMedia(item);
            }

            @Override
            public void openPreview(Item item) {
                host.openTextPreview(item);
            }

            @Override
            public void download(Item item) {
                host.downloadItem(item);
            }
        });
        layoutManager = new LinearLayoutManager(view.getContext());
        list.setLayoutManager(layoutManager);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (!requestInFlight && hasMore && layoutManager.findFirstVisibleItemPosition() <= 2) {
                    loadOlder();
                }
            }
        });
        uploadController = new UploadController(view, api, config, host);
        view.findViewById(R.id.button_send).setOnClickListener(v -> sendComposer());
        view.findViewById(R.id.button_attach).setOnClickListener(v -> openFilePicker());
        view.findViewById(R.id.button_download_selected).setOnClickListener(v -> downloadSelected());
        view.findViewById(R.id.button_delete_selected).setOnClickListener(v -> confirmDeleteSelected());
        composer.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN
                    && !event.isShiftPressed()) {
                sendComposer();
                return true;
            }
            return false;
        });
        loadFirstPage();
    }

    public View getView() {
        return view;
    }

    public void setComposerDraft(String text) {
        if (text != null && !text.isEmpty()) {
            composer.setText(text);
            composer.setSelection(composer.getText().length());
        }
    }

    public void enqueueUris(List<Uri> uris) {
        if (uris.isEmpty()) {
            return;
        }
        List<UploadRequest> requests = fileResolver.toUploadRequests(uris);
        uploadController.enqueue(requests);
    }

    public void refreshFromBackend() {
        if (requestInFlight) {
            refreshPending = true;
            return;
        }
        refreshPending = false;
        loadFirstPage();
    }

    public void removeItems(Set<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return;
        }
        selectedItemIds.removeAll(itemIds);
        for (int i = entries.size() - 1; i >= 0; i--) {
            ChatEntry entry = entries.get(i);
            if (!entry.isOptimistic() && itemIds.contains(entry.getItem().getId())) {
                entries.remove(i);
            }
        }
        render();
    }

    @Override
    public boolean onBackPressed() {
        if (isSelectionMode()) {
            clearSelection();
            return true;
        }
        return uploadController.onBackPressed();
    }

    @Override
    public void onItemEvent(ItemEvent event) {
        if (event.getType() == ItemEventType.DELETED) {
            removeItem(event.getItemId());
        } else {
            refreshVisible();
        }
    }

    private void loadFirstPage() {
        requestInFlight = true;
        loading.setVisibility(View.VISIBLE);
        api.loadChatPage(0, new ApiCallback<Page<Item>>() {
            @Override
            public void onSuccess(Page<Item> page) {
                requestInFlight = false;
                loading.setVisibility(View.GONE);
                if (startPendingRefresh()) {
                    return;
                }
                List<ChatEntry> optimistic = optimisticEntries();
                entries.clear();
                appendNewestFirstItems(page.getItems());
                entries.addAll(optimistic);
                nextCursor = page.getNextCursor();
                hasMore = page.hasMore();
                render();
                scrollToNewest();
            }

            @Override
            public void onError(ApiError error) {
                requestInFlight = false;
                loading.setVisibility(View.GONE);
                if (startPendingRefresh()) {
                    return;
                }
                handleApiError(error);
            }
        });
    }

    private void loadOlder() {
        if (nextCursor == 0) {
            return;
        }
        int anchorPosition = Math.max(0, layoutManager.findFirstVisibleItemPosition());
        View anchorView = layoutManager.findViewByPosition(anchorPosition);
        int anchorTop = anchorView == null ? 0 : anchorView.getTop();
        requestInFlight = true;
        api.loadChatPage(nextCursor, new ApiCallback<Page<Item>>() {
            @Override
            public void onSuccess(Page<Item> page) {
                requestInFlight = false;
                if (startPendingRefresh()) {
                    return;
                }
                List<Item> currentItems = itemsOnly();
                List<Item> merged = prependNewestFirstItems(currentItems, page.getItems());
                List<ChatEntry> optimistic = optimisticEntries();
                entries.clear();
                appendDisplayOrderItems(merged);
                entries.addAll(optimistic);
                nextCursor = page.getNextCursor();
                hasMore = page.hasMore();
                render();
                int addedCount = merged.size() - currentItems.size();
                if (addedCount > 0) {
                    layoutManager.scrollToPositionWithOffset(anchorPosition + addedCount, anchorTop);
                }
            }

            @Override
            public void onError(ApiError error) {
                requestInFlight = false;
                if (startPendingRefresh()) {
                    return;
                }
                handleApiError(error);
            }
        });
    }

    private boolean startPendingRefresh() {
        if (!refreshPending) {
            return false;
        }
        refreshPending = false;
        loadFirstPage();
        return true;
    }

    private void refreshVisible() {
        if (!requestInFlight) {
            loadFirstPage();
        }
    }

    private void sendComposer() {
        String text = composer.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        composer.setText("");
        ChatEntry entry = ChatEntry.optimistic(nextLocalId.getAndDecrement(), text);
        entries.add(entry);
        render();
        scrollToNewest();
        sendEntry(entry);
    }

    private void retryOptimistic(ChatEntry entry) {
        int index = indexOfStableId(entry.getStableId());
        if (index < 0) {
            return;
        }
        ChatEntry sending = entry.withStatus(SendStatus.SENDING);
        entries.set(index, sending);
        render();
        sendEntry(sending);
    }

    private void sendEntry(ChatEntry entry) {
        api.sendTextMessage(entry.getText(), new ApiCallback<Item>() {
            @Override
            public void onSuccess(Item item) {
                int index = indexOfStableId(entry.getStableId());
                if (index >= 0) {
                    entries.set(index, ChatEntry.fromItem(item));
                } else {
                    entries.add(ChatEntry.fromItem(item));
                }
                removeDuplicateItems(item.getId());
                render();
                scrollToNewest();
            }

            @Override
            public void onError(ApiError error) {
                int index = indexOfStableId(entry.getStableId());
                if (index >= 0) {
                    entries.set(index, entry.withStatus(SendStatus.FAILED));
                    render();
                }
                handleApiError(error);
            }
        });
    }

    private void openFilePicker() {
        filePicker.openFilePicker();
    }

    private void openMedia(Item selected) {
        List<Item> media = new ArrayList<>();
        int start = 0;
        for (ChatEntry entry : entries) {
            if (!entry.isOptimistic() && entry.getItem().isMedia()) {
                if (entry.getItem().getId() == selected.getId()) {
                    start = media.size();
                }
                media.add(entry.getItem());
            }
        }
        host.openMediaViewer(media, start);
    }

    private void appendNewestFirstItems(List<Item> items) {
        for (int i = items.size() - 1; i >= 0; i--) {
            entries.add(ChatEntry.fromItem(items.get(i)));
        }
    }

    private void appendDisplayOrderItems(List<Item> items) {
        for (Item item : items) {
            entries.add(ChatEntry.fromItem(item));
        }
    }

    private List<Item> prependNewestFirstItems(List<Item> currentItems, List<Item> incomingNewestFirst) {
        Set<Long> seen = new HashSet<>();
        for (Item item : currentItems) {
            seen.add(item.getId());
        }
        List<Item> merged = new ArrayList<>(currentItems.size() + incomingNewestFirst.size());
        for (int i = incomingNewestFirst.size() - 1; i >= 0; i--) {
            Item item = incomingNewestFirst.get(i);
            if (seen.add(item.getId())) {
                merged.add(item);
            }
        }
        merged.addAll(currentItems);
        return merged;
    }

    private List<Item> itemsOnly() {
        List<Item> items = new ArrayList<>();
        for (ChatEntry entry : entries) {
            if (!entry.isOptimistic()) {
                items.add(entry.getItem());
            }
        }
        return items;
    }

    private List<ChatEntry> optimisticEntries() {
        List<ChatEntry> optimistic = new ArrayList<>();
        for (ChatEntry entry : entries) {
            if (entry.isOptimistic()) {
                optimistic.add(entry);
            }
        }
        return optimistic;
    }

    private void removeItem(long itemId) {
        selectedItemIds.remove(itemId);
        for (int i = entries.size() - 1; i >= 0; i--) {
            ChatEntry entry = entries.get(i);
            if (!entry.isOptimistic() && entry.getItem().getId() == itemId) {
                entries.remove(i);
            }
        }
        render();
    }

    private void toggleSelection(Item item) {
        if (selectedItemIds.contains(item.getId())) {
            selectedItemIds.remove(item.getId());
        } else {
            selectedItemIds.add(item.getId());
        }
        adapter.setSelectedItemIds(selectedItemIds);
        updateSelectionUi();
    }

    private boolean isSelectionMode() {
        return !selectedItemIds.isEmpty();
    }

    private void clearSelection() {
        selectedItemIds.clear();
        adapter.setSelectedItemIds(selectedItemIds);
        updateSelectionUi();
    }

    private void downloadSelected() {
        List<Item> selectedItems = selectedItems();
        clearSelection();
        host.downloadItemsInBackground(selectedItems);
    }

    private void confirmDeleteSelected() {
        List<Item> selectedItems = selectedItems();
        if (selectedItems.isEmpty()) {
            clearSelection();
            return;
        }
        new AlertDialog.Builder(view.getContext())
                .setTitle(R.string.confirm_delete_title)
                .setMessage(view.getResources().getString(
                        R.string.confirm_delete_selected_message, selectedItems.size()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    clearSelection();
                    host.deleteItemsOptimistically(selectedItems);
                })
                .show();
    }

    private List<Item> selectedItems() {
        List<Item> selected = new ArrayList<>();
        for (ChatEntry entry : entries) {
            if (!entry.isOptimistic() && selectedItemIds.contains(entry.getItem().getId())) {
                selected.add(entry.getItem());
            }
        }
        return selected;
    }

    private void pruneSelectionToCurrentEntries() {
        Set<Long> availableIds = new HashSet<>();
        for (ChatEntry entry : entries) {
            if (!entry.isOptimistic()) {
                availableIds.add(entry.getItem().getId());
            }
        }
        selectedItemIds.retainAll(availableIds);
    }

    private void updateSelectionUi() {
        boolean selectionMode = isSelectionMode();
        selectionActions.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        composerPanel.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
    }

    private void removeDuplicateItems(long itemId) {
        boolean firstSeen = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            ChatEntry entry = entries.get(i);
            if (!entry.isOptimistic() && entry.getItem().getId() == itemId) {
                if (firstSeen) {
                    entries.remove(i);
                } else {
                    firstSeen = true;
                }
            }
        }
    }

    private int indexOfStableId(long stableId) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getStableId() == stableId) {
                return i;
            }
        }
        return -1;
    }

    private void render() {
        pruneSelectionToCurrentEntries();
        adapter.submit(entries);
        adapter.setSelectedItemIds(selectedItemIds);
        updateSelectionUi();
        empty.setVisibility(entries.isEmpty() && !requestInFlight ? View.VISIBLE : View.GONE);
    }

    private void scrollToNewest() {
        int count = adapter.getItemCount();
        if (count > 0) {
            list.scrollToPosition(count - 1);
        }
    }

    private void handleApiError(ApiError error) {
        if (error.isAuthenticationFailure()) {
            host.onSessionExpired();
        } else {
            host.showMessage(error.getMessage());
        }
    }
}
