package com.ephemeral.android.data.api;

import com.ephemeral.android.util.SimpleJsonParser;

import java.util.Map;

final class SseEventParser {
    private SseEventParser() {
    }

    static ItemEvent parse(String eventName, String data) {
        ItemEventType type = eventType(eventName);
        if (type == null) {
            return null;
        }
        if (type == ItemEventType.RESET) {
            return new ItemEvent(type, 0);
        }
        long itemId = parseItemId(data);
        return itemId > 0 ? new ItemEvent(type, itemId) : null;
    }

    static String normalizeEventId(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.indexOf('\0') >= 0 ? null : clean;
    }

    private static ItemEventType eventType(String eventName) {
        if ("item:new".equals(eventName)) {
            return ItemEventType.NEW;
        }
        if ("item:updated".equals(eventName)) {
            return ItemEventType.UPDATED;
        }
        if ("item:deleted".equals(eventName)) {
            return ItemEventType.DELETED;
        }
        if ("stream:reset".equals(eventName)) {
            return ItemEventType.RESET;
        }
        return null;
    }

    private static long parseItemId(String data) {
        String clean = data == null ? "" : data.trim();
        if (clean.isEmpty()) {
            return 0;
        }
        try {
            if (clean.startsWith("{")) {
                Map<String, Object> object = SimpleJsonParser.parseObject(clean);
                Object itemId = object.containsKey("itemId") ? object.get("itemId") : object.get("id");
                return Long.parseLong(String.valueOf(itemId));
            }
            return Long.parseLong(clean);
        } catch (IllegalArgumentException error) {
            return 0;
        }
    }
}
