package com.ephemeral.android.data.cache;

import com.ephemeral.android.AppExecutors;
import com.ephemeral.android.data.model.HistoryQuery;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemTypeFilter;
import com.ephemeral.android.data.model.Page;
import com.ephemeral.android.data.model.RecentFilter;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ItemCacheStore {
    public interface Callback<T> {
        void onResult(T value);
    }

    private static final long MAX_CACHE_BYTES = 512L * 1024L * 1024L;
    private static final String SYNC_CHAT = "chat";
    private static final int EXTRA_ROW_FOR_HAS_MORE = 1;
    private static final int TRIM_BATCH_SIZE = 200;

    private final ItemCacheDao dao;
    private final AppExecutors executors;

    public ItemCacheStore(EphemeralDatabase database, AppExecutors executors) {
        this.dao = database.itemCacheDao();
        this.executors = executors;
    }

    public void readChatPage(long cursor, int pageSize, Callback<Page<Item>> callback) {
        executors.disk().execute(() -> {
            Page<Item> page = pageFromEntities(dao.chatPage(Math.max(0, cursor), pageSize + EXTRA_ROW_FOR_HAS_MORE),
                    pageSize);
            executors.main().execute(() -> callback.onResult(page));
        });
    }

    public void readHistoryPage(HistoryQuery query, int pageSize, Callback<Page<Item>> callback) {
        executors.disk().execute(() -> {
            HistoryBounds bounds = HistoryBounds.from(query);
            Page<Item> page = pageFromEntities(dao.historyPage(
                    query.getCursor(),
                    typeFilter(query.getTypeFilter()),
                    query.getQuery().toLowerCase(Locale.US),
                    query.isSearchBody() ? 1 : 0,
                    bounds.fromMillis,
                    bounds.toMillis,
                    pageSize + EXTRA_ROW_FOR_HAS_MORE), pageSize);
            executors.main().execute(() -> callback.onResult(page));
        });
    }

    public void cacheChatPage(Page<Item> page) {
        cacheItems(page.getItems(), SYNC_CHAT, page.getNextCursor(), page.hasMore());
    }

    public void cacheHistoryPage(HistoryQuery query, Page<Item> page) {
        cacheItems(page.getItems(), historySyncKey(query), page.getNextCursor(), page.hasMore());
    }

    public void cacheItem(Item item) {
        List<Item> items = new ArrayList<>();
        items.add(item);
        cacheItems(items, "", 0, false);
    }

    public void deleteItem(long itemId) {
        executors.disk().execute(() -> dao.deleteItem(itemId));
    }

    public void clear() {
        executors.disk().execute(() -> {
            dao.clearItems();
            dao.clearSyncState();
        });
    }

    private void cacheItems(List<Item> items, String syncKey, long nextCursor, boolean hasMore) {
        if (items.isEmpty() && syncKey.isEmpty()) {
            return;
        }
        executors.disk().execute(() -> {
            long now = System.currentTimeMillis();
            if (!items.isEmpty()) {
                List<CachedItemEntity> entities = new ArrayList<>(items.size());
                for (Item item : items) {
                    entities.add(ItemCacheMapper.toEntity(item, now));
                }
                dao.upsertItems(entities);
            }
            if (!syncKey.isEmpty()) {
                CacheSyncStateEntity state = new CacheSyncStateEntity();
                state.syncKey = syncKey;
                state.nextCursor = nextCursor;
                state.hasMore = hasMore;
                state.updatedAtEpochMillis = now;
                dao.upsertSyncState(state);
            }
            trimToLimit();
        });
    }

    private Page<Item> pageFromEntities(List<CachedItemEntity> entities, int pageSize) {
        boolean hasMore = entities.size() > pageSize;
        int end = Math.min(pageSize, entities.size());
        List<Item> items = new ArrayList<>(end);
        for (int i = 0; i < end; i++) {
            items.add(ItemCacheMapper.toItem(entities.get(i)));
        }
        long nextCursor = hasMore && !items.isEmpty() ? items.get(items.size() - 1).getId() : 0;
        return new Page<>(items, nextCursor, hasMore);
    }

    private void trimToLimit() {
        long total = dao.totalCacheBytes();
        if (total <= MAX_CACHE_BYTES) {
            return;
        }
        while (total > MAX_CACHE_BYTES) {
            List<CachedItemEntity> oldestItems = dao.oldestItems(TRIM_BATCH_SIZE);
            if (oldestItems.isEmpty()) {
                return;
            }
            for (CachedItemEntity item : oldestItems) {
                if (total <= MAX_CACHE_BYTES) {
                    return;
                }
                dao.deleteItem(item.id);
                total -= Math.max(0, item.cacheBytes);
            }
        }
    }

    private String typeFilter(ItemTypeFilter filter) {
        if (filter == ItemTypeFilter.IMAGES) {
            return "image";
        }
        if (filter == ItemTypeFilter.VIDEOS) {
            return "video";
        }
        if (filter == ItemTypeFilter.FILES) {
            return "file";
        }
        return "";
    }

    private String historySyncKey(HistoryQuery query) {
        return "history|" + typeFilter(query.getTypeFilter()) + "|" + query.getQuery() + "|"
                + query.isSearchBody() + "|" + query.getDateFromIso() + "|" + query.getDateToIso()
                + "|" + query.getRecent().getWireValue();
    }

    private static final class HistoryBounds {
        final long fromMillis;
        final long toMillis;

        HistoryBounds(long fromMillis, long toMillis) {
            this.fromMillis = fromMillis;
            this.toMillis = toMillis;
        }

        static HistoryBounds from(HistoryQuery query) {
            long from = startOfDayMillis(query.getDateFromIso());
            long to = endOfDayMillis(query.getDateToIso());
            long recentFrom = recentStartMillis(query.getRecent());
            if (recentFrom > 0) {
                from = from == 0 ? recentFrom : Math.max(from, recentFrom);
            }
            return new HistoryBounds(from, to);
        }

        private static long startOfDayMillis(String value) {
            if (value == null || value.isEmpty()) {
                return 0;
            }
            try {
                return LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (DateTimeParseException error) {
                return 0;
            }
        }

        private static long endOfDayMillis(String value) {
            if (value == null || value.isEmpty()) {
                return 0;
            }
            try {
                return LocalDate.parse(value).plusDays(1).atStartOfDay(ZoneId.systemDefault())
                        .toInstant().toEpochMilli() - 1L;
            } catch (DateTimeParseException error) {
                return 0;
            }
        }

        private static long recentStartMillis(RecentFilter recent) {
            long now = System.currentTimeMillis();
            switch (recent) {
                case LAST_DAY:
                    return now - days(1);
                case LAST_7_DAYS:
                    return now - days(7);
                case LAST_14_DAYS:
                    return now - days(14);
                case LAST_30_DAYS:
                    return now - days(30);
                case LAST_90_DAYS:
                    return now - days(90);
                case LAST_6_MONTHS:
                    return now - days(183);
                case LAST_YEAR:
                    return now - days(365);
                case ANY_TIME:
                default:
                    return 0;
            }
        }

        private static long days(int days) {
            return days * 24L * 60L * 60L * 1000L;
        }
    }
}
