package org.xcore.msav;

/** How much of a map the reader accepts. The ceilings hold whatever a caller configures. */
public interface MsavLimits {
    int MAX_INFLATED_BYTES = 64 * 1024 * 1024;
    int MAX_REGION_BYTES = 32 * 1024 * 1024;
    int MAX_ALLOCATED_TILES = 1_000_000;

    /** Compressed file size. */
    int maxFileBytes();

    /** Width × height above which tiles are not decoded; the snapshot is then partial. */
    int maxTiles();

    static MsavLimits of(int maxFileBytes, int maxTiles) {
        if (maxFileBytes <= 0 || maxTiles <= 0) throw new IllegalArgumentException("Positive map limits required");
        int file = Math.min(maxFileBytes, MAX_INFLATED_BYTES), tiles = Math.min(maxTiles, MAX_ALLOCATED_TILES);
        return new MsavLimits() {
            @Override public int maxFileBytes() { return file; }
            @Override public int maxTiles() { return tiles; }
        };
    }
}
