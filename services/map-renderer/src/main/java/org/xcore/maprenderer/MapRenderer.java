package org.xcore.maprenderer;

import arc.math.Rand;
import org.xcore.plugin.service.map.inspect.DetachedMsavReader;
import org.xcore.plugin.service.map.inspect.InspectionSettings;
import org.xcore.plugin.service.map.inspect.MapSnapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws a map with block sprites. The image is built in bands of {@link #BAND_TILES} tile rows,
 * top first, and each band goes straight to the PNG encoder: memory per render is one band, the
 * decoded map and the encoded PNG, never the whole canvas.
 */
public final class MapRenderer {
    public static final int BAND_TILES = 16;
    static final int[] SCALES = {32, 16, 8, 4};

    private final RenderContent content;
    private final Path pack;
    private final Map<Integer, SpritePack> packs = new ConcurrentHashMap<>();
    private final InspectionSettings settings;

    public MapRenderer(RenderContent content, Path pack, int maxFileBytes, int maxTiles) {
        this.content = content;
        this.pack = pack;
        this.settings = new InspectionSettings(maxFileBytes, maxTiles, Map.of());
    }

    public record Result(byte[] png, int width, int height, int scale, int tilesWide, int tilesHigh,
                         long decodeNanos, long drawNanos) {}

    public static final class TooLarge extends IOException {
        TooLarge(String message) { super(message); }
    }

    /**
     * Renders at the largest pack scale whose long side fits {@code maxSide}; if the PNG is over
     * {@code maxBytes}, renders again at the next smaller scale.
     */
    public Result render(InputStream msav, long size, int maxSide, int maxBytes) throws IOException {
        long start = System.nanoTime();
        MapSnapshot map = new DetachedMsavReader().read(msav, size, "", content.registry(), settings);
        if (!map.tilesAvailable()) throw new TooLarge("Map exceeds the tile limit");
        long decoded = System.nanoTime();
        int width = map.metadata().width(), height = map.metadata().height();
        int longest = Math.max(width, height);
        for (int scale : SCALES) {
            if ((long) longest * scale > maxSide && scale != SCALES[SCALES.length - 1]) continue;
            try {
                byte[] png = draw(map, sprites(scale), maxBytes);
                return new Result(png, width * scale, height * scale, scale, width, height,
                        decoded - start, System.nanoTime() - decoded);
            } catch (Overflow overflow) {
                // Try the next smaller scale.
            }
        }
        throw new TooLarge("Map does not fit in " + maxBytes + " bytes at any scale");
    }

    private SpritePack sprites(int scale) throws IOException {
        var existing = packs.get(scale);
        if (existing != null) return existing;
        synchronized (packs) {
            existing = packs.get(scale);
            if (existing == null) packs.put(scale, existing = SpritePack.load(pack, scale));
            return existing;
        }
    }

    private byte[] draw(MapSnapshot map, SpritePack sprites, int maxBytes) throws IOException {
        int s = sprites.scale;
        int width = map.metadata().width(), height = map.metadata().height();
        int pixelsWide = width * s;
        int[] band = new int[pixelsWide * BAND_TILES * s];
        var rand = new Rand();

        // Buildings by the row of their centre, so a band only looks at the rows near it.
        List<MapSnapshot.Placed> placed = map.buildings();
        int[] rowStart = new int[height + 1];
        for (var building : placed) rowStart[building.y() + 1]++;
        for (int y = 0; y < height; y++) rowStart[y + 1] += rowStart[y];
        int[] byRow = new int[placed.size()];
        int[] fill = Arrays.copyOf(rowStart, height);
        int reach = 1;
        for (int i = 0; i < placed.size(); i++) {
            var building = placed.get(i);
            byRow[fill[building.y()]++] = i;
            var sprite = icon(sprites, building.block());
            if (sprite != null) reach = Math.max(reach, (Math.max(sprite.width(), sprite.height()) / s + 1) / 2 + 1);
        }

        var png = new Limited(maxBytes);
        try (var writer = new PngStreamWriter(png, pixelsWide, height * s, 6)) {
            drawBands(map, sprites, band, rand, placed, rowStart, byRow, reach, writer);
        } catch (UncheckedOverflow overflow) {
            throw new Overflow();
        }
        return png.toByteArray();
    }

    private void drawBands(MapSnapshot map, SpritePack sprites, int[] band, Rand rand, List<MapSnapshot.Placed> placed,
                           int[] rowStart, int[] byRow, int reach, PngStreamWriter writer) throws IOException {
        int s = sprites.scale;
        int width = map.metadata().width(), height = map.metadata().height();
        int pixelsWide = width * s;
        for (int top = height - 1; top >= 0; top -= BAND_TILES) {
            int bottom = Math.max(0, top - BAND_TILES + 1);
            int rows = (top - bottom + 1) * s;
            Arrays.fill(band, 0, pixelsWide * rows, 0);
            var target = new Band(band, pixelsWide, rows);
            for (int y = top; y >= bottom; y--) {
                for (int x = 0; x < width; x++) drawTile(map, sprites, target, rand, x, y, top);
            }
            for (int y = Math.min(height - 1, top + reach); y >= Math.max(0, bottom - reach); y--) {
                for (int i = rowStart[y]; i < rowStart[y + 1]; i++) {
                    drawBuilding(sprites, target, placed.get(byRow[i]), top);
                }
            }
            for (int row = 0; row < rows; row++) writer.row(band, row * pixelsWide);
        }
    }

    private void drawTile(MapSnapshot map, SpritePack sprites, Band band, Rand rand, int x, int y, int top) {
        int index = x + y * map.metadata().width();
        int s = sprites.scale;
        int left = x * s, upper = (top - y) * s;
        int floor = map.floor(index), overlay = map.overlay(index), block = map.block(index);
        int seed = x << 16 | y & 0xffff;

        if (floor >= 0 && content.layer[floor] == RenderContent.FLOOR) {
            if (!drawVariant(sprites, band, rand, seed, floor, left, upper)) band.fill(left, upper, s, content.color[floor]);
        }
        boolean wallOre = overlay > 0 && overlay < content.layer.length && content.wallOre[overlay];
        if (overlay > 0 && overlay < content.layer.length && content.layer[overlay] == RenderContent.OVERLAY && !wallOre) {
            drawVariant(sprites, band, rand, seed, overlay, left, upper);
        }
        if (block > 0 && block < content.layer.length && content.layer[block] == RenderContent.STATIC) {
            if (!drawVariant(sprites, band, rand, seed, block, left, upper)) band.fill(left, upper, s, content.color[block]);
            if (wallOre) drawVariant(sprites, band, rand, seed, overlay, left, upper);
        }
    }

    /** Floor, ore and wall variants are picked as the engine does: Mathf.randomSeed(pos, 0, variants - 1). */
    private boolean drawVariant(SpritePack sprites, Band band, Rand rand, int seed, int block, int left, int upper) {
        String[] names = content.sprites[block];
        if (names.length == 0) return false;
        int max = names.length - 1;
        rand.setSeed(seed);
        if (max != 0 && (max & max - 1) == 0) rand.nextInt();
        var sprite = sprites.get(names[rand.nextInt(max + 1)]);
        if (sprite == null) return false;
        int s = sprites.scale;
        band.blit(sprite, left + (s - sprite.width()) / 2, upper + (s - sprite.height()) / 2, 0, -1);
        return true;
    }

    private void drawBuilding(SpritePack sprites, Band band, MapSnapshot.Placed building, int top) {
        int block = building.block();
        if (block < 0 || block >= content.layer.length) return;
        var sprite = icon(sprites, block);
        if (sprite == null) return;
        int s = sprites.scale;
        // Even sizes sit half a tile right of and above their centre tile, as World places them.
        int shift = content.size[block] % 2 == 0 ? s / 2 : 0;
        int centreX = building.x() * s + s / 2 + shift;
        int centreY = (top - building.y()) * s + s / 2 - shift;
        int rotation = content.rotate[block] ? building.rotation() & 3 : 0;
        boolean swap = (rotation & 1) == 1;
        int w = swap ? sprite.height() : sprite.width(), h = swap ? sprite.width() : sprite.height();
        band.blit(sprite, centreX - w / 2, centreY - h / 2, rotation, -1);
        var team = sprites.get(content.teamSprite[block]);
        if (team != null) {
            int tint = building.team() < content.teamColor.length ? content.teamColor[building.team()] : 0xffffff;
            band.blit(team, centreX - w / 2, centreY - h / 2, rotation, tint);
        }
    }

    private SpritePack.Sprite icon(SpritePack sprites, int block) {
        if (block < 0 || block >= content.sprites.length || content.sprites[block].length == 0) return null;
        return sprites.get(content.sprites[block][0]);
    }

    /** One band of the image, 0xRRGGBB per pixel. */
    private record Band(int[] pixels, int width, int rows) {
        void fill(int left, int upper, int s, int rgb) {
            for (int y = Math.max(0, upper); y < Math.min(rows, upper + s); y++) {
                Arrays.fill(pixels, y * width + Math.max(0, left), y * width + Math.min(width, left + s), rgb);
            }
        }

        /**
         * Draws {@code sprite} with its top-left corner at (left, upper), rotated counter-clockwise by
         * {@code rotation} quarter turns, multiplied by {@code tint} unless it is -1.
         */
        void blit(SpritePack.Sprite sprite, int left, int upper, int rotation, int tint) {
            int sw = sprite.width(), sh = sprite.height();
            int w = (rotation & 1) == 1 ? sh : sw, h = (rotation & 1) == 1 ? sw : sh;
            int y0 = Math.max(0, -upper), y1 = Math.min(h, rows - upper);
            int x0 = Math.max(0, -left), x1 = Math.min(w, width - left);
            if (y0 >= y1 || x0 >= x1) return;
            int[] src = sprite.argb();
            int tr = tint >> 16 & 0xff, tg = tint >> 8 & 0xff, tb = tint & 0xff;
            for (int dy = y0; dy < y1; dy++) {
                int row = (upper + dy) * width + left;
                for (int dx = x0; dx < x1; dx++) {
                    int sx, sy;
                    switch (rotation) {
                        case 1 -> { sx = sw - 1 - dy; sy = dx; }
                        case 2 -> { sx = sw - 1 - dx; sy = sh - 1 - dy; }
                        case 3 -> { sx = dy; sy = sh - 1 - dx; }
                        default -> { sx = dx; sy = dy; }
                    }
                    int argb = src[sy * sw + sx];
                    int a = argb >>> 24;
                    if (a == 0) continue;
                    int r = argb >> 16 & 0xff, g = argb >> 8 & 0xff, b = argb & 0xff;
                    if (tint != -1) {
                        r = r * tr / 255;
                        g = g * tg / 255;
                        b = b * tb / 255;
                    }
                    if (a != 255) {
                        int dst = pixels[row + dx];
                        r = (r * a + (dst >> 16 & 0xff) * (255 - a)) / 255;
                        g = (g * a + (dst >> 8 & 0xff) * (255 - a)) / 255;
                        b = (b * a + (dst & 0xff) * (255 - a)) / 255;
                    }
                    pixels[row + dx] = r << 16 | g << 8 | b;
                }
            }
        }
    }

    private static final class Overflow extends IOException {
        Overflow() { super(null, null); }
    }

    /** Holds the encoded PNG; past the limit the render is abandoned for a smaller scale. */
    private static final class Limited extends ByteArrayOutputStream {
        private final int limit;

        Limited(int limit) {
            super(256 * 1024);
            this.limit = limit;
        }

        @Override public void write(byte[] b, int off, int len) {
            if (count + len > limit) throw new UncheckedOverflow();
            super.write(b, off, len);
        }

        @Override public void write(int b) {
            if (count + 1 > limit) throw new UncheckedOverflow();
            super.write(b);
        }
    }

    private static final class UncheckedOverflow extends RuntimeException {
        UncheckedOverflow() { super(null, null, false, false); }
    }

}
