package org.xcore.plugin.database.repository;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.xcore.plugin.map.domain.MapDataConsolidator;
import org.xcore.plugin.map.domain.MapEntity;
import org.xcore.plugin.map.domain.MapKeys;
import org.xcore.plugin.model.MapData;
import org.xcore.plugin.model.MapUploadRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.nin;

/**
 * Rebuilds {@code map_entities} from the statistics records ({@code maps}) and the upload
 * attribution of every server ({@code map_uploads}), one entity per map as {@link MapKeys}
 * defines it.
 *
 * <p>The collection is derived: nothing references an entity, and votes and games keep
 * referencing the statistics records. A rebuild is idempotent and may run on any server at any
 * time; dropping the collection loses nothing.
 */
public final class MapEntityProjection {
    public static final String COLLECTION = "map_entities";
    private static final MapDataConsolidator CONSOLIDATOR = new MapDataConsolidator();
    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);

    private MapEntityProjection() {
    }

    /** {@code records}: statistics records read; {@code maps}: entities written. */
    public record Result(int records, int maps, Set<String> ambiguous) {
    }

    public static void ensureIndexes(MongoCollection<?> entities) {
        entities.createIndex(new Document("map_key", 1), new IndexOptions().unique(true)
                .partialFilterExpression(Filters.exists("map_key")).name("map_key_unique"));
        entities.createIndex(new Document("file_names_lower", 1));
    }

    public static Result rebuild(MongoDatabase database) {
        // Taken before the reads: an entity another server writes from data saved after them
        // is stamped later, so the cleanup below cannot delete a map this snapshot missed.
        long now = System.currentTimeMillis();
        var records = database.getCollection("maps", MapData.class).find().into(new ArrayList<>());
        var uploads = database.getCollection("map_uploads", MapUploadRecord.class).find().into(new ArrayList<>());
        MongoCollection<MapEntity> entities = database.getCollection(COLLECTION, MapEntity.class);
        var links = MapKeys.links(uploads);
        var groups = MapKeys.group(records, links);
        int written = 0;
        for (var group : groups.entrySet()) {
            if (write(entities, group.getKey(), group.getValue(), now)) written++;
        }
        // Maps that no longer exist, and the slug-grouped documents of migration v3; only those
        // last written before this snapshot (servers' clocks are assumed to roughly agree).
        entities.deleteMany(and(nin("map_key", new ArrayList<>(groups.keySet())), lt("updated_at", now)));
        return new Result(records.size(), written, links.ambiguous());
    }

    /**
     * Brings up to date only the maps {@code fileNames} belong to, now and before the change
     * that named them (an upload, a removal or a restore of those files). Reads the upload
     * attribution, which has one small row per server and file, and only the statistics
     * records of those maps. Returns the number of maps it looked at.
     *
     * <p>A record whose file name differs only in letter case from every name the maps know
     * is picked up by the next full {@link #rebuild}, which runs at every startup.
     */
    public static int update(MongoDatabase database, Collection<String> fileNames) {
        long now = System.currentTimeMillis();
        var exact = new HashSet<String>();
        var changed = new HashSet<String>();
        for (String name : fileNames) {
            if (!MapKeys.named(name)) continue;
            exact.add(name.trim());
            changed.add(MapKeys.lower(name));
        }
        if (changed.isEmpty()) return 0;
        var uploads = database.getCollection("map_uploads", MapUploadRecord.class).find().into(new ArrayList<>());
        MongoCollection<MapEntity> entities = database.getCollection(COLLECTION, MapEntity.class);
        var links = MapKeys.links(uploads);

        // The maps the files belong to now, and the ones they belonged to before.
        var keys = new LinkedHashSet<String>();
        for (String name : changed) keys.add(MapKeys.keyOfFile(name, links));
        for (MapEntity before : entities.find(in("file_names_lower", new ArrayList<>(changed))).into(new ArrayList<>())) {
            if (before.mapKey == null) continue;
            keys.add(before.mapKey);
            if (before.fileNames != null) exact.addAll(before.fileNames);
        }
        // Every file of those maps: the ones they had, and the ones their posts installed.
        for (MapUploadRecord row : uploads) {
            if (row != null && MapKeys.named(row.fileName) && keys.contains(MapKeys.keyOfFile(row.fileName, links))) {
                exact.add(row.fileName.trim());
            }
        }
        var records = database.getCollection("maps", MapData.class)
                .find(in("file_name", new ArrayList<>(exact))).into(new ArrayList<>());
        var groups = MapKeys.group(records, links);
        for (String key : keys) {
            var group = groups.get(key);
            if (group == null || group.isEmpty()) {
                // No record is left in this map; a newer write by another server stays.
                entities.deleteOne(and(eq("map_key", key), lt("updated_at", now)));
            } else {
                write(entities, key, group, now);
            }
        }
        return keys.size();
    }

    private static boolean write(MongoCollection<MapEntity> entities, String key, List<MapData> records, long now) {
        MapEntity entity = CONSOLIDATOR.consolidate(key, records);
        if (entity == null) return false;
        entity.editModelTime = now;
        try {
            entities.replaceOne(eq("map_key", key), entity, UPSERT);
        } catch (MongoWriteException race) {
            // Another server inserted the same map between our match and insert.
            if (race.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw race;
            entities.replaceOne(eq("map_key", key), entity);
        }
        return true;
    }
}
