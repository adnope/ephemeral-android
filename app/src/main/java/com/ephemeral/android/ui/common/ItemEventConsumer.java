package com.ephemeral.android.ui.common;

import com.ephemeral.android.data.api.ItemEvent;

public interface ItemEventConsumer {
    void onItemEvent(ItemEvent event);
}
