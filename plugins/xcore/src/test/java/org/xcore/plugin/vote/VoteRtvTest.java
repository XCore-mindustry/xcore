package org.xcore.plugin.vote;

import arc.files.Fi;
import arc.struct.StringMap;
import mindustry.maps.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.xcore.plugin.concurrent.Async;
import org.xcore.plugin.concurrent.DeferredStorageExecutor;
import org.xcore.plugin.config.TomlSecretsConfig;
import org.xcore.plugin.database.repository.MapDataRepository;
import org.xcore.plugin.event.RoundScope;
import org.xcore.plugin.model.MapData;
import org.xcore.plugin.service.GameDataService;
import org.xcore.plugin.service.GameStateService;
import org.xcore.plugin.service.MatchStartService;
import org.xcore.plugin.session.SessionService;
import org.xcore.testkit.fixtures.HeadlessWorld;
import org.xcore.testkit.fixtures.junit.WithHeadlessWorld;

import static mindustry.Vars.state;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VoteRtvTest {
    private final DeferredStorageExecutor storage = new DeferredStorageExecutor();
    private final MapDataRepository maps = mock(MapDataRepository.class);
    private final MatchStartService matchStarts = mock(MatchStartService.class);

    @Test
    @WithHeadlessWorld
    void aManualVoteCountsTheSkipAndThePopularityOffTheGameThread(HeadlessWorld world) {
        state.map = map("Current");
        MapData current = record("Current");
        MapData target = record("Target");
        String mode = state.rules.mode().name();
        when(maps.findOrCreate("Current", "current.msav", "Author", mode)).thenReturn(current);
        when(maps.findOrCreate("Target", "target.msav", "Author", mode)).thenReturn(target);

        vote(map("Target"), true).success();

        verifyNoInteractions(maps);
        // The match on the new map looks its record up only after these counters.
        verify(matchStarts).lookUpAfter(any());
        storage.runAll();
        verify(maps).markSkip(current.id);
        // The chosen map's own record, looked up by its own file.
        verify(maps).bumpPopularity(target.id, 2.0);
    }

    @Test
    @WithHeadlessWorld
    void aVoteForTheNextMapInLineCountsOnlyTheSkip(HeadlessWorld world) {
        state.map = map("Current");
        MapData current = record("Current");
        when(maps.findOrCreate("Current", "current.msav", "Author", state.rules.mode().name())).thenReturn(current);

        vote(map("Target"), false).success();
        storage.runAll();

        verify(maps).markSkip(current.id);
        verify(maps, never()).bumpPopularity(any(), anyDouble());
        verify(maps, never()).findOrCreate(eq("Target"), anyString(), anyString(), anyString());
    }

    private VoteRtv vote(Map target, boolean manual) {
        var async = new Async(storage.executor(), Runnable::run);
        return new VoteRtv(target, manual, maps, new TomlSecretsConfig(), mock(SessionService.class),
                mock(VoteService.class), mock(GameStateService.class), mock(GameDataService.class),
                matchStarts, async, mock(RoundScope.class), null);
    }

    private static MapData record(String name) {
        var record = new MapData(name, name.toLowerCase() + ".msav", "Author", "survival");
        record.id = new ObjectId();
        return record;
    }

    private static Map map(String name) {
        return new Map(new Fi(name.toLowerCase() + ".msav"), 100, 100,
                StringMap.of("name", name, "author", "Author"), true);
    }
}
