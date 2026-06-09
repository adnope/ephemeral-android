package com.ephemeral.android;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.IntentCompat;

import com.ephemeral.android.data.api.ApiCallback;
import com.ephemeral.android.data.api.ApiError;
import com.ephemeral.android.data.api.AuthResult;
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
import com.ephemeral.android.data.cache.CachedEphemeralApi;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemType;
import com.ephemeral.android.data.model.PublicLink;
import com.ephemeral.android.data.session.SessionRepository;
import com.ephemeral.android.ui.chat.ChatController;
import com.ephemeral.android.ui.common.BackHandler;
import com.ephemeral.android.ui.common.FileResolver;
import com.ephemeral.android.ui.common.ImageLoader;
import com.ephemeral.android.ui.common.ItemEventConsumer;
import com.ephemeral.android.ui.common.ScreenHost;
import com.ephemeral.android.ui.common.SwipePagerLayout;
import com.ephemeral.android.ui.history.HistoryController;
import com.ephemeral.android.ui.login.LoginController;
import com.ephemeral.android.ui.media.MediaViewerController;
import com.ephemeral.android.ui.preview.TextPreviewController;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.OkHttpClient;

public final class MainActivity extends ComponentActivity implements ScreenHost {
    private static final int PAGE_CHAT = 0;
    private static final int PAGE_HISTORY = 1;
    private static final long DELETE_EVENT_TIMEOUT_MS = 10_000L;

    private enum Screen {
        LOADING,
        LOGIN,
        CHAT,
        HISTORY,
        MEDIA,
        PREVIEW
    }

    private FrameLayout container;
    private EphemeralApi api;
    private AppExecutors executors;
    private SessionRepository sessionRepository;
    private FileResolver fileResolver;
    private ImageLoader imageLoader;
    private RuntimeConfig runtimeConfig;
    private Screen screen = Screen.LOADING;
    private Screen lastAuthenticatedScreen = Screen.CHAT;
    private View authenticatedShell;
    private SwipePagerLayout authenticatedPager;
    private TextView authenticatedChatTab;
    private TextView authenticatedHistoryTab;
    private View toolbarNormal;
    private View toolbarSelection;
    private TextView textSelectionCount;
    private TextView textSelectionSize;
    private View downloadProgressPanel;
    private TextView textDownloadProgress;
    private ProgressBar progressDownloadTotal;
    private DownloadBatch activeDownloadBatch;
    private SelectionClient currentSelectionClient;
    private ChatController chatController;
    private HistoryController historyController;
    private MediaViewerController mediaViewerController;
    private TextPreviewController textPreviewController;
    private ItemEventConsumer activeEventConsumer;
    private EventSubscription eventSubscription;
    private final List<PendingDeleteBatch> pendingDeleteBatches = new ArrayList<>();
    private PendingShare pendingShare = PendingShare.empty();
    private ActivityResultLauncher<String[]> filePicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        filePicker = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
            if (chatController != null) {
                chatController.enqueueUris(uris);
            } else if (!uris.isEmpty()) {
                pendingShare = pendingShare.merge(new PendingShare("", uris));
            }
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackPressed();
            }
        });
        setContentView(R.layout.activity_main);
        container = findViewById(R.id.screen_container);
        EphemeralApplication application = (EphemeralApplication) getApplication();
        api = application.getApi();
        executors = application.getExecutors();
        sessionRepository = application.getSessionRepository();
        fileResolver = new FileResolver(getContentResolver());
        imageLoader = new ImageLoader(getContentResolver(), executors, imageClient(),
                new File(getCacheDir(), "thumbnail-cache"));
        pendingShare = PendingShare.fromIntent(getIntent());
        boot();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingShare = pendingShare.merge(PendingShare.fromIntent(intent));
        if (screen == Screen.CHAT && chatController != null) {
            processPendingShare(chatController);
        }
    }

    @Override
    public void showChat() {
        if (runtimeConfig == null) {
            loadRuntimeConfig(this::showChat);
            return;
        }
        showAuthenticatedPage(PAGE_CHAT, true);
    }

    @Override
    public void showHistory() {
        if (runtimeConfig == null) {
            loadRuntimeConfig(this::showHistory);
            return;
        }
        showAuthenticatedPage(PAGE_HISTORY, true);
    }

    @Override
    public void openMediaViewer(List<Item> mediaItems, int startIndex) {
        if (mediaItems.isEmpty()) {
            return;
        }
        releaseOverlay();
        screen = Screen.MEDIA;
        mediaViewerController = new MediaViewerController(LayoutInflater.from(this), this, api, imageLoader,
                imageClient(), mediaItems, startIndex);
        activeEventConsumer = mediaViewerController;
        container.addView(mediaViewerController.getView(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void openTextPreview(Item item) {
        releaseOverlay();
        screen = Screen.PREVIEW;
        textPreviewController = new TextPreviewController(LayoutInflater.from(this), api, executors, this, item);
        activeEventConsumer = textPreviewController;
        container.addView(textPreviewController.getView(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void closeOverlay() {
        if (screen == Screen.MEDIA || screen == Screen.PREVIEW) {
            releaseOverlay();
            restoreAuthenticatedScreen();
            return;
        }
        if (lastAuthenticatedScreen == Screen.HISTORY) {
            showHistory();
        } else {
            showChat();
        }
    }

    @Override
    public void confirmDelete(Item item, Runnable afterDeleted) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(R.string.confirm_delete_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) ->
                        api.deleteItem(item.getId(), new ApiCallback<Void>() {
                            @Override
                            public void onSuccess(Void value) {
                                afterDeleted.run();
                            }

                            @Override
                            public void onError(ApiError error) {
                                handleApiError(error);
                            }
                        }))
                .show();
    }

    @Override
    public void deleteItemsOptimistically(List<Item> items) {
        Set<Long> itemIds = itemIds(items);
        if (itemIds.isEmpty()) {
            return;
        }
        PendingDeleteBatch batch = new PendingDeleteBatch(itemIds);
        pendingDeleteBatches.add(batch);
        removeItemsFromAuthenticatedControllers(itemIds);
        batch.timeoutRunnable = () -> failDeleteBatch(batch);
        container.postDelayed(batch.timeoutRunnable, DELETE_EVENT_TIMEOUT_MS);
        for (long itemId : itemIds) {
            api.deleteItem(itemId, new ApiCallback<Void>() {
                @Override
                public void onSuccess(Void value) {
                    // The item:deleted SSE is the confirmation source for optimistic UI state.
                }

                @Override
                public void onError(ApiError error) {
                    if (error.isAuthenticationFailure()) {
                        handleApiError(error);
                    } else {
                        failDeleteBatch(batch);
                    }
                }
            });
        }
    }

    @Override
    public void downloadItem(Item item) {
        List<Item> items = new ArrayList<>();
        items.add(item);
        DownloadBatch batch = startDownloadBatch(items, false);
        api.downloadFile(new FileDownloadRequest(item.getId(), item.getContentRef(), item.getFilename()),
                new DownloadProgressListener() {
                    @Override
                    public void onProgress(long downloadedBytes, long totalBytes) {
                        batch.recordProgress(item.getId(), downloadedBytes, totalBytes);
                    }
                },
                new ApiCallback<FileDownloadResult>() {
                    @Override
                    public void onSuccess(FileDownloadResult value) {
                        batch.recordSuccess(item.getId());
                        showMessage("Downloaded: " + value.getFilename());
                    }

                    @Override
                    public void onError(ApiError error) {
                        batch.recordFailure(item.getId(), error);
                        if (!error.isAuthenticationFailure()) {
                            handleApiError(error);
                        }
                    }
                });
    }

    @Override
    public void downloadItemsInBackground(List<Item> items) {
        List<Item> downloadableItems = downloadableItems(items);
        if (downloadableItems.isEmpty()) {
            showMessage(getString(R.string.no_downloadable_files_selected));
            return;
        }
        showMessage("Download started...");
        DownloadBatch batch = startDownloadBatch(downloadableItems, true);
        for (Item item : downloadableItems) {
            api.downloadFile(new FileDownloadRequest(item.getId(), item.getContentRef(), item.getFilename()),
                    new DownloadProgressListener() {
                        @Override
                        public void onProgress(long downloadedBytes, long totalBytes) {
                            batch.recordProgress(item.getId(), downloadedBytes, totalBytes);
                        }
                    },
                    new ApiCallback<FileDownloadResult>() {
                        @Override
                        public void onSuccess(FileDownloadResult value) {
                            batch.recordSuccess(item.getId());
                        }

                        @Override
                        public void onError(ApiError error) {
                            batch.recordFailure(item.getId(), error);
                        }
                    });
        }
    }

    @Override
    public void logout() {
        clearPendingDeleteBatches();
        stopEvents();
        runtimeConfig = null;
        sessionRepository.clearSession();
        showLogin(false, "");
        api.logout(new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void value) {
            }

            @Override
            public void onError(ApiError error) {
            }
        });
    }

    @Override
    public void onSessionExpired() {
        clearPendingDeleteBatches();
        stopEvents();
        runtimeConfig = null;
        sessionRepository.clearSession();
        askServerState("Session expired. Sign in again.");
    }

    @Override
    public void showMessage(String message) {
        Toast.makeText(this, message == null ? "Request failed." : message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onSelectionChanged(SelectionClient client) {
        currentSelectionClient = client;
        if (client == null || !client.isSelectionMode()) {
            if (toolbarSelection != null) {
                toolbarSelection.setVisibility(View.GONE);
            }
            if (toolbarNormal != null) {
                toolbarNormal.setVisibility(View.VISIBLE);
            }
        } else {
            if (toolbarNormal != null) {
                // Wait! Tweak 1 says: "The toolbar in multiselect mode should be at the bottom."
                // This means the normal toolbar at the top stays visible! We don't hide it anymore!
                // Ah!!! That is an extremely important point!
                // "1. The toolbar in multiselect mode should be at the bottom."
                // In a normal app, the top toolbar stays as the normal top bar, and the selection actions are shown at the bottom.
                // So toolbarNormal should remain visible (View.VISIBLE).
                toolbarNormal.setVisibility(View.VISIBLE);
            }
            if (toolbarSelection != null) {
                toolbarSelection.setVisibility(View.VISIBLE);
            }
            List<Item> selected = client.getSelectedItems();
            long totalSizeBytes = 0;
            for (Item item : selected) {
                long size = item.getFilesizeBytes();
                if (size > 0) {
                    totalSizeBytes += size;
                } else if (item.getType() == ItemType.TEXT && item.getContentRef() != null) {
                    totalSizeBytes += item.getContentRef().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                }
            }
            if (textSelectionCount != null) {
                textSelectionCount.setText(String.valueOf(selected.size()));
            }
            if (textSelectionSize != null) {
                textSelectionSize.setText(com.ephemeral.android.util.ByteFormatter.format(totalSizeBytes));
            }
        }
    }

    private void confirmDeleteSelected(List<Item> selectedItems) {
        if (selectedItems.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(getString(R.string.confirm_delete_selected_message, selectedItems.size()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    if (currentSelectionClient != null) {
                        currentSelectionClient.clearSelection();
                    }
                    deleteItemsOptimistically(selectedItems);
                })
                .show();
    }

    private void handleBackPressed() {
        if (currentBackHandler() != null && currentBackHandler().onBackPressed()) {
            return;
        }
        if (screen == Screen.MEDIA || screen == Screen.PREVIEW) {
            closeOverlay();
            return;
        }
        if (screen == Screen.HISTORY) {
            showChat();
            return;
        }
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (screen == Screen.MEDIA && mediaViewerController != null && mediaViewerController.handleKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void boot() {
        showLoading("Checking session...");
        if (sessionRepository.hasStoredSession()) {
            api.validateSession(new ApiCallback<AuthResult>() {
                @Override
                public void onSuccess(AuthResult value) {
                    loadRuntimeConfig(() -> {
                        startEvents();
                        showChat();
                    });
                }

                @Override
                public void onError(ApiError error) {
                    sessionRepository.clearSession();
                    askServerState("");
                }
            });
        } else {
            askServerState("");
        }
    }

    private void askServerState(String initialError) {
        showLoading("Connecting...");
        api.getServerState(new ApiCallback<ServerState>() {
            @Override
            public void onSuccess(ServerState value) {
                showLogin(value.isSetupRequired(), initialError);
            }

            @Override
            public void onError(ApiError error) {
                showLogin(false, initialError == null || initialError.isEmpty() ? error.getMessage() : initialError);
            }
        });
    }

    private void showLogin(boolean setupMode, String initialError) {
        releaseOverlay();
        releaseAuthenticatedPager();
        clearPendingDeleteBatches();
        stopEvents();
        screen = Screen.LOGIN;
        LoginController loginController = new LoginController(LayoutInflater.from(this), api, sessionRepository,
                setupMode, result -> loadRuntimeConfig(() -> {
                    startEvents();
                    showChat();
                }));
        if (initialError != null && !initialError.isEmpty()) {
            loginController.showInitialError(initialError);
        }
        activeEventConsumer = null;
        container.removeAllViews();
        container.addView(loginController.getView());
    }

    private void loadRuntimeConfig(Runnable afterLoaded) {
        api.getRuntimeConfig(new ApiCallback<RuntimeConfig>() {
            @Override
            public void onSuccess(RuntimeConfig value) {
                runtimeConfig = value;
                afterLoaded.run();
            }

            @Override
            public void onError(ApiError error) {
                sessionRepository.clearSession();
                showLogin(false, error.getMessage());
            }
        });
    }

    private void startEvents() {
        stopEvents();
        eventSubscription = api.observeItemEvents(new ItemEventListener() {
            @Override
            public void onEvent(ItemEvent event) {
                if (event.getType() == ItemEventType.DELETED) {
                    recordDeletedItemEvent(event.getItemId());
                }
                if (activeEventConsumer != null) {
                    activeEventConsumer.onItemEvent(event);
                }
            }

            @Override
            public void onError(ApiError error) {
                if (error.isAuthenticationFailure()) {
                    handleApiError(error);
                }
            }
        });
    }

    private void stopEvents() {
        if (eventSubscription != null) {
            eventSubscription.stop();
            eventSubscription = null;
        }
    }

    private Set<Long> itemIds(List<Item> items) {
        Set<Long> itemIds = new HashSet<>();
        for (Item item : items) {
            if (item.getId() > 0) {
                itemIds.add(item.getId());
            }
        }
        return itemIds;
    }

    private void removeItemsFromAuthenticatedControllers(Set<Long> itemIds) {
        if (chatController != null) {
            chatController.removeItems(itemIds);
        }
        if (historyController != null) {
            historyController.removeItems(itemIds);
        }
    }

    private void refreshAuthenticatedControllers() {
        if (chatController != null) {
            chatController.refreshFromBackend();
        }
        if (historyController != null) {
            historyController.refreshFromBackend();
        }
    }

    private void recordDeletedItemEvent(long itemId) {
        if (pendingDeleteBatches.isEmpty()) {
            return;
        }
        List<PendingDeleteBatch> completed = new ArrayList<>();
        for (PendingDeleteBatch batch : pendingDeleteBatches) {
            if (batch.recordDeleted(itemId) && batch.isComplete()) {
                completed.add(batch);
            }
        }
        for (PendingDeleteBatch batch : completed) {
            completeDeleteBatch(batch);
        }
    }

    private void completeDeleteBatch(PendingDeleteBatch batch) {
        if (!pendingDeleteBatches.remove(batch)) {
            return;
        }
        cancelDeleteTimeout(batch);
        showMessage(getString(R.string.deleted_items_successfully, batch.size()));
    }

    private void failDeleteBatch(PendingDeleteBatch batch) {
        if (!pendingDeleteBatches.remove(batch)) {
            return;
        }
        cancelDeleteTimeout(batch);
        showMessage(getString(R.string.delete_items_failed, batch.size()));
        refreshAuthenticatedControllers();
    }

    private void cancelDeleteTimeout(PendingDeleteBatch batch) {
        if (batch.timeoutRunnable != null && container != null) {
            container.removeCallbacks(batch.timeoutRunnable);
        }
        batch.timeoutRunnable = null;
    }

    private void clearPendingDeleteBatches() {
        List<PendingDeleteBatch> batches = new ArrayList<>(pendingDeleteBatches);
        pendingDeleteBatches.clear();
        for (PendingDeleteBatch batch : batches) {
            cancelDeleteTimeout(batch);
        }
    }

    private void handleApiError(ApiError error) {
        if (error.isAuthenticationFailure()) {
            onSessionExpired();
        } else {
            showMessage(error.getMessage());
        }
    }

    private BackHandler currentBackHandler() {
        if (screen == Screen.CHAT) {
            return chatController;
        }
        if (screen == Screen.HISTORY) {
            return historyController;
        }
        return null;
    }

    private void releaseOverlay() {
        if (mediaViewerController != null) {
            container.removeView(mediaViewerController.getView());
            mediaViewerController.release();
            mediaViewerController = null;
        }
        if (textPreviewController != null) {
            container.removeView(textPreviewController.getView());
            textPreviewController.release();
            textPreviewController = null;
        }
    }

    private void restoreAuthenticatedScreen() {
        if (authenticatedPager != null) {
            int page = lastAuthenticatedScreen == Screen.HISTORY ? PAGE_HISTORY : PAGE_CHAT;
            authenticatedPager.setCurrentPage(page, false);
            applyAuthenticatedPage(page);
            return;
        }
        if (lastAuthenticatedScreen == Screen.HISTORY) {
            showHistory();
        } else {
            showChat();
        }
    }

    private void showAuthenticatedPage(int page, boolean animate) {
        releaseOverlay();
        ensureAuthenticatedPager();
        if (authenticatedShell.getParent() != container) {
            container.removeAllViews();
            container.addView(authenticatedShell, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        applyAuthenticatedPage(page);
        authenticatedPager.setCurrentPage(page, animate);
        if (page == PAGE_CHAT && chatController != null) {
            processPendingShare(chatController);
        }
    }

    private void ensureAuthenticatedPager() {
        if (authenticatedPager != null) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        authenticatedShell = inflater.inflate(R.layout.screen_authenticated, container, false);
        authenticatedPager = authenticatedShell.findViewById(R.id.pager_authenticated);
        authenticatedChatTab = authenticatedShell.findViewById(R.id.button_nav_chat);
        authenticatedHistoryTab = authenticatedShell.findViewById(R.id.button_nav_history);
        TextView logoutButton = authenticatedShell.findViewById(R.id.button_logout);
        ImageButton refreshButton = authenticatedShell.findViewById(R.id.button_refresh);
        com.ephemeral.android.ui.common.ViewUi.prepareTextButton(logoutButton);
        com.ephemeral.android.ui.common.ViewUi.prepareTextButton(authenticatedChatTab);
        com.ephemeral.android.ui.common.ViewUi.prepareTextButton(authenticatedHistoryTab);
        com.ephemeral.android.ui.common.ViewUi.prepareImageButton(refreshButton);
        logoutButton.setOnClickListener(v -> logout());
        refreshButton.setOnClickListener(v -> refreshCurrentAuthenticatedPage());
        authenticatedChatTab.setOnClickListener(v -> showChat());
        authenticatedHistoryTab.setOnClickListener(v -> showHistory());

        toolbarNormal = authenticatedShell.findViewById(R.id.toolbar_normal);
        toolbarSelection = authenticatedShell.findViewById(R.id.toolbar_selection);
        textSelectionCount = authenticatedShell.findViewById(R.id.text_selection_count);
        textSelectionSize = authenticatedShell.findViewById(R.id.text_selection_size);
        downloadProgressPanel = authenticatedShell.findViewById(R.id.panel_download_progress);
        textDownloadProgress = authenticatedShell.findViewById(R.id.text_download_progress);
        progressDownloadTotal = authenticatedShell.findViewById(R.id.progress_download_total);

        authenticatedShell.findViewById(R.id.button_selection_cancel).setOnClickListener(v -> {
            if (currentSelectionClient != null) {
                currentSelectionClient.clearSelection();
            }
        });
        authenticatedShell.findViewById(R.id.button_selection_all).setOnClickListener(v -> {
            if (currentSelectionClient != null) {
                currentSelectionClient.toggleSelectAll();
            }
        });
        authenticatedShell.findViewById(R.id.button_selection_download).setOnClickListener(v -> {
            if (currentSelectionClient != null) {
                List<Item> selected = currentSelectionClient.getSelectedItems();
                currentSelectionClient.clearSelection();
                downloadItemsInBackground(selected);
            }
        });
        authenticatedShell.findViewById(R.id.button_selection_delete).setOnClickListener(v -> {
            if (currentSelectionClient != null) {
                List<Item> selected = currentSelectionClient.getSelectedItems();
                confirmDeleteSelected(selected);
            }
        });

        chatController = new ChatController(inflater, api, runtimeConfig,
                this, fileResolver, imageLoader, () -> filePicker.launch(new String[]{"*/*"}));
        historyController = new HistoryController(inflater, api, this, imageLoader);
        authenticatedPager.addView(chatController.getView());
        authenticatedPager.addView(historyController.getView());
        authenticatedPager.setOnPageChangedListener(this::applyAuthenticatedPage);
    }

    private void applyAuthenticatedPage(int page) {
        if (page == PAGE_HISTORY) {
            if (chatController != null) {
                chatController.clearSelection();
            }
            screen = Screen.HISTORY;
            lastAuthenticatedScreen = Screen.HISTORY;
            activeEventConsumer = historyController;
            updateAuthenticatedTabs(PAGE_HISTORY);
            return;
        }
        if (historyController != null) {
            historyController.clearSelection();
        }
        screen = Screen.CHAT;
        lastAuthenticatedScreen = Screen.CHAT;
        activeEventConsumer = chatController;
        updateAuthenticatedTabs(PAGE_CHAT);
    }

    private void updateAuthenticatedTabs(int page) {
        if (authenticatedChatTab == null || authenticatedHistoryTab == null) {
            return;
        }
        boolean history = page == PAGE_HISTORY;
        authenticatedChatTab.setBackgroundResource(history
                ? R.drawable.bg_filter_unselected_ripple : R.drawable.bg_filter_selected_ripple);
        authenticatedHistoryTab.setBackgroundResource(history
                ? R.drawable.bg_filter_selected_ripple : R.drawable.bg_filter_unselected_ripple);
    }

    private void refreshCurrentAuthenticatedPage() {
        if (screen == Screen.HISTORY && historyController != null) {
            historyController.refreshFromBackend();
            return;
        }
        if (chatController != null) {
            chatController.refreshFromBackend();
        }
    }

    private void releaseAuthenticatedPager() {
        authenticatedShell = null;
        authenticatedPager = null;
        authenticatedChatTab = null;
        authenticatedHistoryTab = null;
        downloadProgressPanel = null;
        textDownloadProgress = null;
        progressDownloadTotal = null;
        activeDownloadBatch = null;
        chatController = null;
        historyController = null;
        activeEventConsumer = null;
    }

    private void showLoading(String message) {
        releaseOverlay();
        releaseAuthenticatedPager();
        screen = Screen.LOADING;
        TextView text = new TextView(this);
        text.setText(message);
        text.setGravity(android.view.Gravity.CENTER);
        container.removeAllViews();
        container.addView(text, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void processPendingShare(ChatController controller) {
        if (pendingShare.text.length() > 0) {
            controller.setComposerDraft(pendingShare.text);
        }
        if (!pendingShare.uris.isEmpty()) {
            controller.enqueueUris(pendingShare.uris);
        }
        pendingShare = PendingShare.empty();
    }

    private OkHttpClient imageClient() {
        if (api instanceof CachedEphemeralApi) {
            return ((CachedEphemeralApi) api).getOkHttpClient();
        }
        if (api instanceof OkHttpEphemeralApi) {
            return ((OkHttpEphemeralApi) api).getClient();
        }
        return null;
    }

    private static final class PendingDeleteBatch {
        final Set<Long> expectedItemIds;
        final Set<Long> deletedEventItemIds = new HashSet<>();
        Runnable timeoutRunnable;

        PendingDeleteBatch(Set<Long> expectedItemIds) {
            this.expectedItemIds = new HashSet<>(expectedItemIds);
        }

        boolean recordDeleted(long itemId) {
            if (!expectedItemIds.contains(itemId)) {
                return false;
            }
            deletedEventItemIds.add(itemId);
            return true;
        }

        boolean isComplete() {
            return deletedEventItemIds.containsAll(expectedItemIds);
        }

        int size() {
            return expectedItemIds.size();
        }
    }

    private List<Item> downloadableItems(List<Item> items) {
        List<Item> downloadableItems = new ArrayList<>();
        for (Item item : items) {
            if (item.getType() != ItemType.TEXT && !item.getContentRef().isEmpty()) {
                downloadableItems.add(item);
            }
        }
        return downloadableItems;
    }

    private DownloadBatch startDownloadBatch(List<Item> items, boolean showBatchResult) {
        DownloadBatch batch = new DownloadBatch(items, showBatchResult);
        activeDownloadBatch = batch;
        batch.updateProgressUi();
        return batch;
    }

    private void hideDownloadProgressLater(DownloadBatch batch) {
        if (downloadProgressPanel == null) {
            return;
        }
        downloadProgressPanel.postDelayed(() -> {
            if (activeDownloadBatch == batch && downloadProgressPanel != null) {
                downloadProgressPanel.setVisibility(View.GONE);
                activeDownloadBatch = null;
            }
        }, 1200L);
    }

    private final class DownloadBatch {
        private final int total;
        private final boolean showBatchResult;
        private final Map<Long, DownloadItemProgress> itemProgress = new HashMap<>();
        private int completed;
        private int failed;
        private boolean authenticationErrorHandled;

        DownloadBatch(List<Item> items, boolean showBatchResult) {
            this.total = items.size();
            this.showBatchResult = showBatchResult;
            for (Item item : items) {
                long totalBytes = item.getFilesizeBytes() > 0 ? item.getFilesizeBytes() : -1;
                itemProgress.put(item.getId(), new DownloadItemProgress(totalBytes));
            }
        }

        void recordProgress(long itemId, long downloadedBytes, long totalBytes) {
            DownloadItemProgress progress = itemProgress.get(itemId);
            if (progress == null || progress.complete) {
                return;
            }
            progress.downloadedBytes = Math.max(progress.downloadedBytes, downloadedBytes);
            if (totalBytes > 0) {
                progress.totalBytes = totalBytes;
            }
            updateProgressUi();
        }

        void recordSuccess(long itemId) {
            DownloadItemProgress progress = itemProgress.get(itemId);
            if (progress != null && !progress.complete) {
                progress.complete = true;
                if (progress.totalBytes > 0) {
                    progress.downloadedBytes = progress.totalBytes;
                }
            }
            completed++;
            updateProgressUi();
            showResultIfComplete();
        }

        void recordFailure(long itemId, ApiError error) {
            DownloadItemProgress progress = itemProgress.get(itemId);
            if (progress != null && !progress.complete) {
                progress.complete = true;
            }
            completed++;
            failed++;
            updateProgressUi();
            if (error.isAuthenticationFailure() && !authenticationErrorHandled) {
                authenticationErrorHandled = true;
                handleApiError(error);
                return;
            }
            showResultIfComplete();
        }

        private void showResultIfComplete() {
            if (completed < total) {
                return;
            }
            updateProgressUi();
            hideDownloadProgressLater(this);
            if (!showBatchResult) {
                return;
            }
            if (failed == 0) {
                showMessage(getString(R.string.downloaded_files, total));
            } else {
                showMessage(getString(R.string.download_files_failed, failed));
            }
        }

        private void updateProgressUi() {
            if (activeDownloadBatch != this || downloadProgressPanel == null || textDownloadProgress == null
                    || progressDownloadTotal == null) {
                return;
            }
            int percent = progressPercent();
            downloadProgressPanel.setVisibility(View.VISIBLE);
            progressDownloadTotal.setProgress(percent);
            textDownloadProgress.setText(getString(R.string.download_progress, completed, total, percent));
        }

        private int progressPercent() {
            if (total == 0) {
                return 0;
            }
            long progressSum = 0;
            for (DownloadItemProgress progress : itemProgress.values()) {
                if (progress.complete) {
                    progressSum += 100;
                } else if (progress.totalBytes > 0) {
                    long safeDownloaded = Math.min(progress.downloadedBytes, progress.totalBytes);
                    progressSum += (safeDownloaded * 100) / progress.totalBytes;
                }
            }
            return (int) Math.min(100, progressSum / total);
        }
    }

    private static final class DownloadItemProgress {
        long downloadedBytes;
        long totalBytes;
        boolean complete;

        DownloadItemProgress(long totalBytes) {
            this.totalBytes = totalBytes;
        }
    }

    private static final class PendingShare {
        final String text;
        final List<Uri> uris;

        PendingShare(String text, List<Uri> uris) {
            this.text = text == null ? "" : text;
            this.uris = uris;
        }

        static PendingShare empty() {
            return new PendingShare("", new ArrayList<>());
        }

        PendingShare merge(PendingShare other) {
            List<Uri> merged = new ArrayList<>(uris);
            merged.addAll(other.uris);
            String mergedText = other.text.isEmpty() ? text : other.text;
            return new PendingShare(mergedText, merged);
        }

        static PendingShare fromIntent(Intent intent) {
            if (intent == null) {
                return empty();
            }
            String action = intent.getAction();
            if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                return empty();
            }
            List<Uri> uris = new ArrayList<>();
            if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                ArrayList<Uri> streams = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri.class);
                if (streams != null) {
                    uris.addAll(streams);
                }
            } else {
                Uri stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class);
                if (stream != null) {
                    uris.add(stream);
                }
            }
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            return new PendingShare(text == null ? "" : text.toString(), uris);
        }
    }

    @Override
    public void managePublicLink(Item item) {
        if (item == null || item.getType() == ItemType.TEXT) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_public_link, null);

        ProgressBar progressLoading = dialogView.findViewById(R.id.progress_loading);
        View layoutContent = dialogView.findViewById(R.id.layout_content);
        TextView textStatus = dialogView.findViewById(R.id.text_status);
        View layoutLinkDetails = dialogView.findViewById(R.id.layout_link_details);
        TextView textUrl = dialogView.findViewById(R.id.text_url);
        ImageButton buttonCopy = dialogView.findViewById(R.id.button_copy);
        TextView textExpiryTime = dialogView.findViewById(R.id.text_expiry_time);
        Spinner spinnerExpiry = dialogView.findViewById(R.id.spinner_expiry);
        Button buttonAction = dialogView.findViewById(R.id.button_action);
        Button buttonRevoke = dialogView.findViewById(R.id.button_revoke);
        Button buttonClose = dialogView.findViewById(R.id.button_close);

        com.ephemeral.android.ui.common.ViewUi.stripButtonShadow(buttonAction);
        com.ephemeral.android.ui.common.ViewUi.stripButtonShadow(buttonRevoke);
        com.ephemeral.android.ui.common.ViewUi.stripButtonShadow(buttonClose);

        configureDialogExpirySpinner(spinnerExpiry);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        buttonCopy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Public Link", textUrl.getText().toString()));
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
            }
        });

        buttonClose.setOnClickListener(v -> dialog.dismiss());

        progressLoading.setVisibility(View.VISIBLE);
        layoutContent.setVisibility(View.GONE);

        api.getPublicLink(item.getId(), new ApiCallback<PublicLink>() {
            @Override
            public void onSuccess(PublicLink link) {
                progressLoading.setVisibility(View.GONE);
                layoutContent.setVisibility(View.VISIBLE);
                bindDialogState(link, item.getId(), dialog, dialogView);
            }

            @Override
            public void onError(ApiError error) {
                showMessage(getString(R.string.failed_to_get_public_link) + " " + error.getMessage());
                dialog.dismiss();
            }
        });

        dialog.show();
    }

    private void bindDialogState(PublicLink link, long itemId, AlertDialog dialog, View dialogView) {
        TextView textStatus = dialogView.findViewById(R.id.text_status);
        View layoutLinkDetails = dialogView.findViewById(R.id.layout_link_details);
        TextView textUrl = dialogView.findViewById(R.id.text_url);
        TextView textExpiryTime = dialogView.findViewById(R.id.text_expiry_time);
        Spinner spinnerExpiry = dialogView.findViewById(R.id.spinner_expiry);
        Button buttonAction = dialogView.findViewById(R.id.button_action);
        Button buttonRevoke = dialogView.findViewById(R.id.button_revoke);

        boolean hasLink = link.hasLink();
        if (hasLink) {
            textStatus.setText(link.isExpired() ? "Link expired" : "Link active");
            layoutLinkDetails.setVisibility(View.VISIBLE);
            textUrl.setText(link.getUrl());

            String expiresAt = link.getExpiresAt();
            if (expiresAt == null || expiresAt.isEmpty()) {
                textExpiryTime.setText("Never expires");
            } else {
                textExpiryTime.setText("Expires: " + formatIsoDate(expiresAt));
            }

            buttonAction.setText("Update Expiry");
            buttonAction.setEnabled(true);
            buttonAction.setOnClickListener(v -> {
                long duration = getExpiryDurationSeconds(spinnerExpiry.getSelectedItemPosition());
                updateLinkInPlace(itemId, duration == -1 ? null : duration, dialog, dialogView);
            });

            buttonRevoke.setVisibility(View.VISIBLE);
            buttonRevoke.setEnabled(true);
            buttonRevoke.setOnClickListener(v -> {
                revokeLinkInPlace(itemId, dialog, dialogView);
            });
        } else {
            textStatus.setText("No active link");
            layoutLinkDetails.setVisibility(View.GONE);

            buttonAction.setText("Create Link");
            buttonAction.setEnabled(true);
            buttonAction.setOnClickListener(v -> {
                long duration = getExpiryDurationSeconds(spinnerExpiry.getSelectedItemPosition());
                createLinkInPlace(itemId, duration == -1 ? null : duration, dialog, dialogView);
            });

            buttonRevoke.setVisibility(View.GONE);
        }
    }

    private void configureDialogExpirySpinner(Spinner spinner) {
        com.ephemeral.android.ui.common.ViewUi.configureFilterSpinner(
                this, spinner, R.array.public_link_expiry_labels);
        com.ephemeral.android.ui.common.ViewUi.syncSpinnerDropDownWidth(spinner);
    }

    private String formatIsoDate(String isoString) {
        if (isoString == null || isoString.isEmpty()) {
            return "";
        }
        try {
            java.time.Instant instant = java.time.Instant.parse(isoString);
            java.util.Date date = java.util.Date.from(instant);
            return com.ephemeral.android.util.DateFormatter.detail(date.getTime());
        } catch (Exception e) {
            return isoString;
        }
    }

    private long getExpiryDurationSeconds(int spinnerPosition) {
        switch (spinnerPosition) {
            case 1: return 3600L; // 1 hour
            case 2: return 86400L; // 24 hours
            case 3: return 604800L; // 7 days
            case 4: return 2592000L; // 30 days
            default: return -1L; // Never
        }
    }

    private void syncPublicLinkState(long itemId, boolean active) {
        if (chatController != null) {
            chatController.updateItemPublicLink(itemId, active);
        }
        if (historyController != null) {
            historyController.updateItemPublicLink(itemId, active);
        }
    }

    private static boolean isPublicLinkActive(PublicLink link) {
        return link != null && link.isActive();
    }

    private void createLinkInPlace(long itemId, Long durationSeconds, AlertDialog dialog, View dialogView) {
        Button buttonAction = dialogView.findViewById(R.id.button_action);
        buttonAction.setEnabled(false);
        buttonAction.setText("Creating...");
        api.createPublicLink(itemId, durationSeconds, new ApiCallback<PublicLink>() {
            @Override
            public void onSuccess(PublicLink link) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Public Link", link.getUrl()));
                }
                showMessage(getString(R.string.public_link_created));
                bindDialogState(link, itemId, dialog, dialogView);
                syncPublicLinkState(itemId, isPublicLinkActive(link));
            }

            @Override
            public void onError(ApiError error) {
                buttonAction.setEnabled(true);
                buttonAction.setText("Create Link");
                showMessage(getString(R.string.failed_to_create_public_link) + " " + error.getMessage());
            }
        });
    }

    private void updateLinkInPlace(long itemId, Long durationSeconds, AlertDialog dialog, View dialogView) {
        Button buttonAction = dialogView.findViewById(R.id.button_action);
        buttonAction.setEnabled(false);
        buttonAction.setText("Updating...");

        // Optimistic: show success toast immediately
        showMessage(getString(R.string.public_link_updated));
        api.createPublicLink(itemId, durationSeconds, new ApiCallback<PublicLink>() {
            @Override
            public void onSuccess(PublicLink link) {
                bindDialogState(link, itemId, dialog, dialogView);
                syncPublicLinkState(itemId, isPublicLinkActive(link));
            }

            @Override
            public void onError(ApiError error) {
                buttonAction.setEnabled(true);
                buttonAction.setText("Update Expiry");
                showMessage(getString(R.string.failed_to_create_public_link) + " " + error.getMessage());
            }
        });
    }

    private void revokeLinkInPlace(long itemId, AlertDialog dialog, View dialogView) {
        Button buttonRevoke = dialogView.findViewById(R.id.button_revoke);
        buttonRevoke.setEnabled(false);
        buttonRevoke.setText("Revoking...");

        // Optimistic: update UI immediately
        TextView textStatus = dialogView.findViewById(R.id.text_status);
        textStatus.setText("No active link");
        View layoutLinkDetails = dialogView.findViewById(R.id.layout_link_details);
        layoutLinkDetails.setVisibility(View.GONE);
        Button buttonAction = dialogView.findViewById(R.id.button_action);
        buttonAction.setText("Create Link");
        buttonRevoke.setVisibility(View.GONE);
        syncPublicLinkState(itemId, false);

        showMessage(getString(R.string.public_link_revoked));
        api.revokePublicLink(itemId, new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void value) {
                buttonAction.setEnabled(true);
                buttonAction.setOnClickListener(v -> {
                    Spinner spinnerExpiry = dialogView.findViewById(R.id.spinner_expiry);
                    long duration = getExpiryDurationSeconds(spinnerExpiry.getSelectedItemPosition());
                    createLinkInPlace(itemId, duration == -1 ? null : duration, dialog, dialogView);
                });
            }

            @Override
            public void onError(ApiError error) {
                showMessage(getString(R.string.failed_to_revoke_public_link) + " " + error.getMessage());
                // Re-fetch state to restore accurate UI
                api.getPublicLink(itemId, new ApiCallback<PublicLink>() {
                    @Override
                    public void onSuccess(PublicLink link) {
                        bindDialogState(link, itemId, dialog, dialogView);
                        syncPublicLinkState(itemId, isPublicLinkActive(link));
                    }

                    @Override
                    public void onError(ApiError error2) {
                        dialog.dismiss();
                    }
                });
            }
        });
    }
}
