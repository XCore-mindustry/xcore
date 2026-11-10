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
import java.util.Set;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
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
        var upsert = new ReplaceOptions().upsert(true);
        int written = 0;
        for (var group : groups.entrySet()) {
            MapEntity entity = CONSOLIDATOR.consolidate(group.getKey(), group.getValue());
            if (entity == null) continue;
            entity.editModelTime = now;
            try {
                entities.replaceOne(eq("map_key", group.getKey()), entity, upsert);
            } catch (MongoWriteException race) {
                // Another server inserted the same map between our match and insert.
                if (race.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw race;
                entities.replaceOne(eq("map_key", group.getKey()), entity);
            }
            written++;
        }
        // Maps that no longer exist, and the slug-grouped documents of migration v3; only those
        // last written before this snapshot (servers' clocks are assumed to roughly agree).
        entities.deleteMany(and(nin("map_key", new ArrayList<>(groups.keySet())), lt("updated_at", now)));
        return new Result(records.size(), written, links.ambiguous());
    }
}
