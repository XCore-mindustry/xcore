package org.xcore.plugin.map.domain;

import org.xcore.plugin.model.MapData;
import org.xcore.plugin.model.MapUploadRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which map a statistics record belongs to (map-updates-design-spec.md, stage 3).
 *
 * <p>A map is its forum post: every file a review of the post installed, on any server and
 * under any name, is a revision of the same map. A file that no review installed is a map of
 * its own. Revisions are linked only through the post recorded in the upload attribution, never
 * by title, author, slug or content hash: two different maps may share a title, and every
 * revision of one map has a different hash. A file name that reviews of two different posts
 * installed (on different servers) is ambiguous and stays a map of its own.
 *
 * <p>Statistics records stay split by game mode and keep their ids, which votes and games
 * reference; a map groups them, it does not replace them.
 */
public final class MapKeys {
    public static final String POST = "post:";
    public static final String FILE = "file:";
    public static final String RECORD = "record:";

    private MapKeys() {
    }

    public static String post(String threadId) {
        return POST + threadId.trim();
    }

    public static String file(String fileName) {
        return FILE + lower(fileName);
    }

    /** A record with a real file name; legacy records without one are maps of their own. */
    public static boolean named(String fileName) {
        return fileName != null && !fileName.isBlank() && !"Unknown".equalsIgnoreCase(fileName.trim());
    }

    public static String lower(String fileName) {
        return fileName.trim().toLowerCase(Locale.ROOT);
    }

    /** The post each installed file came from, by lower-case file name; ambiguous names are left out. */
    public static Links links(Collection<MapUploadRecord> uploads) {
        Map<String, Set<String>> threads = new HashMap<>();
        for (MapUploadRecord row : uploads) {
            if (row == null || !named(row.fileName) || row.upload == null) continue;
            String thread = row.upload.threadId;
            if (thread == null || thread.isBlank()) continue;
            threads.computeIfAbsent(lower(row.fileName), ignored -> new HashSet<>()).add(thread.trim());
        }
        Map<String, String> posts = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        threads.forEach((file, ids) -> {
            if (ids.size() == 1) posts.put(file, ids.iterator().next());
            else ambiguous.add(file);
        });
        return new Links(Map.copyOf(posts), Set.copyOf(ambiguous));
    }

    public static String keyOf(MapData record, Links links) {
        if (!named(record.fileName)) {
            return RECORD + (record.id == null ? "" : record.id.toHexString());
        }
        String thread = links.posts().get(lower(record.fileName));
        return thread != null ? post(thread) : file(record.fileName);
    }

    /** The records of every map, in the order their first record appears. */
    public static Map<String, List<MapData>> group(Collection<MapData> records, Links links) {
        Map<String, List<MapData>> groups = new LinkedHashMap<>();
        for (MapData record : records) {
            if (record == null || (!named(record.fileName) && record.id == null)) continue;
            groups.computeIfAbsent(keyOf(record, links), ignored -> new ArrayList<>()).add(record);
        }
        return groups;
    }

    /** {@code posts}: lower-case file name to thread id; {@code ambiguous}: names two posts installed. */
    public record Links(Map<String, String> posts, Set<String> ambiguous) {
    }
}
