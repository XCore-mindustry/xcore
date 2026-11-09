package org.xcore.maprenderer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
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
    void aMaxSideBelowTheSmallestScaleIsTooLarge() throws IOException {
        byte[] map = bundled("groundZero");
        // 256 tiles at the smallest scale (4) is 1024 px, over the requested 512.
        assertThrows(MapRenderer.TooLarge.class,
                () -> renderer.render(new ByteArrayInputStream(map), map.length, 512, 8 * 1024 * 1024));
    }

    @Test
    void aRenderEndsWithExactlyOneIend() throws IOException {
        byte[] map = bundled("groundZero");
        byte[] png = renderer.render(new ByteArrayInputStream(map), map.length, 1024, 8 * 1024 * 1024).png();

        // ImageIO stops at the first IEND, so check the chunk layout itself.
        int iends = 0;
        for (int at = 8; at + 8 <= png.length; ) {
            int length = java.nio.ByteBuffer.wrap(png, at, 4).getInt();
            if (new String(png, at + 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("IEND")) iends++;
            at += 12 + length;
            if (at == png.length) break;
            assertTrue(at < png.length, "chunk runs past the end");
        }
        assertEquals(1, iends);
        assertEquals("IEND", new String(png, png.length - 8, 4, java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    void withoutDarknessWallsAreBrighterAndShadowsStay() throws IOException {
        byte[] map = bundled("groundZero");
        var dark = ImageIO.read(new ByteArrayInputStream(
                renderer.render(new ByteArrayInputStream(map), map.length, 1024, 8 * 1024 * 1024, RenderOptions.GAME).png()));
        var light = ImageIO.read(new ByteArrayInputStream(
                renderer.render(new ByteArrayInputStream(map), map.length, 1024, 8 * 1024 * 1024,
                        new RenderOptions(false, true, false, false, false)).png()));

        long darkSum = 0, lightSum = 0, darker = 0;
        for (int y = 0; y < dark.getHeight(); y++) {
            for (int x = 0; x < dark.getWidth(); x++) {
                int a = luma(dark.getRGB(x, y)), b = luma(light.getRGB(x, y));
                darkSum += a;
                lightSum += b;
                if (b < a) darker++;
            }
        }
        // Darkness only ever darkens: nothing gets darker without it, and walls get lighter.
        assertEquals(0, darker);
        assertTrue(lightSum > darkSum);
    }

    @Test
    void eachLayerChangesThePictureAndTerrainOnlyDropsTheBuildings() throws IOException {
        byte[] map = bundled("groundZero");
        var game = image(map, RenderOptions.GAME);
        for (var options : java.util.List.of(
                new RenderOptions(true, false, false, false, false),
                new RenderOptions(true, true, true, false, false),
                new RenderOptions(true, true, false, true, false),
                new RenderOptions(true, true, false, false, true))) {
            var other = image(map, options);
            assertEquals(game.getWidth(), other.getWidth());
            assertTrue(differing(game, other) > 1000, options + " changes too little");
        }
        // The grid's 0,0 label sits on the bottom-left corner: white pixels there.
        var grid = image(map, new RenderOptions(true, true, false, true, false));
        int white = 0;
        for (int y = grid.getHeight() - 20; y < grid.getHeight(); y++)
            for (int x = 0; x < 20; x++) if ((grid.getRGB(x, y) & 0xffffff) == 0xffffff) white++;
        assertTrue(white > 5, "no 0,0 label");
    }

    private static BufferedImage image(byte[] map, RenderOptions options) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(
                renderer.render(new ByteArrayInputStream(map), map.length, 1024, 8 * 1024 * 1024, options).png()));
    }

    private static int differing(BufferedImage a, BufferedImage b) {
        int count = 0;
        for (int y = 0; y < a.getHeight(); y++)
            for (int x = 0; x < a.getWidth(); x++) if (a.getRGB(x, y) != b.getRGB(x, y)) count++;
        return count;
    }

    private static int luma(int rgb) {
        return ((rgb >> 16) & 0xff) * 3 + ((rgb >> 8) & 0xff) * 6 + (rgb & 0xff);
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
