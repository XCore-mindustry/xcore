package org.xcore.plugin.service.map;

import arc.files.Fi;
import com.google.gson.Gson;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import mindustry.Vars;
import org.xcore.plugin.common.PLog;
import org.xcore.plugin.config.TomlSecretsConfig;
import org.xcore.plugin.map.domain.MapContentHash;
import org.xcore.plugin.model.MapDiscordUpload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Map files a server set aside instead of deleting them: removed, or replaced by an upload.
 *
 * <p>Each kept file lives in {@code <custom maps>/.trash} as {@code <trashId>.msav.kept}, next to a
 * {@code <trashId>.json} that says what it was, why it was kept and the Discord attribution it had.
 * The engine loads only {@code *.msav} files directly in the maps directory, so nothing here is ever
 * offered as a map. Entries older than {@code maps.trash.retention_days}, and the oldest beyond
 * {@code maps.trash.max_entries}, are deleted whenever a file is kept.</p>
 *
 * <p>All methods do file I/O: call them off the game thread, except a removal, which the
 * transport handler runs where it removes the map.</p>
 */
@Singleton
public class MapTrash {
    private static final Gson GSON = new Gson();
    private static final int ID_NAME_LENGTH = 100;

    private final TomlSecretsConfig secrets;
    private final LongSupplier clock;

    @Inject
    public MapTrash(TomlSecretsConfig secrets) {
        this(secrets, System::currentTimeMillis);
    }

    MapTrash(TomlSecretsConfig secrets, LongSupplier clock) {
        this.secrets = secrets;
        this.clock = clock;
    }

    public enum Reason { removed, replaced }

    /** What one kept file was. {@code upload} is the attribution the file had, or null. */
    public record Entry(String trashId, String fileName, String contentHash, long sizeBytes, long trashedAt,
                        Reason reason, String name, String author, MapDiscordUpload upload) {}

    private Fi directory() {
        return Vars.customMapDirectory.child(".trash");
    }

    /**
     * Keeps a copy of {@code file} before it is removed or overwritten. Returns nothing when there
     * is no file to keep, or keeping it failed; the caller goes on, as it did before there was a trash.
     */
    public synchronized Optional<Entry> keep(Fi file, Reason reason, String name, String author,
                                             MapDiscordUpload upload) {
        if (file == null || !file.exists() || file.isDirectory()) return Optional.empty();
        try {
            byte[] bytes = file.readBytes();
            String hash = MapContentHash.ofContent(bytes).asHex();
            long now = clock.getAsLong();
            String id = trashId(now, hash, file.name());
            Fi dir = directory();
            dir.mkdirs();
            dir.child(id + ".msav.kept").writeBytes(bytes, false);
            var entry = new Entry(id, file.name(), hash, bytes.length, now, reason,
                    blankToNull(name), blankToNull(author), upload);
            dir.child(id + ".json").writeString(GSON.toJson(entry), false);
            prune();
            return Optional.of(entry);
        } catch (RuntimeException failure) {
            PLog.warn("Could not keep a copy of map @: @", file.name(), failure.toString());
            return Optional.empty();
        }
    }

    /** The kept files, newest first. */
    public synchronized List<Entry> list() {
        Fi dir = directory();
        if (!dir.exists()) return List.of();
        var entries = new ArrayList<Entry>();
        for (Fi sidecar : dir.list()) {
            if (!sidecar.name().endsWith(".json")) continue;
            read(sidecar).ifPresent(entries::add);
        }
        entries.sort(Comparator.comparingLong(Entry::trashedAt).reversed().thenComparing(Entry::trashId));
        return entries;
    }

    public synchronized Optional<Entry> find(String trashId) {
        if (!validId(trashId)) return Optional.empty();
        Fi sidecar = directory().child(trashId + ".json");
        return sidecar.exists() && directory().child(trashId + ".msav.kept").exists() ? read(sidecar) : Optional.empty();
    }

    /** The bytes of a kept file, checked against its recorded hash. */
    public synchronized Optional<byte[]> bytes(Entry entry) {
        Fi kept = directory().child(entry.trashId() + ".msav.kept");
        if (!kept.exists()) return Optional.empty();
        byte[] bytes = kept.readBytes();
        String hash = MapContentHash.ofContent(bytes).asHex();
        return hash.equals(entry.contentHash()) ? Optional.of(bytes) : Optional.empty();
    }

    /** Forgets a kept file once it is back in place. */
    public synchronized void forget(String trashId) {
        if (!validId(trashId)) return;
        directory().child(trashId + ".msav.kept").delete();
        directory().child(trashId + ".json").delete();
    }

    /** Deletes entries past the retention period, and the oldest beyond the size cap. */
    public synchronized void prune() {
        var config = secrets.maps.trash;
        long cutoff = clock.getAsLong() - config.retentionDays * 86_400_000L;
        var entries = list();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            if (i >= config.maxEntries || entry.trashedAt() < cutoff) forget(entry.trashId());
        }
    }

    private Optional<Entry> read(Fi sidecar) {
        try {
            var entry = GSON.fromJson(sidecar.readString(), Entry.class);
            return entry == null || !validId(entry.trashId()) ? Optional.empty() : Optional.of(entry);
        } catch (RuntimeException corrupt) { // JsonParseException included
            PLog.warn("Ignoring unreadable map trash entry @: @", sidecar.name(), corrupt.toString());
            return Optional.empty();
        }
    }

    /** {@code <millis>-<8 hex>-<file name>}, with anything outside the protocol's id alphabet replaced. */
    static String trashId(long at, String hash, String fileName) {
        String base = fileName.replaceAll("[^A-Za-z0-9._-]", "_").replace("..", "_");
        if (base.length() > ID_NAME_LENGTH) base = base.substring(base.length() - ID_NAME_LENGTH);
        return at + "-" + hash.substring(0, 8) + "-" + base;
    }

    static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9._-]{1,160}") && !id.contains("..");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
