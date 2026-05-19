package com.ephemeral.android;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
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
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemType;
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
import java.util.HashSet;
import java.util.List;
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
    private SwipePagerLayout authenticatedPager;
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
        mediaViewerController = new MediaViewerController(LayoutInflater.from(this), this, imageLoader,
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
        api.downloadFile(new FileDownloadRequest(item.getId(), item.getContentRef(), item.getFilename()),
                new DownloadProgressListener() {
                    @Override
                    public void onProgress(long downloadedBytes, long totalBytes) {
                    }
                },
                new ApiCallback<FileDownloadResult>() {
                    @Override
                    public void onSuccess(FileDownloadResult value) {
                        showMessage("Downloaded: " + value.getFilename());
                    }

                    @Override
                    public void onError(ApiError error) {
                        handleApiError(error);
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
        DownloadBatch batch = new DownloadBatch(downloadableItems.size());
        for (Item item : downloadableItems) {
            api.downloadFile(new FileDownloadRequest(item.getId(), item.getContentRef(), item.getFilename()),
                    new DownloadProgressListener() {
                        @Override
                        public void onProgress(long downloadedBytes, long totalBytes) {
                        }
                    },
                    new ApiCallback<FileDownloadResult>() {
                        @Override
                        public void onSuccess(FileDownloadResult value) {
                            batch.recordSuccess();
                        }

                        @Override
                        public void onError(ApiError error) {
                            batch.recordFailure(error);
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

    private List<Item> downloadableItems(List<Item> items) {
        List<Item> downloadableItems = new ArrayList<>();
        for (Item item : items) {
            if (item.getType() != ItemType.TEXT && !item.getContentRef().isEmpty()) {
                downloadableItems.add(item);
            }
        }
        return downloadableItems;
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
        if (authenticatedPager.getParent() != container) {
            container.removeAllViews();
            container.addView(authenticatedPager, new FrameLayout.LayoutParams(
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
        authenticatedPager = new SwipePagerLayout(this);
        chatController = new ChatController(inflater, api, runtimeConfig,
                this, fileResolver, imageLoader, () -> filePicker.launch(new String[]{"*/*"}));
        historyController = new HistoryController(inflater, api, this, imageLoader);
        authenticatedPager.addView(chatController.getView());
        authenticatedPager.addView(historyController.getView());
        authenticatedPager.setOnPageChangedListener(this::applyAuthenticatedPage);
    }

    private void applyAuthenticatedPage(int page) {
        if (page == PAGE_HISTORY) {
            screen = Screen.HISTORY;
            lastAuthenticatedScreen = Screen.HISTORY;
            activeEventConsumer = historyController;
            return;
        }
        screen = Screen.CHAT;
        lastAuthenticatedScreen = Screen.CHAT;
        activeEventConsumer = chatController;
    }

    private void releaseAuthenticatedPager() {
        authenticatedPager = null;
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

    private final class DownloadBatch {
        private final int total;
        private int completed;
        private int failed;
        private boolean authenticationErrorHandled;

        DownloadBatch(int total) {
            this.total = total;
        }

        void recordSuccess() {
            completed++;
            showResultIfComplete();
        }

        void recordFailure(ApiError error) {
            completed++;
            failed++;
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
            if (failed == 0) {
                showMessage(getString(R.string.downloaded_files, total));
            } else {
                showMessage(getString(R.string.download_files_failed, failed));
            }
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
}
