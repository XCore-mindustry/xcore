package org.xcore.plugin.database.storage;

import com.google.gson.JsonObject;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.xcore.plugin.concurrent.StorageExecutor;
import org.xcore.plugin.config.TomlSecretsConfig;
import org.xcore.plugin.config.TomlXcoreConfig;
import org.xcore.plugin.database.MongoTransactions;
import org.xcore.plugin.database.repository.BanDataRepository;
import org.xcore.plugin.database.repository.GameDataRepository;
import org.xcore.plugin.database.repository.MuteDataRepository;
import org.xcore.plugin.database.repository.PlayerDataRepository;
import org.xcore.plugin.localization.TranslatorLanguagesProvider;
import org.xcore.plugin.permission.grant.MongoGrantStore;
import org.xcore.plugin.rating.ladder.LadderService;
import org.xcore.plugin.service.AccountMergeService;
import org.xcore.plugin.service.FindService;
import org.xcore.plugin.service.NetworkService;
import org.xcore.plugin.service.PlayerDisplayService;
import org.xcore.plugin.service.TopMenuCacheService;
import org.xcore.plugin.service.moderation.AuditService;
import org.xcore.plugin.service.stats.PlayerTotalsStore;
import org.xcore.plugin.session.SessionService;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The console merge against the shared merge cases in protocol/storage/merges: seeded with a case's
 * before documents, it must leave every collection as the case's after documents say. The bot's
 * test_merge_contract.py runs the Discord merge over the same cases.
 */
@Testcontainers(disabledWithoutDocker = true)
class StorageMergeContractIntegrationTest {
    @Container
    private static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:7.0")
            .withExposedPorts(27017).withCommand("--replSet", "rs0", "--bind_ip_all");

    private static MongoClient client;
    private static final JsonObject COMPARE = StorageContract.schemaAt(StorageContract.ROOT.resolve("merges/compare.json"));

    @BeforeAll
    static void connect() throws Exception {
        var initialized = MONGO.execInContainer("mongosh", "--quiet", "--eval",
                "rs.initiate({_id:'rs0',members:[{_id:0,host:'localhost:27017'}]})");
        assertThat(initialized.getExitCode()).isZero();
        Wait.forLogMessage(".*Transition to primary complete.*", 1).withStartupTimeout(Duration.ofSeconds(40))
                .waitUntilReady(MONGO);
        // Transactions, as on a production replica set: the merge takes its transactional path.
        client = MongoClients.create("mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017)
                + "/?replicaSet=rs0&directConnection=true&serverSelectionTimeoutMS=30000");
    }

    @AfterAll
    static void disconnect() {
        if (client != null) client.close();
    }

    @Test
    void casesAreFound() {
        assertThat(cases()).hasSizeGreaterThanOrEqualTo(10);
    }

    @TestFactory
    Stream<DynamicTest> theConsoleMergeLeavesWhatEachCaseExpects() {
        return cases().stream().map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(), () -> run(dir)));
    }

    private static void run(Path dir) {
        var spec = StorageContract.schemaAt(dir.resolve("case.json"));
        var request = spec.getAsJsonObject("request");
        Assumptions.assumeTrue(request.get("keep_pid").getAsString().equals("target"),
                "the console merge always keeps the target's PID");

        var database = client.getDatabase("storage_merges").withCodecRegistry(StorageContract.CODECS);
        database.drop();
        var before = side(dir, "before");
        before.forEach((collection, docs) -> {
            if (!docs.isEmpty()) database.getCollection(collection, BsonDocument.class).insertMany(docs);
        });

        var storage = new StorageExecutor(1);
        try {
            var result = merges(database, storage).merge(new AccountMergeService.MergeRequest(
                    "#" + request.get("source_pid").getAsInt(), "#" + request.get("target_pid").getAsInt(),
                    "merge contract", null));
            boolean refused = spec.has("refused") && spec.get("refused").getAsBoolean();
            assertThat(result.success()).as("%s: %s", dir.getFileName(), result.message()).isEqualTo(!refused);
        } finally {
            storage.shutdown();
        }

        var after = side(dir, "after");
        var collections = new TreeSet<String>(before.keySet());
        collections.addAll(after.keySet());
        database.listCollectionNames().forEach(collections::add);
        collections.removeAll(COMPARE.getAsJsonObject("not_compared").keySet());
        for (var collection : collections) {
            var expected = after.containsKey(collection) ? after.get(collection) : before.getOrDefault(collection, List.of());
            var actual = database.getCollection(collection, BsonDocument.class).find().into(new ArrayList<>());
            assertSame(dir.getFileName() + "/" + collection, collection, before.getOrDefault(collection, List.of()),
                    expected, actual);
        }
    }

    private static AccountMergeService merges(MongoDatabase database, StorageExecutor storage) {
        var secrets = new TomlSecretsConfig();
        var config = new TomlXcoreConfig();
        config.server.name = "merge-contract";
        var players = new PlayerDataRepository(database, secrets);
        var totals = new PlayerTotalsStore(database, new MongoTransactions(client), storage, config, secrets);
        var games = new GameDataRepository(database, null, secrets, totals, storage, null);
        var sessions = mock(SessionService.class);
        when(sessions.getOrLoadFromDb(anyInt())).thenAnswer(call -> players.findByPid(call.getArgument(0)));
        var find = new FindService(sessions, mock(TranslatorLanguagesProvider.class));
        return new AccountMergeService(client, players, games, new BanDataRepository(database, secrets),
                new MuteDataRepository(database, secrets), new MongoGrantStore(database, secrets), mock(AuditService.class), sessions,
                mock(PlayerDisplayService.class), mock(NetworkService.class), find, mock(TopMenuCacheService.class),
                mock(LadderService.class), config);
    }

    /**
     * Documents match by _id, except those whose ObjectId the merge generated: they match by their
     * other fields. Fields holding the time of the merge are left out on both sides.
     */
    private static void assertSame(String label, String collection, List<BsonDocument> before,
                                   List<BsonDocument> expected, List<BsonDocument> actual) {
        var stamped = new ArrayList<String>();
        var listed = COMPARE.getAsJsonObject("stamped").getAsJsonArray(collection);
        if (listed != null) listed.forEach(path -> stamped.add(path.getAsString()));
        Set<BsonValue> known = new HashSet<>();
        before.forEach(doc -> known.add(doc.get("_id")));

        var wantKept = new TreeMap<String, BsonDocument>();
        var wantNew = new ArrayList<BsonDocument>();
        split(expected, known, stamped, wantKept, wantNew);
        var gotKept = new TreeMap<String, BsonDocument>();
        var gotNew = new ArrayList<BsonDocument>();
        split(actual, known, stamped, gotKept, gotNew);

        assertThat(gotKept.keySet()).as("%s: documents by _id", label).isEqualTo(wantKept.keySet());
        for (var entry : wantKept.entrySet()) {
            assertThat(StorageContract.same(entry.getValue(), gotKept.get(entry.getKey())))
                    .as("%s: _id %s%n expected %s%n but was  %s", label, entry.getKey(), entry.getValue().toJson(),
                            gotKept.get(entry.getKey()).toJson())
                    .isTrue();
        }
        assertThat(gotNew).as("%s: documents the merge created", label).hasSameSizeAs(wantNew);
        var unmatched = new ArrayList<>(gotNew);
        for (var want : wantNew) {
            var match = unmatched.stream().filter(got -> StorageContract.same(want, got)).findFirst();
            assertThat(match).as("%s: no created document matches%n expected %s%n among    %s", label, want.toJson(),
                    unmatched.stream().map(BsonDocument::toJson).toList()).isPresent();
            unmatched.remove(match.get());
        }
    }

    private static void split(List<BsonDocument> docs, Set<BsonValue> known, List<String> stamped,
                              Map<String, BsonDocument> kept, List<BsonDocument> created) {
        for (var original : docs) {
            var doc = original.clone();
            stamped.forEach(path -> remove(doc, path));
            var id = doc.get("_id");
            if (id.isObjectId() && !known.contains(id)) {
                doc.remove("_id");
                created.add(doc);
            } else {
                kept.put(id.toString(), doc);
            }
        }
    }

    private static void remove(BsonDocument doc, String path) {
        int dot = path.indexOf('.');
        if (dot < 0) {
            doc.remove(path);
        } else if (doc.get(path.substring(0, dot)) instanceof BsonDocument inner) {
            remove(inner, path.substring(dot + 1));
        }
    }

    private static Map<String, List<BsonDocument>> side(Path dir, String side) {
        var found = new TreeMap<String, List<BsonDocument>>();
        for (var collection : StorageContract.dirs(dir.resolve(side))) {
            found.put(collection.getFileName().toString(),
                    StorageContract.files(collection).stream().map(StorageContract::read).toList());
        }
        return found;
    }

    private static List<Path> cases() {
        return StorageContract.dirs(StorageContract.ROOT.resolve("merges"));
    }
}
