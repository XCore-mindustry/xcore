package org.xcore.plugin.service.map;

import arc.files.Fi;
import arc.graphics.Pixmap;
import arc.graphics.PixmapIO;
import arc.util.Log;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import mindustry.maps.Map;
import org.xcore.plugin.concurrent.Async;
import org.xcore.plugin.config.TomlSecretsConfig;
import org.xcore.plugin.service.MapService;
import org.xcore.plugin.service.map.inspect.DetachedMapReadLock;
import org.xcore.protocol.generated.messages.maps.MapsFileResponseV1Status;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsFileRequestV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsFileResponseV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsPreviewRequestV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsPreviewResponseV1;
import org.xcore.protocol.generated.messages.maps.MapsPreviewResponseV1Status;
import org.xcore.protocol.generated.shared.MapBlobRefV1;
import org.xcore.protocol.generated.shared.MapBlobRefV1ContentType;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Pictures and files of installed maps for other services, published as blobs
 * ({@link MapBlobStore}).
 *
 * <ul>
 *   <li>Preview: the vote-menu thumbnail (one pixel per tile, {@link DetachedMapPreview}),
 *       scaled by a whole number so the long side fits {@code maxSide}.</li>
 *   <li>File: the {@code .msav} bytes, for the map renderer.</li>
 * </ul>
 *
 * The map is looked up and preview colours are captured on the game thread; reading, drawing,
 * encoding and publishing run on the storage executor.
 */
@Singleton
public class MapImageService {
    public static final int DEFAULT_PREVIEW_SIDE = 512;
    static final int MIN_PREVIEW_SIDE = 64;
    static final int MAX_PREVIEW_SIDE = 4096;
    private static final int CACHE_ENTRIES = 32;
    private static final Duration REUSE_MARGIN = Duration.ofMinutes(2);

    private final MapService maps;
    private final Async async;
    private final DetachedMapReadLock readLock;
    private final MapBlobStore blobs;
    private final TomlSecretsConfig secrets;
    /** "sha256:side" -> published preview; a blob is republished once it is close to expiring. */
    private final java.util.Map<String, MapBlobRefV1> previews = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, MapBlobRefV1> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    @Inject
    public MapImageService(MapService maps, Async async, DetachedMapReadLock readLock, MapBlobStore blobs,
                           TomlSecretsConfig secrets) {
        this.maps = maps;
        this.async = async;
        this.readLock = readLock;
        this.blobs = blobs;
        this.secrets = secrets;
    }

    /** The map to draw, or {@code cached} when the catalog's hash already names a fresh preview. */
    private record Found(Fi file, DetachedMapPreview colours, String hash, MapBlobRefV1 cached) {}

    public CompletionStage<MapsPreviewResponseV1> preview(MapsPreviewRequestV1 request) {
        // The generated model checks only the minimum; the schema's maximum bounds the Pixmap.
        int side = request.maxSide() == null ? DEFAULT_PREVIEW_SIDE
                : Math.clamp(request.maxSide(), MIN_PREVIEW_SIDE, MAX_PREVIEW_SIDE);
        return onMain(() -> {
            Map map = maps.findMapByFileName(request.fileName());
            if (map == null || map.file == null) return null;
            // A preview drawn for the bytes the catalog hashed is answered without capturing
            // colours on the game thread or reading and hashing the file again.
            String hash = maps.contentHashFor(map);
            MapBlobRefV1 cached = hash == null ? null : cachedPreview(hash, side);
            return cached != null ? new Found(map.file, null, hash, cached)
                    : new Found(map.file, DetachedMapPreview.capture(), null, null);
        }).thenCompose(found -> found == null
                ? CompletableFuture.completedFuture(preview(request, MapsPreviewResponseV1Status.NOT_FOUND, null, null))
                : found.cached() != null
                ? CompletableFuture.completedFuture(preview(request, MapsPreviewResponseV1Status.OK, found.hash(), found.cached()))
                : async.supply(() -> drawPreview(request, found, side)).future());
    }

    private MapBlobRefV1 cachedPreview(String hash, int side) {
        MapBlobRefV1 cached;
        synchronized (previews) {
            cached = previews.get(hash + ":" + side);
        }
        return cached != null && blobs.fresh(REUSE_MARGIN).test(cached) ? cached : null;
    }

    public CompletionStage<MapsFileResponseV1> file(MapsFileRequestV1 request) {
        return onMain(() -> {
            Map map = maps.findMapByFileName(request.fileName());
            return map == null ? null : map.file;
        }).thenCompose(file -> file == null
                ? CompletableFuture.completedFuture(file(request, MapsFileResponseV1Status.NOT_FOUND, null, null))
                : async.supply(() -> publishFile(request, file)).future());
    }

    private MapsPreviewResponseV1 drawPreview(MapsPreviewRequestV1 request, Found found, int side) {
        try {
            byte[] bytes = found.file().readBytes();
            String hash = MapBlobStore.sha256(bytes);
            String key = hash + ":" + side;
            MapBlobRefV1 cached = cachedPreview(hash, side);
            if (cached != null) return preview(request, MapsPreviewResponseV1Status.OK, hash, cached);
            Pixmap tiles = null, scaled = null;
            byte[] png;
            int width, height;
            readLock.lock();
            try {
                tiles = found.colours().read(found.file());
                scaled = fit(tiles, side);
                width = scaled.width;
                height = scaled.height;
                png = PixmapIO.writePngBytes(scaled);
            } finally {
                if (scaled != null && scaled != tiles) scaled.dispose();
                if (tiles != null) tiles.dispose();
                readLock.unlock();
            }
            var image = blobs.put(png, MapBlobRefV1ContentType.IMAGE_PNG, width, height);
            if (image == null) return preview(request, MapsPreviewResponseV1Status.FAILED, hash, null);
            synchronized (previews) {
                previews.put(key, image);
            }
            return preview(request, MapsPreviewResponseV1Status.OK, hash, image);
        } catch (Throwable error) {
            Log.warn("[Maps] Preview of @ failed: @", request.fileName(), error.toString());
            return preview(request, MapsPreviewResponseV1Status.FAILED, null, null);
        }
    }

    private MapsFileResponseV1 publishFile(MapsFileRequestV1 request, Fi file) {
        try {
            int limit = Math.min(secrets.maps.checks.maxFileBytes, MapBlobStore.MAX_BYTES);
            if (file.length() > limit) return file(request, MapsFileResponseV1Status.TOO_LARGE, null, null);
            byte[] bytes = file.readBytes();
            if (bytes.length > limit) return file(request, MapsFileResponseV1Status.TOO_LARGE, null, null);
            var blob = blobs.put(bytes, MapBlobRefV1ContentType.APPLICATION_OCTET_STREAM, null, null);
            return blob == null
                    ? file(request, MapsFileResponseV1Status.FAILED, null, null)
                    : file(request, MapsFileResponseV1Status.OK, blob.sha256(), blob);
        } catch (Throwable error) {
            Log.warn("[Maps] Publishing @ failed: @", request.fileName(), error.toString());
            return file(request, MapsFileResponseV1Status.FAILED, null, null);
        }
    }

    /**
     * Scales a one-pixel-per-tile image so its long side is at most {@code side}: up by a whole
     * number, or down by sampling every n-th pixel. Returns {@code source} when it already fits.
     */
    static Pixmap fit(Pixmap source, int side) {
        int longest = Math.max(source.width, source.height);
        if (longest <= side && longest * 2 > side) return source;
        if (longest <= side) {
            int factor = side / longest;
            var result = new Pixmap(source.width * factor, source.height * factor);
            for (int y = 0; y < result.height; y++) {
                for (int x = 0; x < result.width; x++) result.setRaw(x, y, source.getRaw(x / factor, y / factor));
            }
            return result;
        }
        int step = (longest + side - 1) / side;
        var result = new Pixmap(Math.max(1, source.width / step), Math.max(1, source.height / step));
        for (int y = 0; y < result.height; y++) {
            for (int x = 0; x < result.width; x++) result.setRaw(x, y, source.getRaw(x * step, y * step));
        }
        return result;
    }

    private <T> CompletableFuture<T> onMain(Supplier<T> task) {
        var result = new CompletableFuture<T>();
        async.main(() -> {
            try {
                result.complete(task.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    private static MapsPreviewResponseV1 preview(MapsPreviewRequestV1 request, MapsPreviewResponseV1Status status,
                                                 String hash, MapBlobRefV1 image) {
        return new MapsPreviewResponseV1(request.server(), request.fileName(), status, hash, image);
    }

    private static MapsFileResponseV1 file(MapsFileRequestV1 request, MapsFileResponseV1Status status, String hash,
                                           MapBlobRefV1 blob) {
        return new MapsFileResponseV1(request.server(), request.fileName(), status, hash, blob);
    }
}
