package com.ephemeral.android;

import com.ephemeral.android.data.model.UploadStatus;
import com.ephemeral.android.data.model.UploadTask;
import com.ephemeral.android.ui.upload.UploadQueueModel;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class UploadStateTest {
    @Test
    public void reportsProgressAndRetryableTransitions() {
        UploadTask uploading = new UploadTask(1, null, "a.bin", 100,
                UploadStatus.UPLOADING, 50, 100, "");
        UploadTask failed = uploading.withStatus(UploadStatus.FAILED, "network");
        UploadTask retry = failed.withStatus(UploadStatus.QUEUED, "");

        assertEquals(50, uploading.getProgressPercent());
        assertEquals(UploadStatus.FAILED, failed.getStatus());
        assertEquals(UploadStatus.QUEUED, retry.getStatus());
    }

    @Test
    public void clearCompletedKeepsOnlyActiveQueueEntries() {
        List<UploadTask> remaining = UploadQueueModel.clearCompleted(Arrays.asList(
                task(1, UploadStatus.DONE),
                task(2, UploadStatus.FAILED),
                task(3, UploadStatus.CANCELED),
                task(4, UploadStatus.QUEUED),
                task(5, UploadStatus.UPLOADING)));

        assertEquals(2, remaining.size());
        assertEquals(4, remaining.get(0).getLocalId());
        assertEquals(5, remaining.get(1).getLocalId());
    }

    private UploadTask task(long id, UploadStatus status) {
        return new UploadTask(id, null, "x", 1, status, 0, 1, "");
    }
}
