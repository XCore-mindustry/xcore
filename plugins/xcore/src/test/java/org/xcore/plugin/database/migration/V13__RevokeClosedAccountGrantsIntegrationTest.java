package org.xcore.plugin.database.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.BsonDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V13 over its own before fixtures in protocol/storage/migrations/V13 (whose after side the
 * storage contract test checks): what it reports, and that a second run changes nothing.
 */
@Testcontainers(disabledWithoutDocker = true)
class V13__RevokeClosedAccountGrantsIntegrationTest {
    private static final Path BEFORE = Path.of(System.getProperty("xcore.storage.dir", "../../protocol/storage"))
            .resolve("migrations/V13/before");

    @Container
    private static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:7.0").withExposedPorts(27017);

    private static MongoClient client;
    private MongoDatabase database;
    private final V13__RevokeClosedAccountGrants migration = new V13__RevokeClosedAccountGrants();

    @BeforeAll
    static void connect() {
        client = MongoClients.create("mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017));
    }

    @AfterAll
    static void disconnect() {
        if (client != null) client.close();
    }

    @BeforeEach
    void setUp() throws IOException {
        database = client.getDatabase("v13_migration_test");
        database.drop();
        try (var collections = Files.list(BEFORE)) {
            for (var dir : collections.toList()) {
                var docs = files(dir).stream().map(V13__RevokeClosedAccountGrantsIntegrationTest::read).toList();
                database.getCollection(dir.getFileName().toString(), BsonDocument.class).insertMany(docs);
            }
        }
    }

    @Test
    void reportsEachUuidItEmptied() {
        assertThat(migration.apply(database)).containsExactlyInAnyOrder(
                "uuid-old-staff of closed #131: admin (discord:1061673250691752057), command.ban, uuid now held by #134",
                "merged:uuid-synced of closed #132: moderator (discord:1061673250691752058)");
    }

    @Test
    void aSecondRunChangesNothing() {
        migration.apply(database);
        var once = snapshot();

        assertThat(migration.apply(database)).isEmpty();
        assertThat(snapshot()).isEqualTo(once);
    }

    private Map<String, List<BsonDocument>> snapshot() {
        var all = new TreeMap<String, List<BsonDocument>>();
        for (var name : database.listCollectionNames()) {
            all.put(name, database.getCollection(name, BsonDocument.class).find().sort(new org.bson.Document("_id", 1))
                    .into(new ArrayList<>()));
        }
        return all;
    }

    private static List<Path> files(Path dir) {
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BsonDocument read(Path path) {
        try {
            return BsonDocument.parse(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
