package org.xcore.protocol.generated.messages.maps;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.xcore.protocol.generated.runtime.ProtocolPayload;
import org.xcore.protocol.generated.shared.MapBlobRefV1;
import org.xcore.protocol.generated.shared.MapEntryV1;
import org.xcore.protocol.generated.shared.MapFileSourceV1;
import org.xcore.protocol.generated.shared.MapInspectFindingV1;
import org.xcore.protocol.generated.shared.MapInspectInfoV1;
import org.xcore.protocol.generated.shared.MapUploadResultV1;

public final class MapsMessages {
    private MapsMessages() {
        throw new AssertionError("No org.xcore.protocol.generated.messages.maps.MapsMessages instances");
    }

    public record MapsFileRequestV1(
            String server,
            String fileName
    ) implements ProtocolPayload {
    public static final String MESSAGE_TYPE = "maps.file.request";
    public static final int MESSAGE_VERSION = 1;

        public MapsFileRequestV1 {
            Objects.requireNonNull(server, "server must not be null");
            if (server.length() < 1) {
                throw new IllegalArgumentException("server must be at least 1 characters");
            }
            Objects.requireNonNull(fileName, "fileName must not be null");
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageType", MESSAGE_TYPE);
            payload.put("messageVersion", MESSAGE_VERSION);
            payload.put("server", server);
            payload.put("fileName", fileName);
            return payload;
        }
    }

    public record MapsFileResponseV1(
            String server,
            String fileName,
            MapsFileResponseV1Status status,
            String contentHash,
            MapBlobRefV1 file
    ) implements ProtocolPayload {
    public static final String MESSAGE_TYPE = "maps.file.response";
    public static final int MESSAGE_VERSION = 1;

        public MapsFileResponseV1 {
            Objects.requireNonNull(server, "server must not be null");
            if (server.length() < 1) {
                throw new IllegalArgumentException("server must be at least 1 characters");
            }
            Objects.requireNonNull(fileName, "fileName must not be null");
            Objects.requireNonNull(status, "status must not be null");
            if (contentHash != null) {
                Objects.requireNonNull(contentHash, "contentHash must not be null");
            }
            if (file != null) {
                Objects.requireNonNull(file, "file must not be null");
            }
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageType", MESSAGE_TYPE);
            payload.put("messageVersion", MESSAGE_VERSION);
            payload.put("server", server);
            payload.put("fileName", fileName);
            if (status != null) {
                payload.put("status", status.toString());
            }
            if (contentHash != null) {
                payload.put("contentHash", contentHash);
            }
            if (file != null) {
                payload.put("file", file.toPayload());
            }
            return payload;
        }
    }

    public record MapsInspectRequestV1(
            String server,
            MapFileSourceV1 file,
            String profile
    ) implements ProtocolPayload {
    public static final String MESSAGE_TYPE = "maps.inspect.request";
    public static final int MESSAGE_VERSION = 1;

        public MapsInspectRequestV1 {
            Objects.requireNonNull(server, "server must not be null");
            if (server.length() < 1) {
                throw new IllegalArgumentException("server must be at least 1 characters");
            }
            Objects.requireNonNull(file, "file must not be null");
            if (profile != null) {
                Objects.requireNonNull(profile, "profile must not be null");
            }
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageType", MESSAGE_TYPE);
            payload.put("messageVersion", MESSAGE_VERSION);
            payload.put("server", server);
            payload.put("file", file.toPayload());
            if (profile != null) {
                payload.put("profile", profile);
            }
            return payload;
        }
    }

    public record MapsInspectResponseV1(
            String server,
            MapsInspectResponseV1Status status,
            String detail,
            MapInspectInfoV1 info,
            List<MapInspectFindingV1> findings
    ) implements ProtocolPayload {
    public static final String MESSAGE_TYPE = "maps.inspect.response";
    public static final int MESSAGE_VERSION = 1;

        public MapsInspectResponseV1 {
            Objects.requireNonNull(server, "server must not be null");
            if (server.length() < 1) {
                throw new IllegalArgumentException("server must be at least 1 characters");
            }
            Objects.requireNonNull(status, "status must not be null");
            if (detail != null) {
                Objects.requireNonNull(detail, "detail must not be null");
            }
            if (info != null) {
                Objects.requireNonNull(info, "info must not be null");
            }
            findings = Objects.requireNonNull(findings, "findings must not be null");
            findings = List.copyOf(findings);
            for (MapInspectFindingV1 item : findings) {
                Objects.requireNonNull(item, "findings[] must not be null");
            }
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageType", MESSAGE_TYPE);
            payload.put("messageVersion", MESSAGE_VERSION);
            payload.put("server", server);
            if (status != null) {
                payload.put("status", status.toString());
            }
            if (detail != null) {
                payload.put("detail", detail);
            }
            if (info != null) {
                payload.put("info", info.toPayload());
            }
            if (findings != null) {
                payload.put(
        "findings",
        findings.stream()
            .map(item -> item.toPayload())
            .toList()
    );
            }
            return payload;
        }
    }

    public record MapsListRequestV1(
            String server
    ) implements ProtocolPayload {
    public static final String MESSAGE_TYPE = "maps.list.request";
    public static final int MESSAGE_VERSION = 1;

        public MapsListRequestV1 {
            Objects.requireNonNull(server, "server must not be null");
            if (server.length() < 1) {
                throw new IllegalArgumentException("server must be at least 1 characters");
            }
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageType", MESSAGE_TYPE);
            payload.put("messageVersion", MESSAGE_VERSION);
            payload.put("server", server);
            return payload;
        }
    }

    public record MapsListResponseV1(
            String server,
            List<MapEntryV1> maps
    ) implements ProtocolPayload {
    public static final String MESSAGE_TYPE = "maps.list.response";
    public static final int MESSAGE_VERSION = 1;

        public MapsListResponseV1 {
            Objects.requireNonNull(server, "server must not be null");
            if (server.length() < 1) {
                throw new IllegalArgumentException("server must be at least 1 characters");
            }
            maps = Objects.requireNonNull(maps, "maps must not be null");
            maps = List.copyOf(maps);
            for (MapEntryV1 item : maps) {
                Objects.requireNonNull(item, "maps[] must not be null");
            }
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageType", MESSAGE_TYPE);
            payload.put("messageVersion", MESSAGE_VERSION);
            payload.put("server", server);
            if (maps != null) {
                payload.put(
        "maps",
        maps.stream()
            .map(item -> item.toPayload())
            .toList()
    );
            }
            return payload;
        }
    }

    public record MapsLoadCommandV1(
            String server,
            List<MapFileSourceV1> files
    ) implements ProtocolPayload {
    public static final String MESSAGE_TYPE = "maps.load.command";
    public static final int MESSAGE_VERSION = 1;

        public MapsLoadCommandV1 {
            Objects.requireNonNull(server, "server must not be null");
            if (server.length() < 1) {
                throw new IllegalArgumentException("server must be at least 1 characters");
            }
            files = Objects.requireNonNull(files, "files must not be null");
            files = List.copyOf(files);
            if (files.size() < 1) {
                throw new IllegalArgumentException("files must contain at least 1 item(s)");
            }
            for (MapFileSourceV1 item : files) {
                Objects.requireNonNull(item, "files[] must not be null");
            }
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageType", MESSAGE_TYPE);
            payload.put("messageVersion", MESSAGE_VERSION);
            payload.put("server", server);
            if (files != null) {
                payload.put(
        "files",
        files.stream()
            .map(item -> item.toPayload())
            .toList()
    );
            }
            return payload;
        }
    }

    public record MapsPreviewRequestV1(
            String server,
            String fileName,
            Integer maxSide
    ) implements ProtocolPayload {
    public static final String MESSAGE_TYPE = "maps.preview.request";
    public static final int MESSAGE_VERSION = 1;

        public MapsPreviewRequestV1 {
            Objects.requireNonNull(server, "server must not be null");
            if (server.length() < 1) {
                throw new IllegalArgumentException("server must be at least 1 characters");
            }
            Objects.requireNonNull(fileName, "fileName must not be null");
            if (maxSide != null) {
                if (maxSide < 64) {
                    throw new IllegalArgumentException("maxSide must be >= 64");
                }
            }
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageType", MESSAGE_TYPE);
            payload.put("messageVersion", MESSAGE_VERSION);
            payload.put("server", server);
            payload.put("fileName", fileName);
            if (maxSide != null) {
                payload.put("maxSide", maxSide);
            }
            return payload;
        }
    }

    public record MapsPreviewResponseV1(
            String server,
            String fileName,
            MapsPreviewResponseV1Status status,
            String contentHash,
            MapBlobRefV1 image
    ) implements ProtocolPayload {
    public static final String MESSAGE_TYPE = "maps.preview.response";
    public static final int MESSAGE_VERSION = 1;

        public MapsPreviewResponseV1 {
            Objects.requireNonNull(server, "server must not be null");
            if (server.length() < 1) {
                throw new IllegalArgumentException("server must be at least 1 characters");
            }
            Objects.requireNonNull(fileName, "fileName must not be null");
            Objects.requireNonNull(status, "status must not be null");
            if (contentHash != null) {
                Objects.requireNonNull(contentHash, "contentHash must not be null");
            }
            if (image != null) {
                Objects.requireNonNull(image, "image must not be null");
            }
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageType", MESSAGE_TYPE);
            payload.put("messageVersion", MESSAGE_VERSION);
            payload.put("server", server);
            payload.put("fileName", fileName);
            if (status != null) {
                payload.put("status", status.toString());
            }
            if (contentHash != null) {
                payload.put("contentHash", contentHash);
            }
            if (image != null) {
                payload.put("image", image.toPayload());
            }
            return payload;
        }
    }

    public record MapsRemoveRequestV1(
            String server,
            String fileName
    ) implements ProtocolPayload {
    public static final String MESSAGE_TYPE = "maps.remove.request";
    public static final int MESSAGE_VERSION = 1;

        public MapsRemoveRequestV1 {
            Objects.requireNonNull(server, "server must not be null");
            if (server.length() < 1) {
                throw new IllegalArgumentException("server must be at least 1 characters");
            }
            Objects.requireNonNull(fileName, "fileName must not be null");
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageType", MESSAGE_TYPE);
            payload.put("messageVersion", MESSAGE_VERSION);
            payload.put("server", server);
            payload.put("fileName", fileName);
            return payload;
        }
    }

    public record MapsRemoveResponseV1(
            String server,
            String result
    ) implements ProtocolPayload {
    public static final String MESSAGE_TYPE = "maps.remove.response";
    public static final int MESSAGE_VERSION = 1;

        public MapsRemoveResponseV1 {
            Objects.requireNonNull(server, "server must not be null");
            if (server.length() < 1) {
                throw new IllegalArgumentException("server must be at least 1 characters");
            }
            Objects.requireNonNull(result, "result must not be null");
            if (result.length() < 1) {
                throw new IllegalArgumentException("result must be at least 1 characters");
            }
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageType", MESSAGE_TYPE);
            payload.put("messageVersion", MESSAGE_VERSION);
            payload.put("server", server);
            payload.put("result", result);
            return payload;
        }
    }

    public record MapsRenderRequestV1(
            String contentHash,
            MapBlobRefV1 file,
            Integer maxSide,
            Integer maxBytes,
            Boolean darkness,
            Boolean buildings,
            Boolean ores,
            Boolean grid,
            Boolean spawns
    ) implements ProtocolPayload {
    public static final String MESSAGE_TYPE = "maps.render.request";
    public static final int MESSAGE_VERSION = 1;

        public MapsRenderRequestV1 {
            Objects.requireNonNull(contentHash, "contentHash must not be null");
            Objects.requireNonNull(file, "file must not be null");
            if (maxSide != null) {
                if (maxSide < 256) {
                    throw new IllegalArgumentException("maxSide must be >= 256");
                }
            }
            if (maxBytes != null) {
                if (maxBytes < 65536) {
                    throw new IllegalArgumentException("maxBytes must be >= 65536");
                }
            }
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageType", MESSAGE_TYPE);
            payload.put("messageVersion", MESSAGE_VERSION);
            payload.put("contentHash", contentHash);
            payload.put("file", file.toPayload());
            if (maxSide != null) {
                payload.put("maxSide", maxSide);
            }
            if (maxBytes != null) {
                payload.put("maxBytes", maxBytes);
            }
            if (darkness != null) {
                payload.put("darkness", darkness);
            }
            if (buildings != null) {
                payload.put("buildings", buildings);
            }
            if (ores != null) {
                payload.put("ores", ores);
            }
            if (grid != null) {
                payload.put("grid", grid);
            }
            if (spawns != null) {
                payload.put("spawns", spawns);
            }
            return payload;
        }
    }

    public record MapsRenderResponseV1(
            String contentHash,
            MapsRenderResponseV1Status status,
            MapBlobRefV1 image,
            Integer scale,
            Integer renderMs,
            String reason
    ) implements ProtocolPayload {
    public static final String MESSAGE_TYPE = "maps.render.response";
    public static final int MESSAGE_VERSION = 1;

        public MapsRenderResponseV1 {
            Objects.requireNonNull(contentHash, "contentHash must not be null");
            Objects.requireNonNull(status, "status must not be null");
            if (image != null) {
                Objects.requireNonNull(image, "image must not be null");
            }
            if (scale != null) {
                if (scale < 1) {
                    throw new IllegalArgumentException("scale must be >= 1");
                }
            }
            if (renderMs != null) {
                if (renderMs < 0) {
                    throw new IllegalArgumentException("renderMs must be >= 0");
                }
            }
            if (reason != null) {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageType", MESSAGE_TYPE);
            payload.put("messageVersion", MESSAGE_VERSION);
            payload.put("contentHash", contentHash);
            if (status != null) {
                payload.put("status", status.toString());
            }
            if (image != null) {
                payload.put("image", image.toPayload());
            }
            if (scale != null) {
                payload.put("scale", scale);
            }
            if (renderMs != null) {
                payload.put("renderMs", renderMs);
            }
            if (reason != null) {
                payload.put("reason", reason);
            }
            return payload;
        }
    }

    public record MapsUploadRequestV1(
            String server,
            List<MapFileSourceV1> files
    ) implements ProtocolPayload {
    public static final String MESSAGE_TYPE = "maps.upload.request";
    public static final int MESSAGE_VERSION = 1;

        public MapsUploadRequestV1 {
            Objects.requireNonNull(server, "server must not be null");
            if (server.length() < 1) {
                throw new IllegalArgumentException("server must be at least 1 characters");
            }
            files = Objects.requireNonNull(files, "files must not be null");
            files = List.copyOf(files);
            if (files.size() < 1) {
                throw new IllegalArgumentException("files must contain at least 1 item(s)");
            }
            for (MapFileSourceV1 item : files) {
                Objects.requireNonNull(item, "files[] must not be null");
            }
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageType", MESSAGE_TYPE);
            payload.put("messageVersion", MESSAGE_VERSION);
            payload.put("server", server);
            if (files != null) {
                payload.put(
        "files",
        files.stream()
            .map(item -> item.toPayload())
            .toList()
    );
            }
            return payload;
        }
    }

    public record MapsUploadResponseV1(
            String server,
            List<MapUploadResultV1> results
    ) implements ProtocolPayload {
    public static final String MESSAGE_TYPE = "maps.upload.response";
    public static final int MESSAGE_VERSION = 1;

        public MapsUploadResponseV1 {
            Objects.requireNonNull(server, "server must not be null");
            if (server.length() < 1) {
                throw new IllegalArgumentException("server must be at least 1 characters");
            }
            results = Objects.requireNonNull(results, "results must not be null");
            results = List.copyOf(results);
            if (results.size() < 1) {
                throw new IllegalArgumentException("results must contain at least 1 item(s)");
            }
            for (MapUploadResultV1 item : results) {
                Objects.requireNonNull(item, "results[] must not be null");
            }
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageType", MESSAGE_TYPE);
            payload.put("messageVersion", MESSAGE_VERSION);
            payload.put("server", server);
            if (results != null) {
                payload.put(
        "results",
        results.stream()
            .map(item -> item.toPayload())
            .toList()
    );
            }
            return payload;
        }
    }
}
