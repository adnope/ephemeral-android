package com.ephemeral.android.data.api;

import android.net.Uri;

public final class FileDownloadResult {
    private final Uri uri;
    private final String filename;

    public FileDownloadResult(Uri uri, String filename) {
        this.uri = uri;
        this.filename = filename == null ? "" : filename;
    }

    public Uri getUri() {
        return uri;
    }

    public String getFilename() {
        return filename;
    }
}
