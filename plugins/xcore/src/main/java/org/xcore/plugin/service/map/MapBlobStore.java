package org.xcore.plugin.service.map;

import io.lettuce.core.SetArgs;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.xcore.plugin.service.network.RedisNetworkBackend;
import org.xcore.protocol.generated.shared.MapBlobRefV1;
import org.xcore.protocol.generated.shared.MapBlobRefV1ContentType;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Predicate;

/**
 * Bytes published beside a message instead of inside it: a Redis string {@code xcore:blob:{sha256}}
 * with a TTL, referenced by {@link MapBlobRefV1}. Content-addressed, so republishing the same
 * bytes only refreshes the expiry.
 */
@Singleton
public class MapBlobStore {
    public static final Duration TTL = Duration.ofMinutes(15);
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final String PREFIX = "xcore:blob:";

    /** Stores bytes under a key with a TTL; false when Redis is unavailable. */
    @FunctionalInterface
    public interface Writer {
        boolean set(String key, byte[] value, long ttlSeconds);
    }

    private final Writer writer;
    private final Clock clock;

    @Inject
    public MapBlobStore(RedisNetworkBackend backend) {
        this((key, value, ttl) -> backend.withBinaryCommands(
                commands -> "OK".equals(commands.set(key, value, SetArgs.Builder.ex(ttl))), false), Clock.systemUTC());
    }

    public MapBlobStore(Writer writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    /**
     * Publishes {@code bytes}; returns the reference, or {@code null} when they are too large or
     * Redis did not take them. {@code width}/{@code height} describe images and may be null.
     */
    public MapBlobRefV1 put(byte[] bytes, MapBlobRefV1ContentType type, Integer width, Integer height) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) return null;
        String sha256 = sha256(bytes);
        long expiresAt = clock.millis() + TTL.toMillis();
        if (!writer.set(PREFIX + sha256, bytes, TTL.toSeconds())) return null;
        return new MapBlobRefV1(PREFIX + sha256, sha256, bytes.length, type, width, height, expiresAt);
    }

    /** A reference still usable for at least {@code margin}. */
    public Predicate<MapBlobRefV1> fresh(Duration margin) {
        return ref -> ref.expiresAt() - clock.millis() > margin.toMillis();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
