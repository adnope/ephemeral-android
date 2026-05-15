package com.ephemeral.android.data.model;

import android.net.Uri;

public final class UploadTask {
    private final long localId;
    private final Uri sourceUri;
    private final String displayName;
    private final long sizeBytes;
    private final UploadStatus status;
    private final long uploadedBytes;
    private final long totalBytes;
    private final String errorMessage;

    public UploadTask(long localId, Uri sourceUri, String displayName, long sizeBytes,
            UploadStatus status, long uploadedBytes, long totalBytes, String errorMessage) {
        this.localId = localId;
        this.sourceUri = sourceUri;
        this.displayName = displayName == null ? "Upload" : displayName;
        this.sizeBytes = sizeBytes;
        this.status = status == null ? UploadStatus.QUEUED : status;
        this.uploadedBytes = Math.max(0, uploadedBytes);
        this.totalBytes = totalBytes;
        this.errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public long getLocalId() {
        return localId;
    }

    public Uri getSourceUri() {
        return sourceUri;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public UploadStatus getStatus() {
        return status;
    }

    public long getUploadedBytes() {
        return uploadedBytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public int getProgressPercent() {
        if (totalBytes <= 0) {
            return status == UploadStatus.DONE ? 100 : 0;
        }
        long capped = Math.min(uploadedBytes, totalBytes);
        return (int) Math.min(100, (capped * 100) / totalBytes);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isActive() {
        return status == UploadStatus.QUEUED || status == UploadStatus.UPLOADING;
    }

    public UploadTask withStatus(UploadStatus nextStatus, String nextError) {
        long nextUploaded = nextStatus == UploadStatus.DONE && totalBytes > 0 ? totalBytes : uploadedBytes;
        return new UploadTask(localId, sourceUri, displayName, sizeBytes, nextStatus,
                nextUploaded, totalBytes, nextError);
    }

    public UploadTask withProgress(long nextUploaded, long nextTotal) {
        return new UploadTask(localId, sourceUri, displayName, sizeBytes, UploadStatus.UPLOADING,
                nextUploaded, nextTotal, "");
    }
}
