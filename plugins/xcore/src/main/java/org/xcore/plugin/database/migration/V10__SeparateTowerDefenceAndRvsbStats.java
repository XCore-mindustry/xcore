package org.xcore.plugin.database.migration;

import arc.util.Log;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.xcore.plugin.service.stats.PlayerTotalsStore;

import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Updates.set;

/**
 * Migration v10: games of the tower defence and Red vs Blue servers were filed under the category their map
 * rules gave them, mostly survival; they get their own categories, and their players' totals are queued for
 * a rebuild. {@code counted_in_stats} stays as it was recorded.
 */
@Singleton
public class V10__SeparateTowerDefenceAndRvsbStats implements Migration {
    private static final int BATCH = 500;

    @Override
    public int getVersion() {
        return 10;
    }

    @Override
    public String getDescription() {
        return "Give tower defence and Red vs Blue games their own stats categories";
    }

    @Override
    public void up(MongoDatabase database) {
        var games = database.getCollection("games_v2");
        long towerDefence = games.updateMany(eq("server_id", "tower-defence"),
                set("stats_category", "TOWER_DEFENSE")).getModifiedCount();
        long rvsb = games.updateMany(eq("server_id", "rvsb"), set("stats_category", "RVSB")).getModifiedCount();

        var totals = database.getCollection("player_totals");
        var batch = new ArrayList<WriteModel<Document>>(BATCH);
        var options = new UpdateOptions().upsert(true);
        long players = 0;
        var pipeline = List.of(new Document("$match", in("server_id", "tower-defence", "rvsb")),
                new Document("$unwind", "$player_stats"),
                new Document("$group", new Document("_id", "$player_stats.uuid")));
        try (var cursor = games.aggregate(pipeline).allowDiskUse(true).iterator()) {
            while (cursor.hasNext()) {
                if (!(cursor.next().get("_id") instanceof String uuid) || uuid.isBlank()) continue;
                batch.add(new UpdateOneModel<>(eq("_id", uuid), PlayerTotalsStore.invalidation(), options));
                players++;
                if (batch.size() == BATCH) {
                    totals.bulkWrite(batch, new BulkWriteOptions().ordered(false));
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) totals.bulkWrite(batch, new BulkWriteOptions().ordered(false));
        Log.info("[Migrations v10] Moved @ tower defence and @ Red vs Blue games; queued @ players for a rebuild.",
                towerDefence, rvsb, players);
    }
}
