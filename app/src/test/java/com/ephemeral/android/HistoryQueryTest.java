package com.ephemeral.android;

import com.ephemeral.android.data.model.HistoryQuery;
import com.ephemeral.android.data.model.ItemTypeFilter;
import com.ephemeral.android.data.model.RecentFilter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class HistoryQueryTest {
    @Test
    public void trimsQueryAndMapsRecentFilter() {
        HistoryQuery query = new HistoryQuery(0, ItemTypeFilter.IMAGES, "  screenshot  ",
                true, "2026-01-01", "2026-02-01", RecentFilter.LAST_7_DAYS);

        assertEquals("screenshot", query.getQuery());
        assertEquals("7d", query.getRecent().getWireValue());
    }

    @Test
    public void clearSearchPreservesTypeFilter() {
        HistoryQuery query = new HistoryQuery(55, ItemTypeFilter.VIDEOS, "demo",
                true, "2026-01-01", "2026-02-01", RecentFilter.LAST_YEAR);

        HistoryQuery cleared = query.clearSearchPreservingType();

        assertEquals(ItemTypeFilter.VIDEOS, cleared.getTypeFilter());
        assertEquals("", cleared.getQuery());
        assertFalse(cleared.isSearchBody());
        assertEquals(RecentFilter.ANY_TIME, cleared.getRecent());
    }
}
