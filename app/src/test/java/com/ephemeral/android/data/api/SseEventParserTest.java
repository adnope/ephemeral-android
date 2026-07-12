package com.ephemeral.android.data.api;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class SseEventParserTest {
    @Test
    public void parsesSequencedItemEventPayload() {
        ItemEvent event = SseEventParser.parse("item:updated", "42");

        assertEquals(ItemEventType.UPDATED, event.getType());
        assertEquals(42, event.getItemId());
        assertEquals("17", SseEventParser.normalizeEventId(" 17 "));
    }

    @Test
    public void parsesStreamResetWithoutItemId() {
        ItemEvent event = SseEventParser.parse("stream:reset", "reconcile");

        assertEquals(ItemEventType.RESET, event.getType());
        assertEquals(0, event.getItemId());
    }

    @Test
    public void ignoresUnknownOrMalformedEvents() {
        assertNull(SseEventParser.parse("unknown", "42"));
        assertNull(SseEventParser.parse("item:new", "not-an-id"));
        assertNull(SseEventParser.normalizeEventId("bad\0id"));
    }
}
