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

    /** Everything one render derives from the map before drawing; discarded with the render. */
    private record Frame(MapSnapshot map, SpritePack sprites, Rand rand, Tiling tiling, Lighting lighting,
                         int width, int height, int s, int[] dirs, int[] blenders) {}

    private byte[] draw(MapSnapshot map, SpritePack sprites, int maxBytes) throws IOException {
        int s = sprites.scale;
        int width = map.metadata().width(), height = map.metadata().height();
        int pixelsWide = width * s;
        var frame = new Frame(map, sprites, new Rand(), new Tiling(content, map.buildings(), width, height),
                new Lighting(content, map), width, height, s, new int[8], new int[8]);
        int[] band = new int[pixelsWide * BAND_TILES * s];

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

        // Where each pixel column samples the per-tile shadow and light grids.
        int[] column = new int[pixelsWide], columnFraction = new int[pixelsWide];
        for (int x = 0; x < pixelsWide; x++) {
            int at = (int) Math.floor(((x + 0.5) / s - 0.5) * 256);
            column[x] = at >> 8;
            columnFraction[x] = at & 0xff;
        }

        var png = new Limited(maxBytes);
        try (var writer = new PngStreamWriter(png, pixelsWide, height * s, 6)) {
            for (int top = height - 1; top >= 0; top -= BAND_TILES) {
                int bottom = Math.max(0, top - BAND_TILES + 1);
                int rows = (top - bottom + 1) * s;
                Arrays.fill(band, 0, pixelsWide * rows, 0);
                var target = new Band(band, pixelsWide, rows);
                // Layers in the engine's order: floors with their edges and ores, block shadows,
                // natural walls, conveyors and conduits, other buildings, darkness.
                for (int y = top; y >= bottom; y--) {
                    for (int x = 0; x < width; x++) drawFloor(frame, target, x, y, top);
                }
                shade(frame, target, top, column, columnFraction, frame.lighting().shadow, true);
                for (int y = top; y >= bottom; y--) {
                    for (int x = 0; x < width; x++) drawWall(frame, target, x, y, top);
                }
                for (int pass = 0; pass < 2; pass++) {
                    for (int y = Math.min(height - 1, top + reach); y >= Math.max(0, bottom - reach); y--) {
                        for (int i = rowStart[y]; i < rowStart[y + 1]; i++) {
                            var building = placed.get(byRow[i]);
                            boolean tiled = building.block() >= 0 && building.block() < content.tiling.length
                                    && content.tiling[building.block()] != RenderContent.NO_TILING;
                            if (tiled == (pass == 0)) drawBuilding(frame, target, building, top);
                        }
                    }
                }
                shade(frame, target, top, column, columnFraction, frame.lighting().light, false);
                for (int row = 0; row < rows; row++) writer.row(band, row * pixelsWide);
            }
        } catch (UncheckedOverflow overflow) {
            throw new Overflow();
        }
        return png.toByteArray();
    }

    /**
     * Applies a per-tile grid to the band: as a shadow (255 = 0.71 black, BlockRenderer.shadowColor)
     * or as brightness (255 = untouched).
     */
    private static void shade(Frame frame, Band band, int top, int[] column, int[] columnFraction, byte[] grid,
                              boolean shadow) {
        int s = frame.s(), width = frame.width(), height = frame.height();
        for (int row = 0; row < band.rows(); row++) {
            // Tile y of this pixel row, tile centres at whole numbers, y up.
            double tileY = top + 0.5 - (row + 0.5) / s;
            int at = (int) Math.floor(tileY * 256);
            int y0 = at >> 8, fy = at & 0xff;
            int offset = row * band.width();
            for (int x = 0; x < band.width(); x++) {
                int value = Lighting.sample(grid, width, height, column[x], y0, columnFraction[x], fy);
                int keep = shadow ? 256 - (value * 182 >> 8) : value + (value >> 7);
                if (keep >= 256) continue;
                int pixel = band.pixels()[offset + x];
                int r = (pixel >> 16 & 0xff) * keep >> 8, g = (pixel >> 8 & 0xff) * keep >> 8, b = (pixel & 0xff) * keep >> 8;
                band.pixels()[offset + x] = r << 16 | g << 8 | b;
            }
        }
    }

    private static final int[] D8X = {1, 1, 0, -1, -1, -1, 0, 1}, D8Y = {0, 1, 1, 1, 0, -1, -1, -1};

    /** Floor.drawBase: the floor's variant, the edges of higher floors around it (drawEdges), then the ore. */
    private void drawFloor(Frame frame, Band band, int x, int y, int top) {
        var map = frame.map();
        int width = frame.width();
        int index = x + y * width;
        int s = frame.s();
        int left = x * s, upper = (top - y) * s;
        int floor = map.floor(index), overlay = map.overlay(index);
        int seed = pack(x, y);
        if (floor >= 0 && floor < content.layer.length && content.layer[floor] == RenderContent.FLOOR) {
            if (!drawVariant(frame, band, seed, floor, left, upper)) band.fill(left, upper, s, content.color[floor]);
            if (content.edgeIn[floor]) drawEdges(frame, band, x, y, floor, left, upper);
        }
        boolean wallOre = overlay > 0 && overlay < content.layer.length && content.wallOre[overlay];
        if (overlay > 0 && overlay < content.layer.length && content.layer[overlay] == RenderContent.OVERLAY && !wallOre) {
            drawVariant(frame, band, seed, overlay, left, upper);
        }
    }

    private void drawEdges(Frame frame, Band band, int x, int y, int floor, int left, int upper) {
        var map = frame.map();
        int width = frame.width(), height = frame.height();
        int[] dirs = frame.dirs(), blenders = frame.blenders();
        int count = 0;
        for (int i = 0; i < 8; i++) {
            dirs[i] = -1;
            int nx = x + D8X[i], ny = y + D8Y[i];
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
            int other = map.floor(nx + ny * width);
            if (other < 0 || other >= content.layer.length || content.layer[other] != RenderContent.FLOOR) continue;
            boolean doEdge = content.blendId[other] > content.blendId[floor] || content.edges[floor] == null;
            if (content.edgeOut[other] && doEdge && content.cache[other] == content.cache[floor] && content.edges[other] != null) {
                boolean seen = false;
                for (int j = 0; j < count; j++) seen |= blenders[j] == other;
                if (!seen) blenders[count++] = other;
                dirs[i] = other;
            }
        }
        Arrays.sort(blenders, 0, count);
        int s = frame.s();
        for (int j = 0; j < count; j++) {
            int blender = blenders[j];
            for (int i = 0; i < 8; i++) {
                if (dirs[i] != blender) continue;
                var sprite = frame.sprites().get(content.edges[blender][(1 - D8X[i]) * 3 + 1 + D8Y[i]]);
                if (sprite != null) band.blit(sprite, left + (s - sprite.width()) / 2, upper + (s - sprite.height()) / 2, 0, -1, false);
            }
        }
    }

    /** StaticWall.drawBase: a quarter of the 2x2 "-large" sprite where four equal walls allow it, else a variant. */
    private void drawWall(Frame frame, Band band, int x, int y, int top) {
        var map = frame.map();
        int width = frame.width(), height = frame.height();
        int index = x + y * width;
        int block = map.block(index), overlay = map.overlay(index);
        if (block <= 0 || block >= content.layer.length || content.layer[block] != RenderContent.STATIC) return;
        int s = frame.s();
        int left = x * s, upper = (top - y) * s;
        int seed = pack(x, y);
        boolean drawn = false;
        String[] large = content.large[block];
        int rx = x / 2 * 2, ry = y / 2 * 2;
        if (large != null && rx < width - 1 && ry < height - 1
                && map.block(rx + ry * width) == block && map.block(rx + 1 + ry * width) == block
                && map.block(rx + (ry + 1) * width) == block && map.block(rx + 1 + (ry + 1) * width) == block) {
            frame.rand().setSeed(pack(rx, ry) * 99999L);
            if (frame.rand().nextFloat() < 0.5f) {
                var sprite = frame.sprites().get(large[(1 - y % 2) * 2 + x % 2]);
                if (sprite != null) {
                    band.blit(sprite, left + (s - sprite.width()) / 2, upper + (s - sprite.height()) / 2, 0, -1, false);
                    drawn = true;
                }
            }
        }
        if (!drawn && !drawVariant(frame, band, seed, block, left, upper)) band.fill(left, upper, s, content.color[block]);
        if (overlay > 0 && overlay < content.layer.length && content.wallOre[overlay]) drawVariant(frame, band, seed, overlay, left, upper);
    }

    /** Point2.pack. */
    private static int pack(int x, int y) {
        return (short) x << 16 | (short) y & 0xffff;
    }

    /** Floor, ore and wall variants are picked as the engine does: Mathf.randomSeed(pos, 0, variants - 1). */
    private boolean drawVariant(Frame frame, Band band, int seed, int block, int left, int upper) {
        String[] names = content.sprites[block];
        if (names.length == 0) return false;
        int max = names.length - 1;
        var rand = frame.rand();
        rand.setSeed(seed);
        if (max != 0 && (max & max - 1) == 0) rand.nextInt();
        var sprite = frame.sprites().get(names[rand.nextInt(max + 1)]);
        if (sprite == null) return false;
        int s = frame.s();
        band.blit(sprite, left + (s - sprite.width()) / 2, upper + (s - sprite.height()) / 2, 0, -1, false);
        return true;
    }

    private void drawBuilding(Frame frame, Band band, MapSnapshot.Placed building, int top) {
        int block = building.block();
        if (block < 0 || block >= content.layer.length) return;
        var sprites = frame.sprites();
        int s = frame.s();
        // Even sizes sit half a tile right of and above their centre tile, as World places them.
        int shift = content.size[block] % 2 == 0 ? s / 2 : 0;
        int centreX = building.x() * s + s / 2 + shift;
        int centreY = (top - building.y()) * s + s / 2 - shift;
        int rotation = content.rotate[block] ? building.rotation() & 3 : 0;

        if (content.tiling[block] == RenderContent.STACK && content.tilingTop[block] != null) {
            // StackConveyorBuild.drawCached: the sprite of its state, an edge on every side that does not join.
            var stack = frame.tiling().stack(building);
            blitCentred(band, sprites.get(content.tilingTop[block][stack.state()]), centreX, centreY, rotation, -1, false);
            var edge = sprites.get(content.cap[block]);
            for (int i = 0; i < 4; i++) {
                if ((stack.joined() & 1 << i) == 0) blitCentred(band, edge, centreX, centreY, (rotation - i) & 3, -1, false);
            }
            return;
        }
        if (content.tiling[block] != RenderContent.NO_TILING && content.tilingTop[block] != null) {
            var blend = frame.tiling().blend(building);
            String[] bottom = content.tilingBottom[block];
            if (bottom != null) {
                blitCentred(band, sprites.get(bottom[blend.bits()]), centreX, centreY, rotation, content.bottomTint[block], blend.flip());
                var cap = sprites.get(content.cap[block]);
                if (blend.capped()) blitCentred(band, cap, centreX, centreY, rotation, -1, false);
                if (blend.backCapped()) blitCentred(band, cap, centreX, centreY, (rotation + 2) & 3, -1, false);
            }
            blitCentred(band, sprites.get(content.tilingTop[block][blend.bits()]), centreX, centreY, rotation, -1, blend.flip());
            return;
        }

        var sprite = icon(sprites, block);
        if (sprite == null) return;
        blitCentred(band, sprite, centreX, centreY, rotation, -1, false);
        var team = sprites.get(content.teamSprite[block]);
        if (team != null) {
            int tint = building.team() < content.teamColor.length ? content.teamColor[building.team()] : 0xffffff;
            blitCentred(band, team, centreX, centreY, rotation, tint, false);
        }
    }

    private static void blitCentred(Band band, SpritePack.Sprite sprite, int centreX, int centreY, int rotation, int tint,
                                    boolean flip) {
        if (sprite == null) return;
        boolean swap = (rotation & 1) == 1;
        int w = swap ? sprite.height() : sprite.width(), h = swap ? sprite.width() : sprite.height();
        band.blit(sprite, centreX - w / 2, centreY - h / 2, rotation, tint, flip);
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
         * {@code rotation} quarter turns after mirroring it vertically if {@code flip}, multiplied by
         * {@code tint} unless it is -1.
         */
        void blit(SpritePack.Sprite sprite, int left, int upper, int rotation, int tint, boolean flip) {
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
                    if (flip) sy = sh - 1 - sy;
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
