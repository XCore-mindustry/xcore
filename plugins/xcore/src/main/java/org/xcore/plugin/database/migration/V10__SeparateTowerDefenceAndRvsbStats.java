package org.xcore.plugin.database.migration;

import arc.util.Log;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.xcore.plugin.service.stats.ParticipantOutcomes;
import org.xcore.plugin.service.stats.PlayerTotalsStore;

import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.nin;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;

/**
 * Migration v10: games of the tower defence and Red vs Blue servers were filed under the category their map
 * rules gave them, mostly survival; they get their own categories, and their players' totals are queued for
 * a rebuild. Event games and {@code counted_in_stats} stay as they were recorded.
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
        // The classifier files event games as events on any server, so those keep their category.
        var kept = nin("stats_category", "HEXED", "EVENT");
        long towerDefence = games.updateMany(and(eq("server_id", "tower-defence"), kept),
                set("stats_category", "TOWER_DEFENSE")).getModifiedCount();
        long rvsb = games.updateMany(and(eq("server_id", "rvsb"), kept), set("stats_category", "RVSB")).getModifiedCount();

        var totals = database.getCollection("player_totals");
        var batch = new ArrayList<WriteModel<Document>>(BATCH);
        var options = new UpdateOptions().upsert(true);
        // Stamped with the new rules so a server still on the old jar cannot claim and replay these players without the new categories.
        var update = combine(PlayerTotalsStore.invalidation(), set("rules_version", ParticipantOutcomes.RULES_VERSION));
        long players = 0;
        var pipeline = List.of(new Document("$match", in("stats_category", "TOWER_DEFENSE", "RVSB")),
                new Document("$unwind", "$player_stats"),
                new Document("$group", new Document("_id", "$player_stats.uuid")));
        try (var cursor = games.aggregate(pipeline).allowDiskUse(true).iterator()) {
            while (cursor.hasNext()) {
                if (!(cursor.next().get("_id") instanceof String uuid) || uuid.isBlank()) continue;
                batch.add(new UpdateOneModel<>(eq("_id", uuid), update, options));
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
