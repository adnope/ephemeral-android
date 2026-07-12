package com.ephemeral.android.ui.common;

import com.ephemeral.android.data.api.ItemEvent;

public final class ItemEventRouter {
    private ItemEventRouter() {
    }

    public static void dispatch(ItemEvent event, ItemEventConsumer chat, ItemEventConsumer history,
            ItemEventConsumer activeOverlay) {
        dispatchOnce(event, chat, null, null);
        dispatchOnce(event, history, chat, null);
        dispatchOnce(event, activeOverlay, chat, history);
    }

    private static void dispatchOnce(ItemEvent event, ItemEventConsumer consumer,
            ItemEventConsumer first, ItemEventConsumer second) {
        if (consumer != null && consumer != first && consumer != second) {
            consumer.onItemEvent(event);
        }
    }
}
