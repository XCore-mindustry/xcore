package org.xcore.protocol.generated.shared;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.xcore.protocol.generated.runtime.ProtocolPayload;

public record MapDiscordUploadV1(
        DiscordIdentityRefV1 uploadedBy,
        long uploadedAt,
        DiscordIdentityRefV1 creator,
        String uploadedByHandle,
        String creatorHandle,
        Integer caseNumber,
        String threadId,
        Integer revision,
        DiscordIdentityRefV1 fixedBy,
        String fixedByHandle
) implements ProtocolPayload {
    public MapDiscordUploadV1 {
        Objects.requireNonNull(uploadedBy, "uploadedBy must not be null");
        if (uploadedAt < 1) {
            throw new IllegalArgumentException("uploadedAt must be >= 1");
        }
        if (creator != null) {
            Objects.requireNonNull(creator, "creator must not be null");
        }
        if (uploadedByHandle != null) {
            Objects.requireNonNull(uploadedByHandle, "uploadedByHandle must not be null");
            if (uploadedByHandle.length() < 1) {
                throw new IllegalArgumentException("uploadedByHandle must be at least 1 characters");
            }
        }
        if (creatorHandle != null) {
            Objects.requireNonNull(creatorHandle, "creatorHandle must not be null");
            if (creatorHandle.length() < 1) {
                throw new IllegalArgumentException("creatorHandle must be at least 1 characters");
            }
        }
        if (caseNumber != null) {
            if (caseNumber < 1) {
                throw new IllegalArgumentException("caseNumber must be >= 1");
            }
        }
        if (threadId != null) {
            Objects.requireNonNull(threadId, "threadId must not be null");
        }
        if (revision != null) {
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be >= 1");
            }
        }
        if (fixedBy != null) {
            Objects.requireNonNull(fixedBy, "fixedBy must not be null");
        }
        if (fixedByHandle != null) {
            Objects.requireNonNull(fixedByHandle, "fixedByHandle must not be null");
            if (fixedByHandle.length() < 1) {
                throw new IllegalArgumentException("fixedByHandle must be at least 1 characters");
            }
        }
    }

    @Override
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uploadedBy", uploadedBy.toPayload());
        payload.put("uploadedAt", uploadedAt);
        if (creator != null) {
            payload.put("creator", creator.toPayload());
        }
        if (uploadedByHandle != null) {
            payload.put("uploadedByHandle", uploadedByHandle);
        }
        if (creatorHandle != null) {
            payload.put("creatorHandle", creatorHandle);
        }
        if (caseNumber != null) {
            payload.put("caseNumber", caseNumber);
        }
        if (threadId != null) {
            payload.put("threadId", threadId);
        }
        if (revision != null) {
            payload.put("revision", revision);
        }
        if (fixedBy != null) {
            payload.put("fixedBy", fixedBy.toPayload());
        }
        if (fixedByHandle != null) {
            payload.put("fixedByHandle", fixedByHandle);
        }
        return payload;
    }
}
