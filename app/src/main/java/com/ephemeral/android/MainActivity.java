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
import com.ephemeral.android.data.api.OkHttpEphemeralApi;
import com.ephemeral.android.data.api.RuntimeConfig;
import com.ephemeral.android.data.api.ServerState;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.session.SessionRepository;
import com.ephemeral.android.ui.chat.ChatController;
import com.ephemeral.android.ui.common.BackHandler;
import com.ephemeral.android.ui.common.FileResolver;
import com.ephemeral.android.ui.common.ImageLoader;
import com.ephemeral.android.ui.common.ItemEventConsumer;
import com.ephemeral.android.ui.common.ScreenHost;
import com.ephemeral.android.ui.history.HistoryController;
import com.ephemeral.android.ui.login.LoginController;
import com.ephemeral.android.ui.media.MediaViewerController;
import com.ephemeral.android.ui.preview.TextPreviewController;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;

public final class MainActivity extends ComponentActivity implements ScreenHost {
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
    private ChatController chatController;
    private HistoryController historyController;
    private MediaViewerController mediaViewerController;
    private TextPreviewController textPreviewController;
    private ItemEventConsumer activeEventConsumer;
    private EventSubscription eventSubscription;
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
        releaseOverlay();
        screen = Screen.CHAT;
        lastAuthenticatedScreen = Screen.CHAT;
        chatController = new ChatController(LayoutInflater.from(this), api, runtimeConfig,
                this, fileResolver, imageLoader, () -> filePicker.launch(new String[]{"*/*"}));
        activeEventConsumer = chatController;
        container.removeAllViews();
        container.addView(chatController.getView());
        processPendingShare(chatController);
    }

    @Override
    public void showHistory() {
        if (runtimeConfig == null) {
            loadRuntimeConfig(this::showHistory);
            return;
        }
        releaseOverlay();
        screen = Screen.HISTORY;
        lastAuthenticatedScreen = Screen.HISTORY;
        historyController = new HistoryController(LayoutInflater.from(this), api, this, imageLoader);
        activeEventConsumer = historyController;
        container.removeAllViews();
        container.addView(historyController.getView());
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
    public void logout() {
        stopEvents();
        api.logout(new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void value) {
                runtimeConfig = null;
                sessionRepository.clearSession();
                askServerState("");
            }

            @Override
            public void onError(ApiError error) {
                runtimeConfig = null;
                sessionRepository.clearSession();
                askServerState(error.getMessage());
            }
        });
    }

    @Override
    public void onSessionExpired() {
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
        if (lastAuthenticatedScreen == Screen.HISTORY && historyController != null) {
            screen = Screen.HISTORY;
            activeEventConsumer = historyController;
            return;
        }
        if (chatController != null) {
            screen = Screen.CHAT;
            activeEventConsumer = chatController;
            return;
        }
        if (lastAuthenticatedScreen == Screen.HISTORY) {
            showHistory();
        } else {
            showChat();
        }
    }

    private void showLoading(String message) {
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
