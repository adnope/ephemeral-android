package com.ephemeral.android.data.api;

public interface DownloadProgressListener {
    void onProgress(long downloadedBytes, long totalBytes);
}
