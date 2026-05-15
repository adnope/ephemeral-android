package com.ephemeral.android.ui.upload;

import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ephemeral.android.R;
import com.ephemeral.android.data.api.ApiCallback;
import com.ephemeral.android.data.api.ApiError;
import com.ephemeral.android.data.api.ApiErrorCategory;
import com.ephemeral.android.data.api.Cancellable;
import com.ephemeral.android.data.api.EphemeralApi;
import com.ephemeral.android.data.api.RuntimeConfig;
import com.ephemeral.android.data.api.UploadRequest;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.UploadStatus;
import com.ephemeral.android.data.model.UploadTask;
import com.ephemeral.android.ui.common.ScreenHost;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class UploadController {
    private final View panel;
    private final RecyclerView list;
    private final TextView summary;
    private final ProgressBar totalProgress;
    private final EphemeralApi api;
    private final RuntimeConfig config;
    private final ScreenHost host;
    private final UploadAdapter adapter;
    private final List<UploadTask> tasks = new ArrayList<>();
    private final Map<Long, Cancellable> activeCalls = new HashMap<>();
    private final AtomicLong nextLocalId = new AtomicLong(1);
    private boolean expanded = true;

    public UploadController(View root, EphemeralApi api, RuntimeConfig config, ScreenHost host) {
        panel = root.findViewById(R.id.panel_upload_queue);
        list = root.findViewById(R.id.list_uploads);
        summary = root.findViewById(R.id.text_upload_summary);
        totalProgress = root.findViewById(R.id.progress_upload_total);
        this.api = api;
        this.config = config;
        this.host = host;
        adapter = new UploadAdapter(new UploadAdapter.Callback() {
            @Override
            public void retry(long localId) {
                retryTask(localId);
            }

            @Override
            public void cancel(long localId) {
                cancelTask(localId);
            }
        });
        list.setLayoutManager(new LinearLayoutManager(root.getContext()));
        list.setAdapter(adapter);
        root.findViewById(R.id.button_close_uploads).setOnClickListener(v -> closeOrCollapse());
        summary.setOnClickListener(v -> {
            expanded = !expanded;
            render();
        });
        render();
    }

    public void enqueue(List<UploadRequest> requests) {
        for (UploadRequest request : requests) {
            UploadStatus status = UploadStatus.QUEUED;
            String error = "";
            if (request.getSizeBytes() > config.getMaxUploadSizeBytes() && request.getSizeBytes() >= 0) {
                status = UploadStatus.FAILED;
                error = "File exceeds server limit";
            }
            tasks.add(new UploadTask(nextLocalId.getAndIncrement(), request.getSourceUri(),
                    request.getDisplayName(), request.getSizeBytes(), status, 0,
                    request.getSizeBytes(), error));
        }
        expanded = true;
        render();
        pumpQueue();
    }

    public boolean onBackPressed() {
        if (panel.getVisibility() == View.VISIBLE && expanded && hasActiveTasks()) {
            expanded = false;
            render();
            return true;
        }
        return false;
    }

    private void pumpQueue() {
        int active = UploadQueueModel.activeCount(tasks);
        for (UploadTask task : new ArrayList<>(tasks)) {
            if (active >= config.getUploadConcurrency()) {
                return;
            }
            if (task.getStatus() == UploadStatus.QUEUED) {
                start(task);
                active++;
            }
        }
    }

    private void start(UploadTask task) {
        replace(task.withStatus(UploadStatus.UPLOADING, ""));
        UploadRequest request = new UploadRequest(task.getSourceUri(), task.getDisplayName(),
                task.getSizeBytes(), "");
        Cancellable call = api.uploadFile(request,
                (uploadedBytes, totalBytes) -> {
                    UploadTask current = find(task.getLocalId());
                    if (current != null && current.getStatus() == UploadStatus.UPLOADING) {
                        replace(current.withProgress(uploadedBytes, totalBytes));
                    }
                },
                new ApiCallback<Item>() {
                    @Override
                    public void onSuccess(Item value) {
                        activeCalls.remove(task.getLocalId());
                        UploadTask current = find(task.getLocalId());
                        if (current != null) {
                            replace(current.withStatus(UploadStatus.DONE, ""));
                        }
                        pumpQueue();
                    }

                    @Override
                    public void onError(ApiError error) {
                        activeCalls.remove(task.getLocalId());
                        UploadTask current = find(task.getLocalId());
                        if (current != null) {
                            UploadStatus status = error.getCategory() == ApiErrorCategory.CANCELED
                                    ? UploadStatus.CANCELED : UploadStatus.FAILED;
                            replace(current.withStatus(status, error.getMessage()));
                        }
                        if (error.isAuthenticationFailure()) {
                            host.onSessionExpired();
                        }
                        pumpQueue();
                    }
                });
        activeCalls.put(task.getLocalId(), call);
    }

    private void retryTask(long localId) {
        UploadTask task = find(localId);
        if (task == null) {
            return;
        }
        replace(task.withStatus(UploadStatus.QUEUED, ""));
        pumpQueue();
    }

    private void cancelTask(long localId) {
        Cancellable call = activeCalls.remove(localId);
        if (call != null) {
            call.cancel();
        }
        UploadTask task = find(localId);
        if (task != null) {
            replace(task.withStatus(UploadStatus.CANCELED, ""));
        }
        pumpQueue();
    }

    private void closeOrCollapse() {
        if (hasActiveTasks()) {
            expanded = false;
        } else {
            tasks.clear();
        }
        render();
    }

    private boolean hasActiveTasks() {
        for (UploadTask task : tasks) {
            if (task.isActive()) {
                return true;
            }
        }
        return false;
    }

    private UploadTask find(long localId) {
        for (UploadTask task : tasks) {
            if (task.getLocalId() == localId) {
                return task;
            }
        }
        return null;
    }

    private void replace(UploadTask next) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).getLocalId() == next.getLocalId()) {
                tasks.set(i, next);
                render();
                return;
            }
        }
    }

    private void render() {
        panel.setVisibility(tasks.isEmpty() ? View.GONE : View.VISIBLE);
        list.setVisibility(expanded && !tasks.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.submit(tasks);
        summary.setText("Uploads (" + tasks.size() + ")");
        totalProgress.setProgress(overallProgress());
    }

    private int overallProgress() {
        if (tasks.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (UploadTask task : tasks) {
            total += task.getProgressPercent();
        }
        return total / tasks.size();
    }
}
