package com.ephemeral.android.data.api;

public interface UploadProgressListener {
    void onProgress(long uploadedBytes, long totalBytes);
}
