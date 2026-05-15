package com.ephemeral.android.data.api;

public interface Cancellable {
    void cancel();

    boolean isCanceled();
}
