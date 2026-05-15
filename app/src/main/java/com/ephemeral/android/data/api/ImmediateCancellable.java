package com.ephemeral.android.data.api;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ImmediateCancellable implements Cancellable {
    private final AtomicBoolean canceled = new AtomicBoolean(false);

    @Override
    public void cancel() {
        canceled.set(true);
    }

    @Override
    public boolean isCanceled() {
        return canceled.get();
    }
}
