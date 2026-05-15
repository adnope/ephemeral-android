package com.ephemeral.android.data.api;

public final class ItemEvent {
    private final ItemEventType type;
    private final long itemId;

    public ItemEvent(ItemEventType type, long itemId) {
        this.type = type == null ? ItemEventType.UPDATED : type;
        this.itemId = itemId;
    }

    public ItemEventType getType() {
        return type;
    }

    public long getItemId() {
        return itemId;
    }
}
