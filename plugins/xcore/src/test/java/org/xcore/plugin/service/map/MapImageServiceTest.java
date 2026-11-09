package org.xcore.plugin.service.map;

import arc.files.Fi;
import arc.graphics.Pixmap;
import mindustry.maps.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xcore.plugin.concurrent.Async;
import org.xcore.plugin.concurrent.GameThread;
import org.xcore.plugin.concurrent.StorageExecutor;
import org.xcore.plugin.config.TomlSecretsConfig;
import org.xcore.plugin.service.MapService;
import org.xcore.plugin.service.map.inspect.DetachedMapReadLock;
import org.xcore.plugin.service.map.inspect.MsavFixture;
import org.xcore.protocol.generated.messages.maps.MapsFileResponseV1Status;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsFileRequestV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsPreviewRequestV1;
import org.xcore.protocol.generated.messages.maps.MapsPreviewResponseV1Status;
import org.xcore.protocol.generated.shared.MapBlobRefV1ContentType;
import org.xcore.testkit.fixtures.HeadlessWorld;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MapImageServiceTest {
    @TempDir Path directory;
    private final java.util.Map<String, byte[]> redis = new ConcurrentHashMap<>();
    private final AtomicInteger writes = new AtomicInteger();
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC);
    private final MapService maps = mock(MapService.class);
    private final TomlSecretsConfig secrets = new TomlSecretsConfig();
    private StorageExecutor storage;
    private HeadlessWorld engine;
    private MapImageService service;
    private Path file;

    @BeforeEach
    void start() throws Exception {
        GameThread.setGameThreadOverride(Thread.currentThread());
        engine = HeadlessWorld.builder().dimensions(8, 8).build();
        storage = new StorageExecutor(4);
        file = new MsavFixture().write(directory);
        when(maps.findMapByFileName(anyString())).thenReturn(null);
        when(maps.findMapByFileName("arena.msav")).thenReturn(new Map(new Fi(file.toFile()), 5, 5, new arc.struct.StringMap(), true));
        var blobs = new MapBlobStore((key, value, ttl) -> {
            writes.incrementAndGet();
            redis.put(key, value);
            return true;
        }, clock);
        // The game thread is the test thread: main-thread work runs inline.
        service = new MapImageService(maps, new Async(storage, Runnable::run), new DetachedMapReadLock(), blobs, secrets);
    }

    @AfterEach
    void stop() {
        storage.shutdown();
        engine.close();
        GameThread.setGameThreadOverride(null);
    }

    @Test
    void previewIsTheThumbnailScaledByAWholeNumberAndPublishedAsABlob() throws Exception {
        var response = service.preview(new MapsPreviewRequestV1("survival", "arena.msav", 512))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(response.status()).isEqualTo(MapsPreviewResponseV1Status.OK);
        assertThat(response.contentHash()).isEqualTo(MapBlobStore.sha256(Files.readAllBytes(file)));
        var image = response.image();
        assertThat(image.contentType()).isEqualTo(MapBlobRefV1ContentType.IMAGE_PNG);
        // 5 tiles: 512 / 5 = 102 pixels per tile.
        assertThat(image.width()).isEqualTo(510);
        assertThat(image.height()).isEqualTo(510);
        assertThat(image.expiresAt()).isEqualTo(clock.millis() + MapBlobStore.TTL.toMillis());
        byte[] png = redis.get(image.key());
        assertThat(image.key()).isEqualTo("xcore:blob:" + image.sha256());
        assertThat(MapBlobStore.sha256(png)).isEqualTo(image.sha256());
        assertThat(png.length).isEqualTo(image.sizeBytes());
        var decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(decoded.getWidth()).isEqualTo(510);
    }

    @Test
    void aSecondPreviewReusesThePublishedBlob() throws Exception {
        var request = new MapsPreviewRequestV1("survival", "arena.msav", null);
        var first = service.preview(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
        var second = service.preview(request).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(second.image()).isEqualTo(first.image());
        assertThat(writes).hasValue(1);
    }

    @Test
    void unknownMapsAreNotFound() throws Exception {
        var preview = service.preview(new MapsPreviewRequestV1("survival", "missing.msav", null))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        var publishedFile = service.file(new MapsFileRequestV1("survival", "missing.msav"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(preview.status()).isEqualTo(MapsPreviewResponseV1Status.NOT_FOUND);
        assertThat(publishedFile.status()).isEqualTo(MapsFileResponseV1Status.NOT_FOUND);
        assertThat(redis).isEmpty();
    }

    @Test
    void fileIsPublishedAsItsBytes() throws Exception {
        var response = service.file(new MapsFileRequestV1("survival", "arena.msav"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(response.status()).isEqualTo(MapsFileResponseV1Status.OK);
        byte[] bytes = Files.readAllBytes(file);
        assertThat(redis.get(response.file().key())).isEqualTo(bytes);
        assertThat(response.contentHash()).isEqualTo(response.file().sha256()).isEqualTo(MapBlobStore.sha256(bytes));
        assertThat(response.file().contentType()).isEqualTo(MapBlobRefV1ContentType.APPLICATION_OCTET_STREAM);
        assertThat(response.file().width()).isNull();
    }

    @Test
    void filesAboveTheUploadLimitAreTooLarge() throws Exception {
        secrets.maps.checks.maxFileBytes = 16;
        var response = service.file(new MapsFileRequestV1("survival", "arena.msav"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(response.status()).isEqualTo(MapsFileResponseV1Status.TOO_LARGE);
        assertThat(redis).isEmpty();
    }

    @Test
    void largeMapsAreSampledDownToTheSide() {
        var source = new Pixmap(1000, 10);
        try {
            source.setRaw(2, 0, 0xff0000ff);
            var fitted = MapImageService.fit(source, 512);
            try {
                assertThat(fitted.width).isEqualTo(500);
                assertThat(fitted.height).isEqualTo(5);
                assertThat(fitted.getRaw(1, 0)).isEqualTo(0xff0000ff);
            } finally { fitted.dispose(); }
            assertThat(MapImageService.fit(source, 1000)).isSameAs(source);
        } finally { source.dispose(); }
    }
}
