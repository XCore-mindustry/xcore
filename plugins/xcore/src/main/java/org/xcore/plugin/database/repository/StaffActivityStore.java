package org.xcore.plugin.database.repository;

import arc.util.Log;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.xcore.plugin.config.TomlSecretsConfig;
import org.xcore.plugin.database.MongoAsync;
import org.xcore.plugin.database.ReactiveMongoStore;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static com.mongodb.client.model.Filters.eq;

/**
 * Minutes staff spend on the servers, one document per Discord account and UTC day. The Discord
 * bot reads it for {@code /staff activity}; every server of the network adds to the same documents.
 */
@Singleton
public class StaffActivityStore {
    public static final String COLLECTION = "staff_activity";

    private final com.mongodb.reactivestreams.client.MongoCollection<Document> collection;
    private final TomlSecretsConfig secretsConfig;

    @Inject
    public StaffActivityStore(MongoDatabase database, ReactiveMongoStore reactiveMongoStore, TomlSecretsConfig secretsConfig) {
        this.collection = reactiveMongoStore.collection(COLLECTION, Document.class);
        this.secretsConfig = secretsConfig;
        database.getCollection(COLLECTION).createIndex(new Document("day", 1).append("discord_id", 1));
    }

    /** Adds one active minute; {@code loggedIn} also counts it as a minute logged in as staff. */
    public void addMinute(String discordId, boolean loggedIn, Instant now) {
        if (secretsConfig.database != null && secretsConfig.database.readOnly) {
            return;
        }
        String day = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        MongoAsync.first(collection.updateOne(
                eq("_id", id(discordId, day)),
                Updates.combine(
                        Updates.setOnInsert("discord_id", discordId),
                        Updates.setOnInsert("day", day),
                        Updates.inc("minutes", 1),
                        Updates.inc("staff_minutes", loggedIn ? 1 : 0)),
                new UpdateOptions().upsert(true)
        )).exceptionally(error -> {
            Log.warn("Staff activity: could not add a minute for @: @", discordId, error.toString());
            return null;
        });
    }

    static String id(String discordId, String day) {
        return discordId + ":" + day;
    }
}
