package com.ephemeral.android.data.model;

public enum ItemType {
    TEXT("text"),
    IMAGE("image"),
    VIDEO("video"),
    FILE("file");

    private final String wireName;

    ItemType(String wireName) {
        this.wireName = wireName;
    }

    public String getWireName() {
        return wireName;
    }

    public static ItemType fromWireName(String value) {
        for (ItemType type : values()) {
            if (type.wireName.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown item type: " + value);
    }
}
