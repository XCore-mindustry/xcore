package org.xcore.plugin.model;

import com.mongodb.MongoClientSettings;
import org.bson.BsonDocument;
import org.bson.BsonDocumentWriter;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.junit.jupiter.api.Test;
import org.xcore.plugin.model.enums.IdentityDisplayMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The players document as the plugin writes it: protocol/storage/fixtures/players/valid/plugin-written.json,
 * which the Discord bot and the website also read in their tests.
 */
class PlayerDataDocumentContractTest {
    @Test
    void encodesTheDocumentTheBotReads() throws IOException {
        var codecs = CodecRegistries.fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
                CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build()));
        var document = new BsonDocument();
        codecs.get(PlayerData.class).encode(new BsonDocumentWriter(document), player(), EncoderContext.builder().build());

        var expected = BsonDocument.parse(Files.readString(Path.of(System.getProperty("xcore.storage.dir"),
                "fixtures/players/valid/plugin-written.json")));
        // Mongo assigns the id, and only the link operations write discord_link_generation.
        expected.remove("_id");
        expected.remove("discord_link_generation");
        // Relaxed JSON reads 1 the same whether it was stored as int or long, as the bot sees it.
        var relaxed = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();
        assertThat(BsonDocument.parse(document.toJson(relaxed))).isEqualTo(expected);
    }

    // Every persisted field differs from its default; collections hold one item so their order is fixed.
    private static PlayerData player() {
        var player = new PlayerData();
        player.uuid = "uuid-contract";
        player.pid = 42;
        player.username = "contract_user";
        player.canChangeUsername = true;
        player.identityDisplayMode = IdentityDisplayMode.USERNAME;
        player.ip = "203.0.113.7";
        player.nickname = "[red]Contract";
        player.customNickname = "Custom";
        player.description = "About me";
        player.password = "$2a$10$hash";
        player.deviceTokenHashes = Set.of("token-hash");
        player.deviceTokens = Map.of("token-hash", 1800000000000L);
        player.language = "uk";
        player.translatorLanguage = "en";
        player.globalChatVisible = false;
        player.discordRelayVisible = false;
        player.legacyPvpRating = 1500;
        player.hexedRank = 3;
        player.hexedPoints = 9;
        player.totalPlayTime = 15;
        player.eventVotes = Map.of("event-1", true);
        player.mapVotes = Map.of("map-1", false);
        player.blockedPrivateUuids = Set.of("uuid-blocked");
        player.unlockedBadges = Set.of("translator");
        player.activeBadge = "translator";
        player.badgeSymbolColorMode = "team";
        player.admin = true;
        player.adminSource = "DISCORD";
        player.leaderboard = false;
        player.discordId = "123456789012345678";
        player.discordUsername = "contract#1";
        player.discordLinkedAt = 1700000000001L;
        player.online = true;
        player.onlineSince = 1700000000002L;
        player.onlineServer = "survival";
        player.createdModelTime = 1700000000003L;
        player.editModelTime = 1700000000004L;
        player.deleteModelTime = 1700000000005L;
        player.isVisible = false;
        player.versionModel = 2;
        return player;
    }
}
