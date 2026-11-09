package org.xcore.protocol.generated.messages.maps;

public enum MapsFileResponseV1Status {
    OK("ok"),
    NOT_FOUND("not_found"),
    TOO_LARGE("too_large"),
    FAILED("failed");

    private final String value;

    MapsFileResponseV1Status(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static MapsFileResponseV1Status fromValue(String value) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        for (MapsFileResponseV1Status candidate : values()) {
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
