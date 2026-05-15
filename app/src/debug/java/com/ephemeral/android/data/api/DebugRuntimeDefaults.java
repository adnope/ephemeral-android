package com.ephemeral.android.data.api;

public final class DebugRuntimeDefaults {
    private DebugRuntimeDefaults() {
    }

    public static RuntimeConfig create() {
        return new RuntimeConfig(20, 30, 128L * 1024L * 1024L, 512L * 1024L, 2);
    }
}
