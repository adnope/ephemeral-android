package com.ephemeral.android.data.model;

public enum VisibilityFilter {
    ALL("all"),
    PUBLIC("public"),
    PRIVATE("private");

    private final String wireValue;

    VisibilityFilter(String wireValue) {
        this.wireValue = wireValue;
    }

    public String getWireValue() {
        return wireValue;
    }
}
