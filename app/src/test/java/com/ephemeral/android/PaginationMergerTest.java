package com.ephemeral.android;

import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemMetadata;
import com.ephemeral.android.data.model.ItemType;
import com.ephemeral.android.util.PaginationMerger;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class PaginationMergerTest {
    @Test
    public void appendIgnoresDuplicateIds() {
        Item one = item(1);
        Item two = item(2);
        Item duplicateTwo = item(2);
        Item three = item(3);

        List<Item> merged = PaginationMerger.appendIgnoringDuplicates(
                Arrays.asList(one, two), Arrays.asList(duplicateTwo, three));

        assertEquals(3, merged.size());
        assertEquals(1, merged.get(0).getId());
        assertEquals(2, merged.get(1).getId());
        assertEquals(3, merged.get(2).getId());
    }

    private Item item(long id) {
        return new Item(id, ItemType.TEXT, "text", "", -1, ItemMetadata.EMPTY, 1, false);
    }
}
