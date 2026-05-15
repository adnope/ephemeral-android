package com.ephemeral.android.ui.upload;

import com.ephemeral.android.data.model.UploadStatus;
import com.ephemeral.android.data.model.UploadTask;

import java.util.ArrayList;
import java.util.List;

public final class UploadQueueModel {
    private UploadQueueModel() {
    }

    public static List<UploadTask> clearCompleted(List<UploadTask> tasks) {
        List<UploadTask> remaining = new ArrayList<>();
        for (UploadTask task : tasks) {
            if (task.getStatus() == UploadStatus.QUEUED || task.getStatus() == UploadStatus.UPLOADING) {
                remaining.add(task);
            }
        }
        return remaining;
    }

    public static int activeCount(List<UploadTask> tasks) {
        int active = 0;
        for (UploadTask task : tasks) {
            if (task.getStatus() == UploadStatus.UPLOADING) {
                active++;
            }
        }
        return active;
    }
}
