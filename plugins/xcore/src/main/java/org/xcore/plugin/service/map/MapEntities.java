package org.xcore.plugin.service.map;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.xcore.plugin.common.PLog;
import org.xcore.plugin.concurrent.StorageExecutor;
import org.xcore.plugin.config.TomlSecretsConfig;
import org.xcore.plugin.database.repository.MapEntityProjection;
import org.xcore.plugin.map.domain.MapEntity;
import org.xcore.plugin.map.domain.MapKeys;
import org.xcore.plugin.model.MapData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mongodb.client.model.Filters.in;

/**
 * The maps behind installed files (map-updates-design-spec.md, stage 3): keeps
 * {@code map_entities} current and sums a map's ratings over all of its revisions and modes.
 *
 * <p>The entity only links records into a map; the numbers are read from the records each
 * time, so a vote shows at once. A file whose map is not known yet keeps its own record's
 * numbers, as before.
 */
@Singleton
public class MapEntities {
    private final MongoDatabase database;
    private final StorageExecutor worker;
    private final TomlSecretsConfig secrets;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicBoolean full = new AtomicBoolean();
    private final java.util.Set<String> changedFiles = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Inject
    public MapEntities(MongoDatabase database, StorageExecutor worker, TomlSecretsConfig secrets) {
        this.database = database;
        this.worker = worker;
        this.secrets = secrets;
    }

    /** A map's ratings over every record it links: all its files and all modes. */
    public record Totals(int likes, int dislikes, int reputation) {
    }

    /** Rebuilds every entity soon on a storage worker (startup); calls while one runs fold into one more. */
    public void refresh() {
        full.set(true);
        schedule();
    }

    /**
     * Brings up to date soon only the maps {@code fileName} belongs to, before and after a change
     * to that file (upload, removal, restore). Names given while an update runs are batched.
     */
    public void refresh(String fileName) {
        if (!MapKeys.named(fileName)) return;
        changedFiles.add(fileName);
        schedule();
    }

    private void schedule() {
        if (secrets.database.readOnly) return;
        dirty.set(true);
        if (!running.compareAndSet(false, true)) return;
        try {
            worker.execute(this::drain);
        } catch (RejectedExecutionException busy) {
            running.set(false);
            PLog.warn("Map entity refresh skipped: storage is busy");
        }
    }

    private void drain() {
        try {
            while (dirty.getAndSet(false)) {
                try {
                    if (full.getAndSet(false)) {
                        // The full rebuild reads after this point, so it covers every name so far.
                        changedFiles.clear();
                        var result = MapEntityProjection.rebuild(database);
                        if (!result.ambiguous().isEmpty()) {
                            PLog.warn("Map files installed by two posts, kept apart: @", result.ambiguous());
                        }
                    } else {
                        var batch = List.copyOf(changedFiles);
                        changedFiles.removeAll(batch);
                        MapEntityProjection.update(database, batch);
                    }
                } catch (RuntimeException failure) {
                    PLog.warn("Map entity refresh failed: @", failure.toString());
                }
            }
        } finally {
            running.set(false);
        }
        if (dirty.get()) schedule();
    }

    /**
     * The totals of the map each of {@code fileNames} belongs to, by lower-case file name.
     * Blocking: call on a storage worker. Files without a known map are left out.
     */
    public Map<String, Totals> totals(Collection<String> fileNames) {
        var wanted = fileNames.stream().filter(MapKeys::named).map(MapKeys::lower).distinct().toList();
        if (wanted.isEmpty()) return Map.of();
        MongoCollection<MapEntity> entities = database.getCollection(MapEntityProjection.COLLECTION, MapEntity.class);
        var maps = entities.find(in("file_names_lower", wanted)).into(new ArrayList<>());
        var linked = new LinkedHashSet<String>();
        for (MapEntity map : maps) linked.addAll(map.fileNames);
        if (linked.isEmpty()) return Map.of();
        var records = database.getCollection("maps", MapData.class)
                .find(in("file_name", List.copyOf(linked))).into(new ArrayList<>());
        return totals(wanted, maps, records);
    }

    /** {@link #totals(Collection)} without the reads; {@code wanted} are lower-case file names. */
    public static Map<String, Totals> totals(Collection<String> wanted, List<MapEntity> maps, List<MapData> records) {
        var byFile = new HashMap<String, List<MapData>>();
        for (MapData record : records) {
            if (MapKeys.named(record.fileName)) {
                byFile.computeIfAbsent(record.fileName, ignored -> new ArrayList<>()).add(record);
            }
        }
        var result = new HashMap<String, Totals>();
        for (MapEntity map : maps) {
            int likes = 0, dislikes = 0, reputation = 0;
            for (String file : map.fileNames) {
                for (MapData record : byFile.getOrDefault(file, List.of())) {
                    likes += record.like;
                    dislikes += record.dislike;
                    reputation += record.reputation;
                }
            }
            var totals = new Totals(likes, dislikes, reputation);
            for (String file : map.fileNamesLower) {
                if (wanted.contains(file)) result.put(file, totals);
            }
        }
        return Map.copyOf(result);
    }

    /**
     * The record a list shows for an installed file: its own record for this mode, with the
     * ratings of its whole map when the map is known. The record itself is not changed.
     */
    public static MapData withTotals(MapData perMode, Totals totals, String fileName, String mode) {
        if (totals == null) return perMode;
        MapData shown = new MapData(perMode == null ? fileName : perMode.name, fileName,
                perMode == null ? "Unknown" : perMode.author, perMode == null ? mode : perMode.gameMode);
        if (perMode != null) {
            shown.id = perMode.id;
            shown.popularity = perMode.popularity;
            shown.interest = perMode.interest;
        }
        shown.like = totals.likes();
        shown.dislike = totals.dislikes();
        shown.reputation = totals.reputation();
        return shown;
    }
}
