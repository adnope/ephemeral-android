package com.ephemeral.android.data.api;

public final class ServerState {
    private final boolean setupRequired;

    public ServerState(boolean setupRequired) {
        this.setupRequired = setupRequired;
    }

    public boolean isSetupRequired() {
        return setupRequired;
    }
}
