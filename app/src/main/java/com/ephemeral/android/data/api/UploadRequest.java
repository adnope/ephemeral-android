package com.ephemeral.android.data.api;

import android.net.Uri;

public final class UploadRequest {
    private final Uri sourceUri;
    private final String displayName;
    private final long sizeBytes;
    private final String mimeType;

    public UploadRequest(Uri sourceUri, String displayName, long sizeBytes, String mimeType) {
        if (sourceUri == null) {
            throw new IllegalArgumentException("sourceUri is required");
        }
        this.sourceUri = sourceUri;
        this.displayName = displayName == null ? "Upload" : displayName;
        this.sizeBytes = sizeBytes;
        this.mimeType = mimeType == null || mimeType.trim().isEmpty()
                ? "application/octet-stream" : mimeType;
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

    public String getMimeType() {
        return mimeType;
    }
}
