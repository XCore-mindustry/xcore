package org.xcore.plugin.database.storage;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.BsonDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.xcore.plugin.database.migration.Migration;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migrations are part of the shared MongoDB contract. A migration that changes a collection with a
 * schema in protocol/storage must ship protocol/storage/migrations/V&lt;n&gt;/before and after fixtures;
 * the bot's and the website's contract tests read both, since servers on the previous jar keep
 * writing the old shape until they restart.
 */
@Testcontainers(disabledWithoutDocker = true)
class StorageMigrationContractIntegrationTest {
    /** The last migration written before the contract existed; later ones must bring fixtures. */
    private static final int BASELINE = 11;
    private static final Pattern MIGRATION = Pattern.compile("V(\\d+)__\\w+\\.class");

    @Container
    private static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:7.0").withExposedPorts(27017);

    private static MongoClient client;

    @BeforeAll
    static void connect() {
        client = MongoClients.create("mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017));
    }

    @AfterAll
    static void disconnect() {
        if (client != null) client.close();
    }

    @Test
    void migrationsAreFound() throws Exception {
        assertThat(migrations()).hasSizeGreaterThanOrEqualTo(BASELINE);
    }

    @Test
    void aMigrationThatChangesASharedCollectionShipsFixtures() throws Exception {
        for (var migration : migrations()) {
            if (migration.getVersion() <= BASELINE) continue;
            for (int variant = 0; variant < variants(); variant++) {
                var database = fresh();
                var before = new TreeMap<String, List<BsonDocument>>();
                for (var collection : collections()) {
                    seedStoredShapes(database, collection, variant);
                    before.put(collection, rows(database, collection));
                }
                migration.up(database);
                for (var collection : collections()) {
                    if (rows(database, collection).equals(before.get(collection))) continue;
                    boolean shipped = !StorageContract.files(fixtures(migration, "before", collection)).isEmpty()
                            && Files.isDirectory(fixtures(migration, "after", collection));
                    assertThat(shipped)
                            .as("V%d changes %s: add protocol/storage/migrations/V%d/before/%s and after/%s fixtures",
                                    migration.getVersion(), collection, migration.getVersion(), collection, collection)
                            .isTrue();
                }
            }
        }
    }

    @Test
    void everyMigrationTurnsItsBeforeFixturesIntoItsAfterFixtures() throws Exception {
        for (var migration : migrations()) {
            var dir = StorageContract.ROOT.resolve("migrations/V" + migration.getVersion());
            if (!Files.isDirectory(dir)) continue;
            var database = fresh();
            var touched = new HashSet<String>();
            for (var side : List.of("before", "after")) {
                if (!Files.isDirectory(dir.resolve(side))) continue;
                try (var collections = Files.list(dir.resolve(side))) {
                    collections.forEach(c -> touched.add(c.getFileName().toString()));
                }
            }
            for (var collection : touched) {
                for (var path : StorageContract.files(fixtures(migration, "before", collection))) {
                    database.getCollection(collection, BsonDocument.class).insertOne(StorageContract.read(path));
                }
            }

            migration.up(database);

            for (var collection : touched) {
                // No after directory: the collection comes out unchanged. An empty one: no documents remain.
                var after = fixtures(migration, Files.isDirectory(fixtures(migration, "after", collection)) ? "after" : "before",
                        collection);
                var expected = StorageContract.files(after).stream().map(StorageContract::read).sorted(BY_ID).toList();
                assertThat(rows(database, collection)).as("V%d: %s", migration.getVersion(), collection)
                        .isEqualTo(expected);
                if (collections().contains(collection)) {
                    for (var row : expected) {
                        assertThat(StorageContract.errors(collection, row)).as("V%d after: %s", migration.getVersion(), row)
                                .isEmpty();
                    }
                }
            }
        }
    }

    private static final Comparator<BsonDocument> BY_ID = Comparator.comparing(d -> d.get("_id").toString());

    private static Path fixtures(Migration migration, String side, String collection) {
        return StorageContract.ROOT.resolve("migrations/V" + migration.getVersion() + "/" + side + "/" + collection);
    }

    private static MongoDatabase fresh() {
        var database = client.getDatabase("storage_migrations").withCodecRegistry(StorageContract.CODECS);
        database.drop();
        return database;
    }

    private static List<BsonDocument> rows(MongoDatabase database, String collection) {
        return database.getCollection(collection, BsonDocument.class).find().into(new ArrayList<>())
                .stream().sorted(BY_ID).toList();
    }

    /**
     * The stored shapes of a collection that share an _id (counters are keyed by name, and a migration's
     * before and after fixtures share ids) cannot sit side by side, so variant n seeds the n-th shape of
     * each id, or its last one.
     */
    private static void seedStoredShapes(MongoDatabase database, String collection, int variant) {
        for (var shapes : shapesById(collection).values()) {
            database.getCollection(collection, BsonDocument.class).insertOne(shapes.get(Math.min(variant, shapes.size() - 1)));
        }
    }

    private static int variants() throws IOException {
        int most = 1;
        for (var collection : collections()) {
            for (var shapes : shapesById(collection).values()) most = Math.max(most, shapes.size());
        }
        return most;
    }

    private static Map<String, List<BsonDocument>> shapesById(String collection) {
        var shapes = new LinkedHashMap<String, List<BsonDocument>>();
        for (var path : StorageContract.stored(collection)) {
            var document = StorageContract.read(path);
            shapes.computeIfAbsent(document.get("_id").toString(), id -> new ArrayList<>()).add(document);
        }
        return shapes;
    }

    private static List<String> collections() throws IOException {
        var names = new ArrayList<String>();
        for (var path : StorageContract.files(StorageContract.ROOT.resolve("schemas"))) {
            var schema = StorageContract.schemaAt(path);
            if (schema.has("x-collection")) names.add(schema.get("x-collection").getAsString());
        }
        return names;
    }

    /** Every migration the plugin ships, found the way the plugin's injector registers them: by class. */
    private static List<Migration> migrations() throws IOException, URISyntaxException, ReflectiveOperationException {
        var pkg = Migration.class.getPackageName();
        var dir = Path.of(Migration.class.getResource(Migration.class.getSimpleName() + ".class").toURI()).getParent();
        var found = new TreeMap<Integer, Migration>();
        try (var classes = Files.list(dir)) {
            for (var file : classes.map(p -> p.getFileName().toString()).toList()) {
                var match = MIGRATION.matcher(file);
                if (!match.matches()) continue;
                var type = Class.forName(pkg + "." + file.replace(".class", ""));
                var migration = (Migration) type.getDeclaredConstructor().newInstance();
                assertThat(migration.getVersion()).as(file).isEqualTo(Integer.parseInt(match.group(1)));
                found.put(migration.getVersion(), migration);
            }
        }
        return List.copyOf(found.values());
    }
}
