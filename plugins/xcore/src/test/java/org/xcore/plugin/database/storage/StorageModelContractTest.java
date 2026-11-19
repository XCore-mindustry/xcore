package org.xcore.plugin.database.storage;

import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;
import org.xcore.plugin.model.BanData;
import org.xcore.plugin.model.ModelData;
import org.xcore.plugin.model.MuteData;
import org.xcore.plugin.model.PlayerData;
import org.xcore.plugin.model.enums.IdentityDisplayMode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plugin's models against the shared MongoDB contract in protocol/storage. What the codec
 * writes must pass the schema, and every stored shape must decode with the fields the plugin reads
 * intact, so a field renamed on either side fails here.
 */
class StorageModelContractTest {
    private static final String[] MODELS = {"ModelData.java", "Punishment.java", "BanData.java", "PlayerData.java"};

    @Test
    void everyStoredPlayerDecodes() {
        assertStoredShapesDecode("players", PlayerData.class);
    }

    @Test
    void everyStoredBanDecodes() {
        assertStoredShapesDecode("bans", BanData.class);
    }

    @Test
    void everyStoredMuteDecodes() {
        assertStoredShapesDecode("mutes", MuteData.class);
    }

    @Test
    void encodedPlayerMatchesTheSchema() {
        assertWritten("players", PlayerData.class, player());
    }

    @Test
    void encodedBansMatchTheSchema() {
        var ban = punishment(BanData.builder().ip("203.0.113.1").banId(7).pid(42).caseNumber(2001)).build();
        assertWritten("bans", BanData.class, ban);
        assertWritten("bans", BanData.class, BanData.builder().uuid("uuid-permanent").banId(8).build());
    }

    @Test
    void encodedMuteMatchesTheSchema() {
        assertWritten("mutes", MuteData.class, punishment(MuteData.builder().pid(42).caseNumber(2001)).build());
    }

    @Test
    void savingABanOrAMuteKeepsEveryStoredField() {
        // Both repositories save with replaceOne, so a field the model does not carry is erased.
        assertReplaceKeepsEveryField("bans", BanData.class);
        assertReplaceKeepsEveryField("mutes", MuteData.class);
    }

    @Test
    void permanentBanFromThePluginHasNoExpireDate() {
        // Recorded as a disagreement in bans.v1.json: the bot writes expire_date: null instead.
        var encoded = StorageContract.encode(BanData.class, BanData.builder().uuid("uuid").banId(1).build());

        assertThat(encoded.containsKey("expire_date")).isFalse();
    }

    private static <T extends ModelData> void assertStoredShapesDecode(String collection, Class<T> type) {
        var read = StorageContract.pluginFields(collection, "x-read-by", MODELS);
        read.remove("_id");
        List<Path> fixtures = StorageContract.stored(collection);
        assertThat(fixtures).isNotEmpty();
        for (var path : fixtures) {
            var stored = StorageContract.read(path);
            var again = StorageContract.encode(type, StorageContract.decode(type, stored));
            for (var field : read) {
                var value = stored.get(field);
                if (value == null || value.isNull()) continue;
                assertThat(same(value, again.get(field)))
                        .as("%s: %s is %s in the fixture but %s once the plugin decodes it", path.getFileName(), field, value,
                                again.get(field))
                        .isTrue();
            }
        }
    }

    private static <T extends ModelData> void assertReplaceKeepsEveryField(String collection, Class<T> type) {
        for (var path : StorageContract.stored(collection)) {
            var stored = StorageContract.read(path);
            var again = StorageContract.encode(type, StorageContract.decode(type, stored));
            for (var field : stored.keySet()) {
                // _id stays with the document; a null is left out, which every reader takes the same way.
                if (field.equals("_id") || stored.get(field).isNull()) continue;
                assertThat(same(stored.get(field), again.get(field)))
                        .as("%s: the plugin's %s drops %s", path.getFileName(), type.getSimpleName(), field)
                        .isTrue();
            }
        }
    }

    private static <T> void assertWritten(String collection, Class<T> type, T value) {
        BsonDocument document = StorageContract.encode(type, value);
        document.put("_id", new org.bson.BsonObjectId());

        assertThat(StorageContract.errors(collection, document)).as("%s as the plugin writes it", collection).isEmpty();
        assertThat(StorageContract.pluginFields(collection, "x-written-by"))
                .as("the schema names the plugin as a writer of every field it writes")
                .containsAll(document.keySet());
    }

    /** Numbers by value, whatever their width; arrays as sets of members, as the plugin keeps them in sets. */
    private static boolean same(BsonValue expected, BsonValue actual) {
        if (actual == null) return false;
        if (expected.isNumber() && actual.isNumber()) {
            return expected.asNumber().decimal128Value().equals(actual.asNumber().decimal128Value());
        }
        if (expected.isArray() && actual.isArray()) {
            var left = expected.asArray().getValues();
            var right = actual.asArray().getValues();
            return left.stream().allMatch(v -> right.stream().anyMatch(w -> same(v, w)))
                    && right.stream().allMatch(w -> left.stream().anyMatch(v -> same(v, w)));
        }
        if (expected.isDocument() && actual.isDocument()) {
            var left = expected.asDocument();
            var right = actual.asDocument();
            return left.keySet().equals(right.keySet())
                    && left.keySet().stream().allMatch(k -> same(left.get(k), right.get(k)));
        }
        return expected.equals(actual);
    }

    private static <B extends org.xcore.plugin.model.Punishment.PunishmentBuilder<?, ?>> B punishment(B builder) {
        builder.uuid("uuid-contract").name("[red]Contract").adminName("Moderator").adminDiscordId("300000000000000001")
                .reason("griefing").expireDate(Instant.parse("2026-06-01T00:00:00Z"));
        return builder;
    }

    // Every persisted field differs from its default.
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
        return player;
    }
}
