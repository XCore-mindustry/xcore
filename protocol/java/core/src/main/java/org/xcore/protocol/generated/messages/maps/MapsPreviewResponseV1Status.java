package org.xcore.protocol.generated.messages.maps;

public enum MapsPreviewResponseV1Status {
    OK("ok"),
    NOT_FOUND("not_found"),
    FAILED("failed");

    private final String value;

    MapsPreviewResponseV1Status(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static MapsPreviewResponseV1Status fromValue(String value) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        for (MapsPreviewResponseV1Status candidate : values()) {
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
