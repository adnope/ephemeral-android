package com.ephemeral.android.data.model;

public final class ItemMetadata {
    public static final ItemMetadata EMPTY = new ItemMetadata(0, 0, "", "", "", "", "", "", false);

    private final int width;
    private final int height;
    private final String duration;
    private final String mime;
    private final String thumbRef;
    private final String playbackRef;
    private final String playbackMime;
    private final String hlsRef;
    private final boolean processing;

    public ItemMetadata(int width, int height, String duration, String mime, String thumbRef) {
        this(width, height, duration, mime, thumbRef, "", "", "", false);
    }

    public ItemMetadata(int width, int height, String duration, String mime, String thumbRef,
            String playbackRef, String playbackMime, String hlsRef, boolean processing) {
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        this.duration = duration == null ? "" : duration;
        this.mime = mime == null ? "" : mime;
        this.thumbRef = thumbRef == null ? "" : thumbRef;
        this.playbackRef = playbackRef == null ? "" : playbackRef;
        this.playbackMime = playbackMime == null ? "" : playbackMime;
        this.hlsRef = hlsRef == null ? "" : hlsRef;
        this.processing = processing;
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

    public String getPlaybackRef() {
        return playbackRef;
    }

    public String getPlaybackMime() {
        return playbackMime;
    }

    public String getHlsRef() {
        return hlsRef;
    }

    public boolean isProcessing() {
        return processing;
    }

    public boolean hasDimensions() {
        return width > 0 && height > 0;
    }
}
