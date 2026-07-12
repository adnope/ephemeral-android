package com.ephemeral.android;

import com.ephemeral.android.data.api.ItemEvent;
import com.ephemeral.android.data.api.ItemEventType;
import com.ephemeral.android.ui.common.ItemEventConsumer;
import com.ephemeral.android.ui.common.ItemEventRouter;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public final class ItemEventRouterTest {
    @Test
    public void dispatchesEveryEventToChatAndHistory() {
        AtomicInteger chatEvents = new AtomicInteger();
        AtomicInteger historyEvents = new AtomicInteger();
        ItemEvent event = new ItemEvent(ItemEventType.UPDATED, 42);

        ItemEventRouter.dispatch(event, ignored -> chatEvents.incrementAndGet(),
                ignored -> historyEvents.incrementAndGet(), null);

        assertEquals(1, chatEvents.get());
        assertEquals(1, historyEvents.get());
    }

    @Test
    public void doesNotDispatchTwiceWhenActiveScreenIsACollection() {
        AtomicInteger events = new AtomicInteger();
        ItemEventConsumer chat = ignored -> events.incrementAndGet();

        ItemEventRouter.dispatch(new ItemEvent(ItemEventType.NEW, 43), chat, null, chat);

        assertEquals(1, events.get());
    }

    @Test
    public void alsoDispatchesToDistinctOverlay() {
        AtomicInteger overlayEvents = new AtomicInteger();

        ItemEventRouter.dispatch(new ItemEvent(ItemEventType.DELETED, 44), ignored -> {
        }, ignored -> {
        }, ignored -> overlayEvents.incrementAndGet());

        assertEquals(1, overlayEvents.get());
    }
}
