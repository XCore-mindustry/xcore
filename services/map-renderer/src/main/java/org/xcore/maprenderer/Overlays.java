package org.xcore.maprenderer;

import java.util.List;

/**
 * Marks drawn over a finished band, in whole-image pixel coordinates (y down) so a mark that
 * crosses bands is drawn in both: the coordinate grid with its labels and the spawn drop zones.
 */
final class Overlays {
    private static final int MINOR = 10, MAJOR = 50;
    private static final int WHITE = 0xffffff, BLACK = 0x000000, RED = 0xff4a4a;

    /** One spawn: its centre and drop zone radius, in image pixels. */
    record Zone(double x, double y, double radius) {}

    private Overlays() {}

    /** Pixels of one band: rows {@code first} to {@code first + rows - 1} of the image. */
    record Target(int[] pixels, int width, int first, int rows) {
        void blend(int x, int y, int rgb, int alpha) {
            int row = y - first;
            if (x < 0 || x >= width || row < 0 || row >= rows) return;
            int i = row * width + x, dst = pixels[i];
            int r = ((rgb >> 16 & 0xff) * alpha + (dst >> 16 & 0xff) * (256 - alpha)) >> 8;
            int g = ((rgb >> 8 & 0xff) * alpha + (dst >> 8 & 0xff) * (256 - alpha)) >> 8;
            int b = ((rgb & 0xff) * alpha + (dst & 0xff) * (256 - alpha)) >> 8;
            pixels[i] = r << 16 | g << 8 | b;
        }
    }

    /**
     * Lines on tile edges: tile x = 10k on its left edge, tile y = 10k on its bottom edge (the
     * game's y points up), so the label "x,y" sits at the bottom-left corner of tile (x, y).
     */
    static void grid(Target band, int tilesWide, int tilesHigh, int s) {
        int thick = Math.max(1, s / 8);
        int imageHigh = tilesHigh * s;
        // A dark line with a light one beside it reads on snow and on dark rock alike.
        for (int tx = 0; tx <= tilesWide; tx += MINOR) {
            boolean major = tx % MAJOR == 0;
            for (int dx = 0; dx < thick * 2; dx++) {
                int x = Math.min(tx * s + dx, tilesWide * s - 1);
                int rgb = dx < thick ? BLACK : WHITE, alpha = (major ? 150 : 70) / (dx < thick ? 1 : 2);
                for (int y = band.first(); y < band.first() + band.rows(); y++) band.blend(x, y, rgb, alpha);
            }
        }
        for (int ty = 0; ty <= tilesHigh; ty += MINOR) {
            boolean major = ty % MAJOR == 0;
            int edge = Math.min(imageHigh - ty * s, imageHigh - 1);
            for (int dy = 0; dy < thick * 2; dy++) {
                int y = edge - dy;
                if (y < band.first() || y >= band.first() + band.rows()) continue;
                int rgb = dy < thick ? BLACK : WHITE, alpha = (major ? 150 : 70) / (dy < thick ? 1 : 2);
                for (int x = 0; x < band.width(); x++) band.blend(x, y, rgb, alpha);
            }
        }
        int scale = Math.max(1, s / 4);
        for (int tx = 0; tx < tilesWide; tx += MAJOR) {
            for (int ty = 0; ty < tilesHigh; ty += MAJOR) {
                int left = tx * s + 2 * thick + scale, bottom = imageHigh - ty * s - 2 * thick - scale;
                text(band, tx + "," + ty, left, bottom - 5 * scale, scale);
            }
        }
    }

    /** Each drop zone: a translucent disc, its rim and a dot on the spawn. */
    static void spawns(Target band, List<Zone> zones, int s) {
        double rim = Math.max(2, s / 3.0);
        for (var zone : zones) {
            double reach = zone.radius() + rim;
            int y0 = (int) Math.floor(zone.y() - reach), y1 = (int) Math.ceil(zone.y() + reach);
            y0 = Math.max(y0, band.first());
            y1 = Math.min(y1, band.first() + band.rows() - 1);
            for (int y = y0; y <= y1; y++) {
                double dy = y + 0.5 - zone.y();
                int x0 = (int) Math.floor(zone.x() - reach), x1 = (int) Math.ceil(zone.x() + reach);
                for (int x = Math.max(0, x0); x <= Math.min(band.width() - 1, x1); x++) {
                    double dx = x + 0.5 - zone.x(), d = Math.sqrt(dx * dx + dy * dy);
                    if (d <= s * 0.6) band.blend(x, y, RED, 230);
                    else if (Math.abs(d - zone.radius()) <= rim / 2) band.blend(x, y, RED, 200);
                    else if (d < zone.radius()) band.blend(x, y, RED, 36);
                }
            }
        }
    }

    // 3x5 digits and a comma, one row of three bits per line.
    private static final int[][] GLYPHS = {
            {7, 5, 5, 5, 7}, {2, 6, 2, 2, 7}, {7, 1, 7, 4, 7}, {7, 1, 7, 1, 7}, {5, 5, 7, 1, 1},
            {7, 4, 7, 1, 7}, {7, 4, 7, 5, 7}, {7, 1, 1, 1, 1}, {7, 5, 7, 5, 7}, {7, 5, 7, 1, 7},
    };
    private static final int[] COMMA = {0, 0, 0, 2, 4};

    /** White text with a black outline, top-left at (left, upper). */
    static void text(Target band, String text, int left, int upper, int scale) {
        for (int pass = 0; pass < 2; pass++) {
            int x = left;
            for (char c : text.toCharArray()) {
                int[] glyph = c == ',' ? COMMA : GLYPHS[c - '0'];
                for (int gy = 0; gy < 5; gy++) {
                    for (int gx = 0; gx < 3; gx++) {
                        if ((glyph[gy] >> (2 - gx) & 1) == 0) continue;
                        int px = x + gx * scale, py = upper + gy * scale;
                        if (pass == 0) {
                            for (int oy = -1; oy <= scale; oy++)
                                for (int ox = -1; ox <= scale; ox++) band.blend(px + ox, py + oy, BLACK, 200);
                        } else {
                            for (int oy = 0; oy < scale; oy++)
                                for (int ox = 0; ox < scale; ox++) band.blend(px + ox, py + oy, WHITE, 256);
                        }
                    }
                }
                x += (c == ',' ? 3 : 4) * scale;
            }
        }
    }
}
