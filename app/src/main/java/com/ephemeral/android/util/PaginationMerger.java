package com.ephemeral.android.util;

import com.ephemeral.android.data.model.Item;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PaginationMerger {
    private PaginationMerger() {
    }

    public static List<Item> appendIgnoringDuplicates(List<Item> existing, List<Item> incoming) {
        Set<Long> seen = new HashSet<>();
        List<Item> merged = new ArrayList<>(existing.size() + incoming.size());
        for (Item item : existing) {
            if (seen.add(item.getId())) {
                merged.add(item);
            }
        }
        for (Item item : incoming) {
            if (seen.add(item.getId())) {
                merged.add(item);
            }
        }
        return merged;
    }
}
