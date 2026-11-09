package org.xcore.protocol.generated.shared;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.xcore.protocol.generated.runtime.ProtocolPayload;

public record MapBlobRefV1(
        String key,
        String sha256,
        int sizeBytes,
        MapBlobRefV1ContentType contentType,
        Integer width,
        Integer height,
        long expiresAt
) implements ProtocolPayload {
    public MapBlobRefV1 {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(sha256, "sha256 must not be null");
        if (sizeBytes < 1) {
            throw new IllegalArgumentException("sizeBytes must be >= 1");
        }
        Objects.requireNonNull(contentType, "contentType must not be null");
        if (width != null) {
            if (width < 1) {
                throw new IllegalArgumentException("width must be >= 1");
            }
        }
        if (height != null) {
            if (height < 1) {
                throw new IllegalArgumentException("height must be >= 1");
            }
        }
        if (expiresAt < 0) {
            throw new IllegalArgumentException("expiresAt must be >= 0");
        }
    }

    @Override
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", key);
        payload.put("sha256", sha256);
        payload.put("sizeBytes", sizeBytes);
        if (contentType != null) {
            payload.put("contentType", contentType.toString());
        }
        if (width != null) {
            payload.put("width", width);
        }
        if (height != null) {
            payload.put("height", height);
        }
        payload.put("expiresAt", expiresAt);
        return payload;
    }
}
