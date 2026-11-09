package org.xcore.maprenderer.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finished renders on disk, by map content, render options and renderer version. Least recently
 * used files are removed once the directory is over its size limit.
 */
final class RenderCache {
    /** One stored render. */
    record Entry(byte[] png, int width, int height, int scale, int renderMs) {}

    private final Path directory;
    private final long maxBytes;

    RenderCache(Path directory, long maxBytes) throws IOException {
        this.directory = Files.createDirectories(directory);
        this.maxBytes = maxBytes;
    }

    static String key(String contentHash, int maxSide, int maxBytes, org.xcore.maprenderer.RenderOptions options,
                      String version) {
        String style = options.key();
        return contentHash + "-" + maxSide + "-" + maxBytes + (style.isEmpty() ? "" : "-" + style) + "-" + version;
    }

    synchronized Entry get(String key) {
        Path png = directory.resolve(key + ".png"), meta = directory.resolve(key + ".meta");
        try {
            if (!Files.exists(png) || !Files.exists(meta)) return null;
            String[] parts = Files.readString(meta, StandardCharsets.US_ASCII).trim().split(" ");
            byte[] bytes = Files.readAllBytes(png);
            var now = FileTime.from(Instant.now());
            Files.setLastModifiedTime(png, now);
            return new Entry(bytes, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    synchronized void put(String key, Entry entry) {
        try {
            Path temp = Files.createTempFile(directory, key, ".tmp");
            Files.write(temp, entry.png());
            Files.writeString(directory.resolve(key + ".meta"),
                    entry.width() + " " + entry.height() + " " + entry.scale() + " " + entry.renderMs(), StandardCharsets.US_ASCII);
            Files.move(temp, directory.resolve(key + ".png"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            evict();
        } catch (IOException ignored) {
            // A cache that cannot be written only costs a render next time.
        }
    }

    private void evict() throws IOException {
        List<Path> pngs = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.toString().endsWith(".png")).forEach(pngs::add);
        }
        long total = 0;
        for (Path png : pngs) total += Files.size(png);
        if (total <= maxBytes) return;
        pngs.sort(Comparator.comparing(path -> {
            try {
                return Files.getLastModifiedTime(path);
            } catch (IOException gone) {
                return FileTime.fromMillis(0);
            }
        }));
        for (Path png : pngs) {
            if (total <= maxBytes) break;
            total -= Files.size(png);
            Files.deleteIfExists(png);
            String name = png.getFileName().toString();
            Files.deleteIfExists(png.resolveSibling(name.substring(0, name.length() - 4) + ".meta"));
        }
    }
}
