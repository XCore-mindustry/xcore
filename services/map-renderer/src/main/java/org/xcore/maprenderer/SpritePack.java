package org.xcore.maprenderer;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.InflaterInputStream;

/**
 * The sprites of one scale from {@code sprites.pack}, as ARGB pixels. Only the requested scale's
 * section is inflated; the others are skipped by length.
 */
public final class SpritePack {
    public final int scale;
    private final Map<String, Sprite> sprites;

    public record Sprite(int width, int height, int[] argb) {}

    private SpritePack(int scale, Map<String, Sprite> sprites) {
        this.scale = scale;
        this.sprites = sprites;
    }

    public Sprite get(String name) { return name == null ? null : sprites.get(name); }

    public long bytes() {
        long total = 0;
        for (var sprite : sprites.values()) total += (long) sprite.argb.length * 4;
        return total;
    }

    public static SpritePack load(Path file, int scale) throws IOException {
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != 0x584d5250 || in.readInt() != 1) throw new IOException("Not a sprite pack");
            int sections = in.readInt();
            for (int i = 0; i < sections; i++) {
                int sectionScale = in.readInt();
                int length = in.readInt();
                if (sectionScale != scale) {
                    in.skipNBytes(length);
                    continue;
                }
                var data = new DataInputStream(new BufferedInputStream(
                        new InflaterInputStream(new BoundedInput(in, length)), 1 << 16));
                int count = data.readInt();
                var sprites = new HashMap<String, Sprite>(count * 2);
                for (int s = 0; s < count; s++) {
                    String name = data.readUTF();
                    int width = data.readUnsignedShort(), height = data.readUnsignedShort();
                    int[] argb = new int[width * height];
                    for (int p = 0; p < argb.length; p++) argb[p] = data.readInt();
                    sprites.put(name, new Sprite(width, height, argb));
                }
                return new SpritePack(scale, sprites);
            }
        }
        throw new IOException("No sprites at scale " + scale);
    }

    /** Reads at most {@code remaining} bytes of the underlying stream. */
    private static final class BoundedInput extends java.io.FilterInputStream {
        private long remaining;

        BoundedInput(java.io.InputStream in, long remaining) {
            super(in);
            this.remaining = remaining;
        }

        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = super.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        @Override public void close() {}
    }
}
