package org.xcore.protocol.generated.messages.maps;

public enum MapsRenderResponseV1Status {
    OK("ok"),
    BUSY("busy"),
    TOO_LARGE("too_large"),
    INVALID_MAP("invalid_map"),
    DOWNLOAD_FAILED("download_failed"),
    FAILED("failed");

    private final String value;

    MapsRenderResponseV1Status(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static MapsRenderResponseV1Status fromValue(String value) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        for (MapsRenderResponseV1Status candidate : values()) {
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
