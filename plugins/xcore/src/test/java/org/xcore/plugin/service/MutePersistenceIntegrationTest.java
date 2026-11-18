package org.xcore.plugin.service;

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
import org.xcore.plugin.config.TomlSecretsConfig;
import org.xcore.plugin.config.TomlXcoreConfig;
import org.xcore.plugin.database.repository.BanDataRepository;
import org.xcore.plugin.database.repository.GameDataRepository;
import org.xcore.plugin.database.repository.MuteDataRepository;
import org.xcore.plugin.database.repository.PlayerDataRepository;
import org.xcore.plugin.model.AuditAction;
import org.xcore.plugin.model.AuditActor;
import org.xcore.plugin.model.AuditActorType;
import org.xcore.plugin.model.AuditAppendResult;
import org.xcore.plugin.model.AuditOrigin;
import org.xcore.plugin.model.AuditRecord;
import org.xcore.plugin.model.AuditTarget;
import org.xcore.plugin.model.PlayerData;
import org.xcore.plugin.rating.ladder.LadderService;
import org.xcore.plugin.service.moderation.AuditService;
import org.xcore.plugin.service.moderation.ModerationService;
import org.xcore.plugin.session.SessionService;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static com.mongodb.MongoClientSettings.getDefaultCodecRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The plugin's mute writers against a real mutes collection. The Discord bot reads a mute's pid
 * (audit entries and mute logs of an unmute or a shortened mute) and writes its case_number, so a
 * mute the plugin saves must carry both where they apply.
 */
@Testcontainers(disabledWithoutDocker = true)
class MutePersistenceIntegrationTest {
    @Container
    private static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:7.0").withExposedPorts(27017);

    private static MongoClient client;
    private MongoDatabase database;
    private MuteDataRepository mutes;
    private FindService find;
    private SessionService sessions;

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
        database = client.getDatabase("mute_persistence").withCodecRegistry(fromRegistries(
                getDefaultCodecRegistry(), fromProviders(org.bson.codecs.pojo.PojoCodecProvider.builder().automatic(true).build())));
        database.drop();
        mutes = new MuteDataRepository(database, new TomlSecretsConfig());
        find = mock(FindService.class);
        sessions = mock(SessionService.class);
    }

    @Test
    void aMuteIssuedInGameRecordsThePlayersPid() {
        var target = player(42, "uuid-target", "Target");
        when(sessions.getOrLoadFromDb(42)).thenReturn(target);

        var result = moderation().muteById(42, "Admin", null, "spam", Duration.ofMinutes(30));

        assertThat(result.isSuccess()).isTrue();
        assertThat(stored("uuid-target").get("pid")).isEqualTo(42);
    }

    @Test
    void aMuteIssuedInGameReplacesTheCaseLinkOfTheMuteBeforeIt() {
        // The new mute is not the one the case was about; an appeal of it starts afresh.
        database.getCollection("mutes").insertOne(botMute("uuid-target", 42, 2103));
        when(sessions.getOrLoadFromDb(42)).thenReturn(player(42, "uuid-target", "Target"));

        moderation().muteById(42, "Admin", null, "spam again", Duration.ofMinutes(30));

        var stored = stored("uuid-target");
        assertThat(stored.getString("reason")).isEqualTo("spam again");
        assertThat(stored.get("pid")).isEqualTo(42);
        assertThat(stored.containsKey("case_number")).isFalse();
    }

    @Test
    void aMergedMuteKeepsItsCaseAndTakesTheTargetsPid() {
        database.getCollection("mutes").insertOne(botMute("uuid-source", 10, 2103));
        var source = player(10, "uuid-source", "Source");
        var target = player(20, "uuid-target", "Target");
        when(find.playerData("10")).thenReturn(source);
        when(find.playerData("20")).thenReturn(target);

        var result = merges(source, target).merge(new AccountMergeService.MergeRequest("10", "20", "same person", null));

        assertThat(result.success()).as("%s", result).isTrue();
        assertThat(result.muteTransferred()).isTrue();
        var moved = stored("uuid-target");
        assertThat(moved.get("case_number")).isEqualTo(2103);
        assertThat(moved.get("pid")).isEqualTo(20);
    }

    private ModerationService moderation() {
        var config = new TomlXcoreConfig();
        config.server.name = "test-server";
        var audit = mock(AuditService.class);
        when(audit.append(any())).thenReturn(AuditAppendResult.success(AuditRecord.builder()
                .auditId("audit-1")
                .action(AuditAction.MUTE)
                .target(AuditTarget.builder().uuid("uuid-target").nameSnapshot("Target").build())
                .actor(AuditActor.builder().type(AuditActorType.SYSTEM).nameSnapshot("system").build())
                .origin(AuditOrigin.builder().serverId("test-server").build())
                .occurredAt(Instant.now())
                .build()));
        return new ModerationService(mock(PlayerDataRepository.class), mock(BanDataRepository.class), mutes, sessions,
                mock(NetworkService.class), find, mock(TimeService.class), audit, config);
    }

    private AccountMergeService merges(PlayerData... known) {
        var players = mock(PlayerDataRepository.class);
        when(players.saveMergedPlayer(isNull(), any(PlayerData.class), any(PlayerData.class))).thenReturn(true);
        for (var player : known) when(players.findByUuid(player.uuid)).thenReturn(player);
        var config = new TomlXcoreConfig();
        config.server.name = "test-server";
        return new AccountMergeService(players, mock(GameDataRepository.class), mock(BanDataRepository.class), mutes,
                mock(AuditService.class), sessions, mock(PlayerDisplayService.class), mock(NetworkService.class), find,
                mock(TopMenuCacheService.class), mock(LadderService.class), config);
    }

    private Document stored(String uuid) {
        var stored = database.getCollection("mutes").find(new Document("uuid", uuid)).first();
        assertThat(stored).as("mute of %s", uuid).isNotNull();
        return stored;
    }

    /** A mute as the Discord bot writes it from a case. */
    private static Document botMute(String uuid, int pid, int caseNumber) {
        return new Document("case_number", caseNumber).append("uuid", uuid).append("pid", pid).append("name", "Muted")
                .append("admin_name", "Discord Mod").append("admin_discord_id", null).append("reason", "insults")
                .append("expire_date", Date.from(Instant.now().plusSeconds(3600)));
    }

    private static PlayerData player(int pid, String uuid, String nickname) {
        var player = new PlayerData(uuid, true);
        player.pid = pid;
        player.nickname = nickname;
        return player;
    }
}
