package com.ephemeral.android.data.model;

public final class Item {
    private final long id;
    private final ItemType type;
    private final String contentRef;
    private final String filename;
    private final long filesizeBytes;
    private final ItemMetadata metadata;
    private final long createdAtEpochMillis;
    private final boolean previewable;

    public Item(long id, ItemType type, String contentRef, String filename, long filesizeBytes,
            ItemMetadata metadata, long createdAtEpochMillis, boolean previewable) {
        if (id == 0) {
            throw new IllegalArgumentException("id must be stable and non-zero");
        }
        this.id = id;
        this.type = type == null ? ItemType.FILE : type;
        this.contentRef = contentRef == null ? "" : contentRef;
        this.filename = filename == null ? "" : filename;
        this.filesizeBytes = filesizeBytes;
        this.metadata = metadata == null ? ItemMetadata.EMPTY : metadata;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.previewable = previewable;
    }

    public long getId() {
        return id;
    }

    public ItemType getType() {
        return type;
    }

    public String getContentRef() {
        return contentRef;
    }

    public String getFilename() {
        return filename;
    }

    public long getFilesizeBytes() {
        return filesizeBytes;
    }

    public ItemMetadata getMetadata() {
        return metadata;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    public boolean isPreviewable() {
        return previewable;
    }

    public boolean isMedia() {
        return type == ItemType.IMAGE || type == ItemType.VIDEO;
    }
}
