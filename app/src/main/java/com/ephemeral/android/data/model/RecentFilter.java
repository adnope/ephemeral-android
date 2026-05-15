package com.ephemeral.android.data.model;

public enum RecentFilter {
    ANY_TIME(""),
    LAST_DAY("1d"),
    LAST_7_DAYS("7d"),
    LAST_14_DAYS("14d"),
    LAST_30_DAYS("30d"),
    LAST_90_DAYS("90d"),
    LAST_6_MONTHS("6mo"),
    LAST_YEAR("1y");

    private final String wireValue;

    RecentFilter(String wireValue) {
        this.wireValue = wireValue;
    }

    public String getWireValue() {
        return wireValue;
    }
}
