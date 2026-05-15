package com.ephemeral.android.data.api;

public final class RuntimeConfig {
    private final int chatPageSize;
    private final int historyPageSize;
    private final long maxUploadSizeBytes;
    private final long textPreviewMaxBytes;
    private final int uploadConcurrency;

    public RuntimeConfig(int chatPageSize, int historyPageSize, long maxUploadSizeBytes,
            long textPreviewMaxBytes, int uploadConcurrency) {
        this.chatPageSize = Math.max(1, chatPageSize);
        this.historyPageSize = Math.max(1, historyPageSize);
        this.maxUploadSizeBytes = Math.max(0, maxUploadSizeBytes);
        this.textPreviewMaxBytes = Math.max(0, textPreviewMaxBytes);
        this.uploadConcurrency = Math.max(1, uploadConcurrency);
    }

    public int getChatPageSize() {
        return chatPageSize;
    }

    public int getHistoryPageSize() {
        return historyPageSize;
    }

    public long getMaxUploadSizeBytes() {
        return maxUploadSizeBytes;
    }

    public long getTextPreviewMaxBytes() {
        return textPreviewMaxBytes;
    }

    public int getUploadConcurrency() {
        return uploadConcurrency;
    }
}
