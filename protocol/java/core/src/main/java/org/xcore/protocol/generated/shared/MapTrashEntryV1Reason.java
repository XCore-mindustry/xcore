package org.xcore.protocol.generated.shared;

public enum MapTrashEntryV1Reason {
    REMOVED("removed"),
    REPLACED("replaced");

    private final String value;

    MapTrashEntryV1Reason(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static MapTrashEntryV1Reason fromValue(String value) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        for (MapTrashEntryV1Reason candidate : values()) {
            if (candidate.value.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown enum value: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
