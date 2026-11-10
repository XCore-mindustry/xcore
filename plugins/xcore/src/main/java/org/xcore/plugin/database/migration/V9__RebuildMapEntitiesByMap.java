package org.xcore.plugin.database.migration;

import arc.util.Log;
import com.mongodb.client.MongoDatabase;
import jakarta.inject.Singleton;
import org.xcore.plugin.database.repository.MapEntityProjection;
import org.xcore.plugin.map.domain.MapEntity;

import static com.mongodb.client.model.Filters.exists;

/**
 * Migration v9: rebuilds {@code map_entities} with one entity per map (its forum post, else its
 * file), replacing the documents of v3, which grouped different maps by slug. Only the derived
 * collection changes; {@code maps}, votes and games are untouched, so rolling back the plugin
 * needs no data change.
 */
@Singleton
public class V9__RebuildMapEntitiesByMap implements Migration {

    @Override
    public int getVersion() {
        return 9;
    }

    @Override
    public String getDescription() {
        return "Rebuild map entities by map (post or file) instead of slug";
    }

    @Override
    public void up(MongoDatabase database) {
        var entities = database.getCollection(MapEntityProjection.COLLECTION, MapEntity.class);
        // The v3 documents have no key; removing them first lets the unique key index build.
        entities.deleteMany(exists("map_key", false));
        MapEntityProjection.ensureIndexes(entities);
        var result = MapEntityProjection.rebuild(database);
        Log.info("[Migrations v9] Grouped @ map records into @ maps; @ file names were installed by two posts.",
                result.records(), result.maps(), result.ambiguous().size());
    }
}
