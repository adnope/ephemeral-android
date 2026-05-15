package com.ephemeral.android.data.model;

public final class ItemMetadata {
    public static final ItemMetadata EMPTY = new ItemMetadata(0, 0, "", "", "");

    private final int width;
    private final int height;
    private final String duration;
    private final String mime;
    private final String thumbRef;

    public ItemMetadata(int width, int height, String duration, String mime, String thumbRef) {
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        this.duration = duration == null ? "" : duration;
        this.mime = mime == null ? "" : mime;
        this.thumbRef = thumbRef == null ? "" : thumbRef;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public String getDuration() {
        return duration;
    }

    public String getMime() {
        return mime;
    }

    public String getThumbRef() {
        return thumbRef;
    }

    public boolean hasDimensions() {
        return width > 0 && height > 0;
    }
}
