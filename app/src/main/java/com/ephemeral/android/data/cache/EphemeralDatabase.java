package com.ephemeral.android.data.cache;

import android.content.Context;

import androidx.room.Database;
import androidx.room.migration.Migration;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {CachedItemEntity.class, CacheSyncStateEntity.class},
        version = 3,
        exportSchema = false)
public abstract class EphemeralDatabase extends RoomDatabase {
    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE cached_items ADD COLUMN playbackRef TEXT");
            database.execSQL("ALTER TABLE cached_items ADD COLUMN playbackMime TEXT");
            database.execSQL("ALTER TABLE cached_items ADD COLUMN hlsRef TEXT");
            database.execSQL("ALTER TABLE cached_items ADD COLUMN processing INTEGER NOT NULL DEFAULT 0");
        }
    };

    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE cached_items ADD COLUMN publicLinkActive INTEGER NOT NULL DEFAULT 0");
        }
    };

    public abstract ItemCacheDao itemCacheDao();

    public static EphemeralDatabase create(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(), EphemeralDatabase.class,
                        "ephemeral-cache.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build();
    }
}
