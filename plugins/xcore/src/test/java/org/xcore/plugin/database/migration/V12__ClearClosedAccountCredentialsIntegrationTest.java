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
import org.xcore.plugin.config.TomlSecretsConfig;
import org.xcore.plugin.database.repository.PlayerDataRepository;

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
 * V12 over its own before fixtures in protocol/storage/migrations/V12 (whose after side the
 * storage contract test checks): what it reports, and that a second run changes nothing.
 */
@Testcontainers(disabledWithoutDocker = true)
class V12__ClearClosedAccountCredentialsIntegrationTest {
    private static final Path BEFORE = Path.of(System.getProperty("xcore.storage.dir", "../../protocol/storage"))
            .resolve("migrations/V12/before");

    @Container
    private static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:7.0").withExposedPorts(27017);

    private static MongoClient client;
    private MongoDatabase database;
    private final V12__ClearClosedAccountCredentials migration = new V12__ClearClosedAccountCredentials();

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
        database = client.getDatabase("v12_migration_test");
        database.drop();
        // The plugin's own indexes, the unique case-insensitive username among them.
        new PlayerDataRepository(database, new TomlSecretsConfig());
        try (var collections = Files.list(BEFORE)) {
            for (var dir : collections.toList()) {
                var docs = files(dir).stream().map(V12__ClearClosedAccountCredentialsIntegrationTest::read).toList();
                database.getCollection(dir.getFileName().toString(), BsonDocument.class).insertMany(docs);
            }
        }
    }

    @Test
    void reportsWhatItClearedAndWhereTheNamesWent() {
        var report = migration.apply(database);

        assertThat(report.accounts()).isEqualTo(6);
        assertThat(report.fields()).containsEntry("username", 6).containsEntry("password_hash", 6)
                .containsEntry("device_tokens", 6).containsEntry("device_token_hashes", 6)
                .containsEntry("can_change_username", 6).containsEntry("is_admin", 2)
                .containsEntry("admin_source", 2).containsEntry("discord_id", 1).containsEntry("discord_username", 1);
        assertThat(report.usernamesMoved()).containsExactlyInAnyOrder(
                "#101 (merged:uuid-alt) -> #102 (uuid-survivor): old_alt",
                "#104 (merged:uuid-redirected) -> #103 (uuid-swapped): redirected_alt");
        assertThat(report.usernamesDropped()).hasSize(4).anyMatch(line -> line.contains("lone_alt"))
                .anyMatch(line -> line.contains("second_alt")).anyMatch(line -> line.contains("first_twin"));
        assertThat(report.copiedTokens()).containsExactly("#102 (uuid-survivor): 1 from #101 (merged:uuid-alt)");
    }

    @Test
    void aSecondRunChangesNothing() {
        migration.apply(database);
        var once = snapshot();

        var again = migration.apply(database);

        assertThat(again.changedNothing()).as("%s", again).isTrue();
        assertThat(again.fields()).isEmpty();
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
