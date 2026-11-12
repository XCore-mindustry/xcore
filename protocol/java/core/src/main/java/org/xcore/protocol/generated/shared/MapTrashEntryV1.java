package org.xcore.protocol.generated.shared;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.xcore.protocol.generated.runtime.ProtocolPayload;

public record MapTrashEntryV1(
        String trashId,
        String fileName,
        String contentHash,
        long sizeBytes,
        long trashedAt,
        MapTrashEntryV1Reason reason,
        String name,
        String author
) implements ProtocolPayload {
    public MapTrashEntryV1 {
        Objects.requireNonNull(trashId, "trashId must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(contentHash, "contentHash must not be null");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0");
        }
        if (trashedAt < 1) {
            throw new IllegalArgumentException("trashedAt must be >= 1");
        }
        Objects.requireNonNull(reason, "reason must not be null");
        if (name != null) {
            Objects.requireNonNull(name, "name must not be null");
            if (name.length() < 1) {
                throw new IllegalArgumentException("name must be at least 1 characters");
            }
        }
        if (author != null) {
            Objects.requireNonNull(author, "author must not be null");
            if (author.length() < 1) {
                throw new IllegalArgumentException("author must be at least 1 characters");
            }
        }
    }

    @Override
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trashId", trashId);
        payload.put("fileName", fileName);
        payload.put("contentHash", contentHash);
        payload.put("sizeBytes", sizeBytes);
        payload.put("trashedAt", trashedAt);
        if (reason != null) {
            payload.put("reason", reason.toString());
        }
        if (name != null) {
            payload.put("name", name);
        }
        if (author != null) {
            payload.put("author", author);
        }
        return payload;
    }
}
