package org.xcore.maprenderer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapRendererTest {
    private static MapRenderer renderer;
    private static Path clientJar;

    @BeforeAll
    static void load() throws IOException {
        Path data = Path.of(System.getProperty("renderData"));
        clientJar = Path.of(System.getProperty("clientJar"));
        renderer = new MapRenderer(RenderContent.load(data.resolve("content.json")), data.resolve("sprites.pack"),
                8 * 1024 * 1024, 1_000_000);
    }

    private static byte[] bundled(String name) throws IOException {
        try (var zip = new ZipFile(clientJar.toFile())) {
            var entry = zip.stream().filter(e -> e.getName().startsWith("maps/") && e.getName().endsWith("/" + name + ".msav"))
                    .findFirst().orElseThrow();
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    @Test
    void groundZeroIsDrawnAtTheLargestScaleThatFits() throws IOException {
        byte[] map = bundled("groundZero");
        var result = renderer.render(new ByteArrayInputStream(map), map.length, 4096, 8 * 1024 * 1024);

        assertEquals(256, result.tilesWide());
        assertEquals(16, result.scale());
        var image = ImageIO.read(new ByteArrayInputStream(result.png()));
        assertEquals(4096, image.getWidth());
        assertEquals(4096, image.getHeight());
        // Sprites, not flat colours: neighbouring pixels inside one floor tile differ.
        int distinct = 0;
        for (int x = 0; x < 15; x++) if (image.getRGB(2048 + x, 2048) != image.getRGB(2049 + x, 2048)) distinct++;
        assertTrue(distinct > 0);
    }

    @Test
    void aSmallerMaxSideDrawsAtASmallerScale() throws IOException {
        byte[] map = bundled("groundZero");
        var result = renderer.render(new ByteArrayInputStream(map), map.length, 1024, 8 * 1024 * 1024);

        assertEquals(4, result.scale());
        assertEquals(1024, result.width());
    }

    @Test
    void aByteLimitMakesTheRendererStepDown() throws IOException {
        byte[] map = bundled("groundZero");
        var full = renderer.render(new ByteArrayInputStream(map), map.length, 4096, 8 * 1024 * 1024);
        var limited = renderer.render(new ByteArrayInputStream(map), map.length, 4096, full.png().length - 1);

        assertNotEquals(full.scale(), limited.scale());
        assertTrue(limited.png().length < full.png().length);
    }

    @Test
    void anUnreadableMapIsRejected() {
        byte[] garbage = {1, 2, 3, 4};
        assertThrows(IOException.class,
                () -> renderer.render(new ByteArrayInputStream(garbage), garbage.length, 4096, 8 * 1024 * 1024));
    }

    @Test
    void pngRowsRoundTrip() throws IOException {
        int width = 37, height = 5;
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) pixels[i] = i * 0x010203 & 0xffffff;
        var out = new ByteArrayOutputStream();
        try (var writer = new PngStreamWriter(out, width, height, 6)) {
            for (int y = 0; y < height; y++) writer.row(pixels, y * width);
        }
        var image = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) assertEquals(pixels[y * width + x], image.getRGB(x, y) & 0xffffff);
        }
    }
}
