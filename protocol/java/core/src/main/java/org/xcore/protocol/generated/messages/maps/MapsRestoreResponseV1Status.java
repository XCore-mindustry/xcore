package org.xcore.protocol.generated.messages.maps;

public enum MapsRestoreResponseV1Status {
    RESTORED("restored"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    FAILED("failed");

    private final String value;

    MapsRestoreResponseV1Status(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static MapsRestoreResponseV1Status fromValue(String value) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        for (MapsRestoreResponseV1Status candidate : values()) {
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
