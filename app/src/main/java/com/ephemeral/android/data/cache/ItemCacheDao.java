package com.ephemeral.android.data.cache;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ItemCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertItems(List<CachedItemEntity> items);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertSyncState(CacheSyncStateEntity state);

    @Query("SELECT * FROM cached_items "
            + "WHERE (:cursor = 0 OR id < :cursor) "
            + "ORDER BY id DESC LIMIT :limit")
    List<CachedItemEntity> chatPage(long cursor, int limit);

    @Query("SELECT * FROM cached_items "
            + "WHERE (:cursor = 0 OR id < :cursor) "
            + "AND (:type = '' OR type = :type) "
            + "AND (:fromMillis = 0 OR createdAtEpochMillis >= :fromMillis) "
            + "AND (:toMillis = 0 OR createdAtEpochMillis <= :toMillis) "
            + "AND (:query = '' OR lower(filename) LIKE '%' || :query || '%' "
            + "OR lower(contentRef) LIKE '%' || :query || '%' "
            + "OR (:searchBody = 1 AND lower(contentRef) LIKE '%' || :query || '%')) "
            + "ORDER BY id DESC LIMIT :limit")
    List<CachedItemEntity> historyPage(long cursor, String type, String query, int searchBody,
            long fromMillis, long toMillis, int limit);

    @Query("DELETE FROM cached_items WHERE id = :itemId")
    void deleteItem(long itemId);

    @Query("DELETE FROM cached_items")
    void clearItems();

    @Query("DELETE FROM cache_sync_state")
    void clearSyncState();

    @Query("SELECT COALESCE(SUM(cacheBytes), 0) FROM cached_items")
    long totalCacheBytes();

    @Query("SELECT * FROM cached_items ORDER BY cachedAtEpochMillis ASC, id ASC LIMIT :limit")
    List<CachedItemEntity> oldestItems(int limit);
}
