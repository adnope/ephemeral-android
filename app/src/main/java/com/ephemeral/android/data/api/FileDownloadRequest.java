package com.ephemeral.android.data.api;

public final class FileDownloadRequest {
    private final long itemId;
    private final String contentRef;
    private final String filename;

    public FileDownloadRequest(long itemId, String contentRef, String filename) {
        this.itemId = itemId;
        this.contentRef = contentRef == null ? "" : contentRef;
        this.filename = filename == null ? "download" : filename;
    }

    public long getItemId() {
        return itemId;
    }

    public String getContentRef() {
        return contentRef;
    }

    public String getFilename() {
        return filename;
    }
}
