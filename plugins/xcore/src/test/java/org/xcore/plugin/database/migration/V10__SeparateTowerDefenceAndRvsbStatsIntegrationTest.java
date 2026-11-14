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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class V10__SeparateTowerDefenceAndRvsbStatsIntegrationTest {
    @Container
    private static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:7.0")
            .withExposedPorts(27017);

    private static MongoClient client;
    private MongoDatabase database;

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
        database = client.getDatabase("v10_migration_test");
        database.drop();
    }

    @Test
    void movesOnlyTheirGamesAndQueuesOnlyTheirPlayers() {
        var games = database.getCollection("games_v2");
        games.insertMany(List.of(
                game("td", "tower-defence", "SURVIVAL", true, "a"),
                game("td-other", "tower-defence", "OTHER", false, "a", "b"),
                game("rvsb", "rvsb", "SURVIVAL", true, "c"),
                game("surv", "mini-surv", "SURVIVAL", true, "d")));
        database.getCollection("player_totals").insertOne(new Document("_id", "a").append("status", "ready")
                .append("rebuild", new Document("dirty", false)));

        new V10__SeparateTowerDefenceAndRvsbStats().up(database);

        assertThat(category("td")).isEqualTo("TOWER_DEFENSE");
        assertThat(category("td-other")).isEqualTo("TOWER_DEFENSE");
        assertThat(category("rvsb")).isEqualTo("RVSB");
        assertThat(category("surv")).isEqualTo("SURVIVAL");
        assertThat(games.find(new Document("_id", "td-other")).first().getBoolean("counted_in_stats")).isFalse();

        var totals = database.getCollection("player_totals");
        assertThat(totals.find(new Document("rebuild.dirty", true)).map(d -> d.getString("_id")).into(new java.util.ArrayList<>()))
                .containsExactlyInAnyOrder("a", "b", "c");
        assertThat(totals.find(new Document("_id", "a")).first().getString("status")).isEqualTo("ready");
    }

    private String category(String id) {
        return database.getCollection("games_v2").find(new Document("_id", id)).first().getString("stats_category");
    }

    private static Document game(String id, String server, String category, boolean counted, String... players) {
        return new Document("_id", id).append("server_id", server).append("stats_category", category)
                .append("counted_in_stats", counted)
                .append("player_stats", java.util.Arrays.stream(players).map(uuid -> new Document("uuid", uuid)).toList());
    }
}
