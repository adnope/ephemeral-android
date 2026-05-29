package com.ephemeral.android.ui.chat;

import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.SendStatus;

final class ChatEntry {
    private final long stableId;
    private final Item item;
    private final String optimisticText;
    private final long createdAtEpochMillis;
    private final SendStatus sendStatus;

    private ChatEntry(long stableId, Item item, String optimisticText, long createdAtEpochMillis,
            SendStatus sendStatus) {
        this.stableId = stableId;
        this.item = item;
        this.optimisticText = optimisticText;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.sendStatus = sendStatus;
    }

    static ChatEntry fromItem(Item item) {
        return new ChatEntry(item.getId(), item, "", item.getCreatedAtEpochMillis(), SendStatus.SENT);
    }

    static ChatEntry optimistic(long localId, String text) {
        return new ChatEntry(localId, null, text, System.currentTimeMillis(), SendStatus.SENDING);
    }

    ChatEntry withStatus(SendStatus status) {
        return new ChatEntry(stableId, item, optimisticText, createdAtEpochMillis, status);
    }

    ChatEntry withItem(Item updatedItem) {
        return new ChatEntry(stableId, updatedItem, optimisticText, createdAtEpochMillis, sendStatus);
    }

    long getStableId() {
        return stableId;
    }

    boolean isOptimistic() {
        return item == null;
    }

    Item getItem() {
        return item;
    }

    String getText() {
        return isOptimistic() ? optimisticText : item.getContentRef();
    }

    long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    SendStatus getSendStatus() {
        return sendStatus;
    }
}
