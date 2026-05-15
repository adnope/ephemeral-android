package com.ephemeral.android.data.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Page<T> {
    private final List<T> items;
    private final long nextCursor;
    private final boolean hasMore;

    public Page(List<T> items, long nextCursor, boolean hasMore) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
    }

    public List<T> getItems() {
        return items;
    }

    public long getNextCursor() {
        return nextCursor;
    }

    public boolean hasMore() {
        return hasMore;
    }
}
