package org.xcore.plugin.service;

import arc.files.Fi;
import arc.struct.StringMap;
import mindustry.game.Team;
import mindustry.maps.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.xcore.plugin.concurrent.Async;
import org.xcore.plugin.concurrent.DeferredStorageExecutor;
import org.xcore.plugin.config.TomlXcoreConfig;
import org.xcore.plugin.database.repository.EventDataRepository;
import org.xcore.plugin.database.repository.GameDataRepository;
import org.xcore.plugin.database.repository.MapDataRepository;
import org.xcore.plugin.model.EventData;
import org.xcore.plugin.model.GameData;
import org.xcore.plugin.model.MapData;
import org.xcore.testkit.fixtures.HeadlessWorld;
import org.xcore.testkit.fixtures.junit.WithHeadlessWorld;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchStartServiceTest {
    private final DeferredStorageExecutor storage = new DeferredStorageExecutor();
    private final MapDataRepository maps = mock(MapDataRepository.class);
    private final EventDataRepository events = mock(EventDataRepository.class);

    @Test
    @WithHeadlessWorld
    void theMatchStartsBeforeItsMapIsKnownAndTakesItWhenTheLookupAnswers(HeadlessWorld world) {
        var player = world.addPlayer("Early", Team.sharded);
        var games = new GameDataService(config("survival"), mock(GameDataRepository.class));
        MapData record = record();
        when(maps.findOrCreate("Arena", "arena.msav", "Author", "survival")).thenReturn(record);

        service(games, "survival").start(map("Arena"), "survival", "survival");

        GameData game = games.getCurrent();
        assertThat(game).isNotNull();
        assertThat(game.map).isNull();
        assertThat(games.getStats(player.player().uuid())).isNotNull();
        answerStorage();
        assertThat(game.map).isEqualTo(record.id);
        verify(events, never()).findActive();
    }

    @Test
    @WithHeadlessWorld
    void onTheEventServerTheMatchTakesTheRunningEvent(HeadlessWorld world) {
        var games = new GameDataService(config("event"), mock(GameDataRepository.class));
        when(maps.findOrCreate("Arena", "arena.msav", "Author", "survival")).thenReturn(record());
        var event = new EventData();
        event.id = new ObjectId();
        when(events.findActive()).thenReturn(Optional.of(event));

        service(games, "event").start(map("Arena"), "survival", "survival");
        answerStorage();

        assertThat(games.getCurrent().event).isEqualTo(event.id);
        assertThat(games.getCurrent().isEvent).isTrue();
    }

    @Test
    @WithHeadlessWorld
    void aLookupThatAnswersAfterTheNextMatchBeganLeavesItAlone(HeadlessWorld world) {
        var games = new GameDataService(config("survival"), mock(GameDataRepository.class));
        when(maps.findOrCreate("Arena", "arena.msav", "Author", "survival")).thenReturn(record());

        service(games, "survival").start(map("Arena"), "survival", "survival");
        GameData next = games.startGameWithoutMap("survival");
        answerStorage();

        assertThat(next.map).isNull();
    }

    @Test
    @WithHeadlessWorld
    void aFailedLookupLeavesTheMatchRunning(HeadlessWorld world) {
        var games = new GameDataService(config("survival"), mock(GameDataRepository.class));
        when(maps.findOrCreate("Arena", "arena.msav", "Author", "survival"))
                .thenThrow(new IllegalStateException("MongoDB is down"));

        var service = service(games, "survival");
        service.start(map("Arena"), "survival", "survival");
        answerStorage();

        assertThat(games.getCurrent()).isNotNull();
        assertThat(games.getCurrent().map).isNull();
        service.stop();
    }

    private MatchStartService service(GameDataService games, String serverName) {
        var async = new Async(storage.executor(), Runnable::run);
        return new MatchStartService(async, maps, events, games, config(serverName));
    }

    /** Lets storage answer; the main-thread hop runs inline. */
    private void answerStorage() {
        storage.runAll();
    }

    private static MapData record() {
        var record = new MapData("Arena", "arena.msav", "Author", "survival");
        record.id = new ObjectId();
        return record;
    }

    private static Map map(String name) {
        return new Map(new Fi(name.toLowerCase() + ".msav"), 100, 100,
                StringMap.of("name", name, "author", "Author"), true);
    }

    private static TomlXcoreConfig config(String serverName) {
        var config = new TomlXcoreConfig();
        config.server.name = serverName;
        return config;
    }
}
