package com.ephemeral.android.data.api;

public interface ItemEventListener {
    void onEvent(ItemEvent event);

    void onError(ApiError error);
}
