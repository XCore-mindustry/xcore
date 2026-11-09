package org.xcore.protocol.generated.shared;

public enum MapBlobRefV1ContentType {
    IMAGE_PNG("image/png"),
    APPLICATION_OCTET_STREAM("application/octet-stream");

    private final String value;

    MapBlobRefV1ContentType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static MapBlobRefV1ContentType fromValue(String value) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        for (MapBlobRefV1ContentType candidate : values()) {
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
