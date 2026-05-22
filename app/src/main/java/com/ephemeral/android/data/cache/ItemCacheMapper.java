package com.ephemeral.android.data.cache;

import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemMetadata;
import com.ephemeral.android.data.model.ItemType;

final class ItemCacheMapper {
    private ItemCacheMapper() {
    }

    static CachedItemEntity toEntity(Item item, long cachedAtEpochMillis) {
        CachedItemEntity entity = new CachedItemEntity();
        entity.id = item.getId();
        entity.type = item.getType().getWireName();
        entity.contentRef = item.getContentRef();
        entity.filename = item.getFilename();
        entity.filesizeBytes = item.getFilesizeBytes();
        ItemMetadata metadata = item.getMetadata();
        entity.width = metadata.getWidth();
        entity.height = metadata.getHeight();
        entity.duration = metadata.getDuration();
        entity.mime = metadata.getMime();
        entity.thumbRef = metadata.getThumbRef();
        entity.playbackRef = metadata.getPlaybackRef();
        entity.playbackMime = metadata.getPlaybackMime();
        entity.hlsRef = metadata.getHlsRef();
        entity.processing = metadata.isProcessing();
        entity.createdAtEpochMillis = item.getCreatedAtEpochMillis();
        entity.previewable = item.isPreviewable();
        entity.cacheBytes = estimateCacheBytes(entity);
        entity.cachedAtEpochMillis = cachedAtEpochMillis;
        return entity;
    }

    static Item toItem(CachedItemEntity entity) {
        return new Item(
                entity.id,
                itemType(entity.type),
                clean(entity.contentRef),
                clean(entity.filename),
                entity.filesizeBytes,
                new ItemMetadata(entity.width, entity.height, clean(entity.duration), clean(entity.mime),
                        clean(entity.thumbRef), clean(entity.playbackRef), clean(entity.playbackMime),
                        clean(entity.hlsRef), entity.processing),
                entity.createdAtEpochMillis,
                entity.previewable);
    }

    private static long estimateCacheBytes(CachedItemEntity entity) {
        return 256L
                + bytes(entity.type)
                + bytes(entity.contentRef)
                + bytes(entity.filename)
                + bytes(entity.duration)
                + bytes(entity.mime)
                + bytes(entity.thumbRef)
                + bytes(entity.playbackRef)
                + bytes(entity.playbackMime)
                + bytes(entity.hlsRef);
    }

    private static long bytes(String value) {
        return value == null ? 0L : value.length() * 2L;
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }

    private static ItemType itemType(String value) {
        try {
            return ItemType.fromWireName(clean(value));
        } catch (IllegalArgumentException error) {
            return ItemType.FILE;
        }
    }
}
