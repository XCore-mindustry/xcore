package org.xcore.plugin.database.storage;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.xcore.plugin.config.TomlSecretsConfig;
import org.xcore.plugin.database.migration.V7__AssignBanIds;
import org.xcore.plugin.database.repository.BanDataRepository;
import org.xcore.plugin.database.repository.MapDataRepository;
import org.xcore.plugin.database.repository.MuteDataRepository;
import org.xcore.plugin.database.repository.PlayerDataRepository;
import org.xcore.plugin.model.BanData;
import org.xcore.plugin.model.MuteData;
import org.xcore.plugin.model.PlayerData;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The plugin's repositories over every stored shape in protocol/storage, and what they write. */
@Testcontainers(disabledWithoutDocker = true)
class StorageRepositoryContractIntegrationTest {
    @Container
    private static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:7.0").withExposedPorts(27017);

    private static MongoClient client;
    private MongoDatabase database;
    private PlayerDataRepository players;
    private BanDataRepository bans;
    private MuteDataRepository mutes;

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
        database = client.getDatabase("storage_contract").withCodecRegistry(StorageContract.CODECS);
        database.drop();
        var config = new TomlSecretsConfig();
        players = new PlayerDataRepository(database, config);
        bans = new BanDataRepository(database, config);
        mutes = new MuteDataRepository(database, config);
    }

    @Test
    void readsEveryStoredShape() {
        for (var path : StorageContract.stored("players")) {
            var stored = alone("players", path);
            var player = players.findByUuid(stored.getString("uuid").getValue());
            assertThat(player).as("%s", path.getFileName()).isNotNull();
            assertThat(player.nickname).isEqualTo(stored.getString("nickname", new org.bson.BsonString("Unknown")).getValue());
        }
        for (var path : StorageContract.stored("bans")) {
            var stored = alone("bans", path);
            var ban = bans.findById(stored.getObjectId("_id").getValue());
            assertThat(ban).as("%s", path.getFileName()).isNotNull();
            assertThat(ban.banId).isEqualTo(stored.containsKey("ban_id") ? stored.getInt32("ban_id").getValue() : null);
        }
        for (var path : StorageContract.stored("mutes")) {
            var stored = alone("mutes", path);
            var mute = mutes.findByUuid(stored.getString("uuid").getValue());
            assertThat(mute).as("%s", path.getFileName()).isNotNull();
            assertThat(mute.expireDate).isEqualTo(Instant.ofEpochMilli(stored.getDateTime("expire_date").getValue()));
        }
    }

    @Test
    void countersContinueEveryStoredSequence() {
        for (var path : StorageContract.stored("counters")) {
            database.drop();
            var stored = seed("counters", path);
            switch (stored.getString("_id").getValue()) {
                case "ban_id" -> assertThat(bans.nextBanId()).isEqualTo(stored.getNumber("seq").intValue() + 1);
                case "player_id" -> {
                    var player = new PlayerData();
                    player.uuid = "uuid-next";
                    players.save(player);
                    assertThat(player.pid).isEqualTo(stored.getNumber("seq").intValue() + 1);
                }
                default -> {
                }
            }
        }
    }

    @Test
    void writesMatchTheSchema() {
        seed("players", StorageContract.ROOT.resolve("fixtures/players/legacy/v1-admin.json"));
        seed("bans", StorageContract.ROOT.resolve("fixtures/bans/legacy/before-v7-no-ban-id.json"));

        var player = new PlayerData();
        player.uuid = "uuid-written";
        player.nickname = "Written";
        players.save(player);
        var linked = players.updateDiscordLink("uuid-written", player.pid, "300000000000000003", "written", 1715000000000L);
        assertThat(players.clearDiscordLink("uuid-written", "300000000000000003", linked.discordLinkedAt)).isTrue();
        assertThat(database.getCollection("players").find(new Document("uuid", "uuid-written")).first().getString("discord_id"))
                .isEmpty();
        players.updateConnectionData("uuid-written", "203.0.113.30", "Renamed");
        players.incrementPlayTime("uuid-written", 5);
        var legacy = players.findByUuid("uuid-old-admin");
        legacy.description = "saved by this jar";
        players.save(legacy);

        bans.save(BanData.builder().uuid("uuid-written").ip("203.0.113.30").name("Written").adminName("Mod")
                .reason("r").expireDate(Instant.parse("2026-06-01T00:00:00Z")).build());
        bans.save(BanData.builder().uuid("uuid-permanent").name("Forever").adminName("Mod").reason("r").build());
        bans.releaseIp(bans.find("uuid-written", null).banId, "203.0.113.30", "uuid-written");
        bans.assignBanIdIfMissing(bans.find("uuid-banned-old", null));
        new V7__AssignBanIds().up(database);
        mutes.save(MuteData.builder().uuid("uuid-written").name("Written").adminName("Mod").reason("r")
                .expireDate(Instant.parse("2026-06-01T00:00:00Z")).build());
        new MapDataRepository(database, new TomlSecretsConfig()).checkMapDecay();
        assertThat(database.getCollection("counters").find(new Document("_id", "last_map_decay")).first()).isNotNull();

        for (var collection : List.of("players", "bans", "mutes", "counters")) {
            var rows = database.getCollection(collection, BsonDocument.class).find().into(new java.util.ArrayList<>());
            assertThat(rows).as(collection).isNotEmpty();
            for (var row : rows) {
                assertThat(StorageContract.errors(collection, row)).as("%s row %s", collection, row.toJson()).isEmpty();
            }
        }
    }

    /** Before and after fixtures of a migration share ids, so each shape is read on its own. */
    private BsonDocument alone(String collection, java.nio.file.Path path) {
        database.getCollection(collection).deleteMany(new BsonDocument());
        return seed(collection, path);
    }

    private BsonDocument seed(String collection, java.nio.file.Path path) {
        var stored = StorageContract.read(path);
        database.getCollection(collection, BsonDocument.class).insertOne(stored);
        return stored;
    }
}
