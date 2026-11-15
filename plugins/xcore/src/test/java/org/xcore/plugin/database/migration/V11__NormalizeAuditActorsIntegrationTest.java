package org.xcore.plugin.database.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Sorts.ascending;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class V11__NormalizeAuditActorsIntegrationTest {
    @Container
    private static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:7.0")
            .withExposedPorts(27017);

    private static MongoClient client;
    private MongoDatabase database;
    private final V11__NormalizeAuditActors migration = new V11__NormalizeAuditActors();

    @BeforeAll
    static void connect() {
        client = MongoClients.create("mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017));
    }

    @AfterAll
    static void disconnect() {
        if (client != null) client.close();
    }

    @BeforeEach
    void setUp() {
        database = client.getDatabase("v11_migration_test");
        database.drop();
    }

    @Test
    void renamesBothShapesAndIsIdempotent() {
        database.getCollection(V11__NormalizeAuditActors.COLLECTION).insertMany(List.of(
                // Plugin row before the fix.
                new Document("_id", 1)
                        .append("actor", new Document("_id", "769").append("discord_id", "769").append("type", "PLAYER_ADMIN"))
                        .append("integrity", new Document("dedupe_key", "BAN:769:u:r")),
                // Bot row before the fix.
                new Document("_id", 2)
                        .append("actor", new Document("type", "DISCORD_USER").append("id", "530"))
                        .append("integrity", new Document("dedupeKey", "BAN:530:u:r").append("hash", null)),
                // Both names present: never overwrite the new one.
                new Document("_id", 3)
                        .append("actor", new Document("_id", "old").append("id", "new"))
                        .append("integrity", new Document("dedupeKey", "old").append("dedupe_key", "new"))));

        migration.up(database);
        var once = rows();
        migration.up(database);

        assertThat(rows()).isEqualTo(once);
        var actor1 = once.get(0).get("actor", Document.class);
        assertThat(actor1.getString("id")).isEqualTo("769");
        assertThat(actor1).doesNotContainKey("_id");
        var integrity2 = once.get(1).get("integrity", Document.class);
        assertThat(integrity2.getString("dedupe_key")).isEqualTo("BAN:530:u:r");
        assertThat(integrity2).doesNotContainKey("dedupeKey");
        assertThat(once.get(2).get("actor", Document.class).getString("id")).isEqualTo("new");
        assertThat(once.get(2).get("integrity", Document.class).getString("dedupe_key")).isEqualTo("new");
    }

    private List<Document> rows() {
        return database.getCollection(V11__NormalizeAuditActors.COLLECTION).find().sort(ascending("_id")).into(new ArrayList<>());
    }
}
