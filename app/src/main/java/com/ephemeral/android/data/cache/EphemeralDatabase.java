package com.ephemeral.android.data.cache;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {CachedItemEntity.class, CacheSyncStateEntity.class},
        version = 1,
        exportSchema = false)
public abstract class EphemeralDatabase extends RoomDatabase {
    public abstract ItemCacheDao itemCacheDao();

    public static EphemeralDatabase create(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(), EphemeralDatabase.class,
                        "ephemeral-cache.db")
                .build();
    }
}
