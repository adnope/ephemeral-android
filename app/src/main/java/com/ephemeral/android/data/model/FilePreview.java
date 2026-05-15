package com.ephemeral.android.data.model;

public final class FilePreview {
    private final long id;
    private final String filename;
    private final String mime;
    private final String language;
    private final String content;
    private final long filesizeBytes;
    private final long createdAtEpochMillis;
    private final String downloadRef;

    public FilePreview(long id, String filename, String mime, String language, String content,
            long filesizeBytes, long createdAtEpochMillis, String downloadRef) {
        this.id = id;
        this.filename = filename == null ? "" : filename;
        this.mime = mime == null ? "" : mime;
        this.language = language == null ? "plaintext" : language;
        this.content = content == null ? "" : content;
        this.filesizeBytes = filesizeBytes;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.downloadRef = downloadRef == null ? "" : downloadRef;
    }

    public long getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public String getMime() {
        return mime;
    }

    public String getLanguage() {
        return language;
    }

    public String getContent() {
        return content;
    }

    public long getFilesizeBytes() {
        return filesizeBytes;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    public String getDownloadRef() {
        return downloadRef;
    }
}
