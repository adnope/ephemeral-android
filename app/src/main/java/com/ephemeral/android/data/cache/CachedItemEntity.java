package com.ephemeral.android.data.cache;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "cached_items",
        indices = {
                @Index(value = {"type", "id"}),
                @Index(value = {"createdAtEpochMillis"})
        })
public final class CachedItemEntity {
    @PrimaryKey
    public long id;
    public String type;
    public String contentRef;
    public String filename;
    public long filesizeBytes;
    public int width;
    public int height;
    public String duration;
    public String mime;
    public String thumbRef;
    public String playbackRef;
    public String playbackMime;
    public String hlsRef;
    public boolean processing;
    public long createdAtEpochMillis;
    public boolean previewable;
    public long cacheBytes;
    public long cachedAtEpochMillis;
}
