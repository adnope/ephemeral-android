package com.ephemeral.android.data.cache;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "cache_sync_state")
public final class CacheSyncStateEntity {
    @PrimaryKey
    @NonNull
    public String syncKey;
    public long nextCursor;
    public boolean hasMore;
    public long updatedAtEpochMillis;
}
