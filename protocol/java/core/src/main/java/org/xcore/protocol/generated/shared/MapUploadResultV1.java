package org.xcore.protocol.generated.shared;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.xcore.protocol.generated.runtime.ProtocolPayload;

public record MapUploadResultV1(
        String fileName,
        MapUploadResultV1Status status,
        String detail,
        String contentHash,
        String replacedContentHash,
        String trashId
) implements ProtocolPayload {
    public MapUploadResultV1 {
        Objects.requireNonNull(fileName, "fileName must not be null");
        if (fileName.length() < 1) {
            throw new IllegalArgumentException("fileName must be at least 1 characters");
        }
        Objects.requireNonNull(status, "status must not be null");
        if (detail != null) {
            Objects.requireNonNull(detail, "detail must not be null");
            if (detail.length() < 1) {
                throw new IllegalArgumentException("detail must be at least 1 characters");
            }
        }
        if (contentHash != null) {
            Objects.requireNonNull(contentHash, "contentHash must not be null");
        }
        if (replacedContentHash != null) {
            Objects.requireNonNull(replacedContentHash, "replacedContentHash must not be null");
        }
        if (trashId != null) {
            Objects.requireNonNull(trashId, "trashId must not be null");
        }
    }

    @Override
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileName", fileName);
        if (status != null) {
            payload.put("status", status.toString());
        }
        if (detail != null) {
            payload.put("detail", detail);
        }
        if (contentHash != null) {
            payload.put("contentHash", contentHash);
        }
        if (replacedContentHash != null) {
            payload.put("replacedContentHash", replacedContentHash);
        }
        if (trashId != null) {
            payload.put("trashId", trashId);
        }
        return payload;
    }
}
