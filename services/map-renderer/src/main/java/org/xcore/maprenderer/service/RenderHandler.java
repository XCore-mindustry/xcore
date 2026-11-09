package org.xcore.maprenderer.service;

import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import org.xcore.maprenderer.MapRenderer;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsRenderRequestV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsRenderResponseV1;
import org.xcore.protocol.generated.messages.maps.MapsRenderResponseV1Status;
import org.xcore.protocol.generated.shared.MapBlobRefV1;
import org.xcore.protocol.generated.shared.MapBlobRefV1ContentType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;

/**
 * One {@code maps.render} request: read the map blob, render it (or take the render from the disk
 * cache), publish the PNG as a blob and describe it.
 */
final class RenderHandler {
    static final int DEFAULT_MAX_SIDE = 4096;
    static final int DEFAULT_MAX_BYTES = 8 * 1024 * 1024;
    static final Duration BLOB_TTL = Duration.ofMinutes(15);
    static final int MIN_MAX_SIDE = 256;
    static final int MIN_MAX_BYTES = 64 * 1024;
    static final int MAX_MAX_BYTES = 16 * 1024 * 1024;
    private static final String BLOB_PREFIX = "xcore:blob:";
    private static final java.util.regex.Pattern SHA256 = java.util.regex.Pattern.compile("[0-9a-f]{64}");

    /** The two blob operations the handler needs. */
    interface Blobs {
        byte[] get(String key) throws IOException;

        void set(String key, byte[] value, long ttlSeconds) throws IOException;
    }

    private final MapRenderer renderer;
    private final RenderCache cache;
    private final Blobs blobs;
    private final String version;
    private final Metrics metrics;
    private final Clock clock;

    RenderHandler(MapRenderer renderer, RenderCache cache, Blobs blobs, String version, Metrics metrics, Clock clock) {
        this.renderer = renderer;
        this.cache = cache;
        this.blobs = blobs;
        this.version = version;
        this.metrics = metrics;
        this.clock = clock;
    }

    static MapsRenderRequestV1 parse(String payloadJson) {
        JsonValue value = new JsonReader().parse(payloadJson);
        if (!MapsRenderRequestV1.MESSAGE_TYPE.equals(value.getString("messageType", ""))
                || value.getInt("messageVersion", 0) != MapsRenderRequestV1.MESSAGE_VERSION) {
            throw new IllegalArgumentException("Not a maps.render.request v1");
        }
        JsonValue file = value.get("file");
        if (file == null) throw new IllegalArgumentException("file is required");
        var blob = new MapBlobRefV1(file.getString("key"), file.getString("sha256"), file.getInt("sizeBytes"),
                MapBlobRefV1ContentType.fromValue(file.getString("contentType")),
                file.has("width") ? file.getInt("width") : null, file.has("height") ? file.getInt("height") : null,
                file.getLong("expiresAt"));
        return new MapsRenderRequestV1(value.getString("contentHash"), blob,
                value.has("maxSide") ? value.getInt("maxSide") : null,
                value.has("maxBytes") ? value.getInt("maxBytes") : null,
                value.has("darkness") ? value.getBoolean("darkness") : null);
    }

    MapsRenderResponseV1 handle(MapsRenderRequestV1 request) {
        long started = System.nanoTime();
        // The generated model checks only the lower bounds; the schema's upper bounds and the hash
        // pattern are enforced here, since the hash also names files in the disk cache.
        int maxSide = request.maxSide() == null ? DEFAULT_MAX_SIDE
                : Math.clamp(request.maxSide(), MIN_MAX_SIDE, DEFAULT_MAX_SIDE);
        int maxBytes = request.maxBytes() == null ? DEFAULT_MAX_BYTES
                : Math.clamp(request.maxBytes(), MIN_MAX_BYTES, MAX_MAX_BYTES);
        String hash = request.contentHash();
        if (hash == null || !SHA256.matcher(hash).matches()) {
            return failed(hash, MapsRenderResponseV1Status.FAILED, "contentHash is not a SHA-256 hex digest");
        }
        boolean darkness = request.darkness() == null || request.darkness();
        String key = RenderCache.key(hash, maxSide, maxBytes, darkness, version);
        try {
            var cached = cache.get(key);
            if (cached != null) {
                metrics.cacheHit();
                return ok(hash, cached, publish(cached));
            }
            byte[] map;
            try {
                map = blobs.get(request.file().key());
            } catch (IOException redisDown) {
                return failed(hash, MapsRenderResponseV1Status.DOWNLOAD_FAILED, "Redis: " + redisDown.getMessage());
            }
            if (map == null || map.length != request.file().sizeBytes() || !sha256(map).equals(request.file().sha256())) {
                return failed(hash, MapsRenderResponseV1Status.DOWNLOAD_FAILED, "The map blob is missing or does not match");
            }
            if (!request.file().sha256().equals(hash)) {
                return failed(hash, MapsRenderResponseV1Status.DOWNLOAD_FAILED, "The map blob is not the map named by contentHash");
            }
            MapRenderer.Result result;
            try {
                result = renderer.render(new ByteArrayInputStream(map), map.length, maxSide, maxBytes, darkness);
            } catch (MapRenderer.TooLarge tooLarge) {
                return failed(hash, MapsRenderResponseV1Status.TOO_LARGE, tooLarge.getMessage());
            } catch (IOException invalid) {
                return failed(hash, MapsRenderResponseV1Status.INVALID_MAP, invalid.getMessage());
            }
            int renderMs = (int) ((System.nanoTime() - started) / 1_000_000);
            var entry = new RenderCache.Entry(result.png(), result.width(), result.height(), result.scale(), renderMs);
            cache.put(key, entry);
            metrics.rendered(System.nanoTime() - started);
            return ok(hash, entry, publish(entry));
        } catch (IOException redisDown) {
            return failed(hash, MapsRenderResponseV1Status.FAILED, "Redis: " + redisDown.getMessage());
        } catch (RuntimeException | OutOfMemoryError error) {
            return failed(hash, MapsRenderResponseV1Status.FAILED, error.toString());
        }
    }

    private MapBlobRefV1 publish(RenderCache.Entry entry) throws IOException {
        String sha = sha256(entry.png());
        blobs.set(BLOB_PREFIX + sha, entry.png(), BLOB_TTL.toSeconds());
        return new MapBlobRefV1(BLOB_PREFIX + sha, sha, entry.png().length, MapBlobRefV1ContentType.IMAGE_PNG,
                entry.width(), entry.height(), clock.millis() + BLOB_TTL.toMillis());
    }

    private MapsRenderResponseV1 ok(String hash, RenderCache.Entry entry, MapBlobRefV1 image) {
        metrics.answered(MapsRenderResponseV1Status.OK);
        return new MapsRenderResponseV1(hash, MapsRenderResponseV1Status.OK, image, entry.scale(), entry.renderMs(), null);
    }

    private MapsRenderResponseV1 failed(String hash, MapsRenderResponseV1Status status, String reason) {
        metrics.answered(status);
        String text = reason == null ? null : reason.length() > 300 ? reason.substring(0, 300) : reason;
        return new MapsRenderResponseV1(hash, status, null, null, null, text);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
