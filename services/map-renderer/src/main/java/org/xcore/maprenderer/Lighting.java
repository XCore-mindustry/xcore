package org.xcore.maprenderer;

import org.xcore.msav.MapSnapshot;

/**
 * Block shadows and darkness, one byte per tile, sampled bilinearly as the engine's linear-filtered
 * frame buffers are ({@code BlockRenderer.drawShadows} and {@code drawDarkness}).
 *
 * <ul>
 *   <li>Shadow: every tile whose block has {@code hasShadow}; drawn under blocks at 0.71 alpha.</li>
 *   <li>Darkness: solid natural walls ({@code Tile.isDarkened}) darken with their L1 distance to
 *       the nearest open tile ({@code World.getWallDarkness}, radius 4), and the two tiles at the
 *       map border darken ({@code borderDarkness}). Drawn over everything.</li>
 * </ul>
 */
final class Lighting {
    private static final int DARK_RADIUS = 4;
    final int width, height;
    /** 255 where a shadow is cast. */
    final byte[] shadow;
    /** Brightness after darkness, 255 = untouched. */
    final byte[] light;

    Lighting(RenderContent content, MapSnapshot map, boolean buildings) {
        width = map.metadata().width();
        height = map.metadata().height();
        int count = width * height;
        shadow = new byte[count];
        light = new byte[count];
        // L1 distance to the nearest tile that is not darkened, capped at radius + 1.
        byte[] distance = new byte[count];
        int cap = DARK_RADIUS + 1;
        for (int i = 0; i < count; i++) {
            int block = map.block(i);
            boolean known = block >= 0 && block < content.layer.length;
            if (known && content.shadow[block] && (buildings || content.layer[block] != RenderContent.BUILDING)) {
                shadow[i] = (byte) 255;
            }
            distance[i] = (byte) (known && content.darkens[block] ? cap : 0);
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = x + y * width, d = distance[i];
                if (x > 0) d = Math.min(d, distance[i - 1] + 1);
                if (y > 0) d = Math.min(d, distance[i - width] + 1);
                distance[i] = (byte) d;
            }
        }
        for (int y = height - 1; y >= 0; y--) {
            for (int x = width - 1; x >= 0; x--) {
                int i = x + y * width, d = distance[i];
                if (x < width - 1) d = Math.min(d, distance[i + 1] + 1);
                if (y < height - 1) d = Math.min(d, distance[i + width] + 1);
                distance[i] = (byte) d;
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = x + y * width;
                float dark = distance[i] > 0 ? Math.max(distance[i] - 1, 0) : 0;
                int edge = Math.min(Math.min(x, y), Math.min(width - 1 - x, height - 1 - y));
                if (edge <= 2) dark = Math.max((2 - edge) * 2f, dark);
                float brightness = dark <= 0 ? 1f : 1f - Math.min((dark + 0.5f) / 4f, 1f);
                light[i] = (byte) Math.round(brightness * 255);
            }
        }
    }

    /** Bilinear sample at tile coordinates (tile centres at whole numbers), clamped to the edge. */
    static int sample(byte[] grid, int width, int height, int x0, int y0, int fx, int fy) {
        int x1 = Math.min(x0 + 1, width - 1), y1 = Math.min(y0 + 1, height - 1);
        int xa = Math.max(x0, 0), ya = Math.max(y0, 0);
        int a = grid[xa + ya * width] & 0xff, b = grid[x1 + ya * width] & 0xff;
        int c = grid[xa + y1 * width] & 0xff, d = grid[x1 + y1 * width] & 0xff;
        if (a == b && b == c && c == d) return a;
        int top = a * (256 - fx) + b * fx, bottom = c * (256 - fx) + d * fx;
        return (top * (256 - fy) + bottom * fy) >> 16;
    }
}
