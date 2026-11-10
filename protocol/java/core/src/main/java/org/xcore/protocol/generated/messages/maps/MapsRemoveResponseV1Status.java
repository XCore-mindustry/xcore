package org.xcore.protocol.generated.messages.maps;

public enum MapsRemoveResponseV1Status {
    REMOVED("removed"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    FAILED("failed");

    private final String value;

    MapsRemoveResponseV1Status(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static MapsRemoveResponseV1Status fromValue(String value) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        for (MapsRemoveResponseV1Status candidate : values()) {
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
