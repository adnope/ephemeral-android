package com.ephemeral.android.data.api;

public interface ApiCallback<T> {
    void onSuccess(T value);

    void onError(ApiError error);
}
