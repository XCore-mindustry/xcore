package org.xcore.maprenderer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Writes an 8-bit RGB PNG row by row, so the caller never holds the whole image. Each row uses
 * the Sub filter; the compressed stream is cut into IDAT chunks of {@link #CHUNK} bytes.
 */
public final class PngStreamWriter implements AutoCloseable {
    private static final int CHUNK = 64 * 1024;

    private final OutputStream out;
    private final int width;
    private final Deflater deflater;
    private final DeflaterOutputStream idat;
    private final byte[] row;
    private int rowsLeft;
    private boolean closed;

    public PngStreamWriter(OutputStream out, int width, int height, int level) throws IOException {
        this.out = out;
        this.width = width;
        this.rowsLeft = height;
        this.row = new byte[1 + width * 3];
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        var header = new byte[13];
        putInt(header, 0, width);
        putInt(header, 4, height);
        header[8] = 8; // bit depth
        header[9] = 2; // truecolour
        chunk("IHDR", header, header.length);
        deflater = new Deflater(level);
        idat = new DeflaterOutputStream(new ChunkStream(), deflater, CHUNK);
    }

    /** One row of {@code width} pixels, 0xRRGGBB, starting at {@code offset}. */
    public void row(int[] rgb, int offset) throws IOException {
        if (rowsLeft-- <= 0) throw new IllegalStateException("All rows were written");
        row[0] = 1; // Sub
        int previousR = 0, previousG = 0, previousB = 0;
        for (int x = 0, i = 1; x < width; x++) {
            int pixel = rgb[offset + x];
            int r = pixel >> 16 & 0xff, g = pixel >> 8 & 0xff, b = pixel & 0xff;
            row[i++] = (byte) (r - previousR);
            row[i++] = (byte) (g - previousG);
            row[i++] = (byte) (b - previousB);
            previousR = r;
            previousG = g;
            previousB = b;
        }
        idat.write(row);
    }

    @Override
    public void close() throws IOException {
        // Closing twice (explicitly, then by try-with-resources) must not write a second IEND.
        if (closed) return;
        closed = true;
        try {
            if (rowsLeft != 0) throw new IllegalStateException(rowsLeft + " rows missing");
            idat.finish();
            idat.flush();
            chunk("IEND", new byte[0], 0);
        } finally {
            deflater.end();
        }
    }

    private void chunk(String type, byte[] data, int length) throws IOException {
        byte[] head = new byte[8];
        putInt(head, 0, length);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, head, 4, 4);
        out.write(head);
        out.write(data, 0, length);
        var crc = new CRC32();
        crc.update(name);
        crc.update(data, 0, length);
        byte[] tail = new byte[4];
        putInt(tail, 0, (int) crc.getValue());
        out.write(tail);
    }

    private static void putInt(byte[] target, int at, int value) {
        target[at] = (byte) (value >>> 24);
        target[at + 1] = (byte) (value >>> 16);
        target[at + 2] = (byte) (value >>> 8);
        target[at + 3] = (byte) value;
    }

    /** Collects deflated bytes and emits them as IDAT chunks. */
    private final class ChunkStream extends OutputStream {
        private final byte[] buffer = new byte[CHUNK];
        private int length;

        @Override public void write(int b) throws IOException {
            buffer[length++] = (byte) b;
            if (length == CHUNK) flush();
        }

        @Override public void write(byte[] data, int off, int len) throws IOException {
            while (len > 0) {
                int n = Math.min(len, CHUNK - length);
                System.arraycopy(data, off, buffer, length, n);
                length += n;
                off += n;
                len -= n;
                if (length == CHUNK) flush();
            }
        }

        @Override public void flush() throws IOException {
            if (length == 0) return;
            chunk("IDAT", buffer, length);
            length = 0;
        }
    }
}
