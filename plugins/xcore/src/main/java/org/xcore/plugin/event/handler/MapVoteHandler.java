package org.xcore.plugin.event.handler;

import arc.func.Cons;
import org.xcore.plugin.common.PLog;
import arc.util.Strings;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import mindustry.core.GameState;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.maps.Map;
import mindustry.net.Packets;
import mindustry.server.ServerControl;
import org.xcore.plugin.concurrent.Async;
import org.xcore.plugin.database.repository.MapDataRepository;
import org.xcore.plugin.model.MapData;
import org.xcore.plugin.service.GameDataService;
import org.xcore.plugin.service.MapService;
import org.xcore.plugin.service.MatchStartService;
import org.xcore.plugin.ui.menu.MapMenu;
import java.util.concurrent.CompletableFuture;

import static mindustry.Vars.*;

@Singleton
public class MapVoteHandler {

    private final MapDataRepository mapDataRepository;
    private final Provider<MapMenu> mapMenu;
    private final GameDataService gameDataService;
    private final MatchStartService matchStarts;
    private final Async async;
    private final MapService mapService;


    @Inject
    public MapVoteHandler(MapDataRepository mapDataRepository,
                          Provider<MapMenu> mapMenu,
                          GameDataService gameDataService,
                          MatchStartService matchStarts,
                          Async async,
                          MapService mapService) {
        this.mapDataRepository = mapDataRepository;
        this.mapMenu = mapMenu;
        this.gameDataService = gameDataService;
        this.matchStarts = matchStarts;
        this.async = async;
        this.mapService = mapService;
    }

    public Cons<GameOverEvent> getGameOverListener() {
        return event -> {
            if (state.rules.waves) {
                PLog.info("Game over. Reached wave @ with @ players online on map @.",
                        state.wave, Groups.player.size(), Strings.capitalize(state.map.plainName()));
            } else {
                PLog.info("Game over. Team @ is victorious with @ players online on map @.",
                        event.winner.name, Groups.player.size(), Strings.capitalize(state.map.plainName()));
            }

            gameDataService.finishGame(event.winner);

            Map nextMap = mapService.resolveNextMap(ServerControl.instance.lastMode, state.map);

            if (nextMap != null) {
                state.gameOver = true;
                Call.updateGameOver(event.winner);

                String mode = state.rules.mode().name();
                // The next match starts now; its map record is filled in when the lookup returns,
                // and the menu takes that same record instead of looking it up again.
                CompletableFuture<MapData> nextRecord = matchStarts.start(nextMap, state.rules.modeName, mode);
                showGameOverMenu(state.map, nextMap, nextRecord, event.winner, mode);

                ServerControl.instance.play(() -> {
                    String nextMapName = nextMap.plainName();
                    String nextMapFile = nextMap.file == null ? "<null>" : nextMap.file.name();
                    String nextMapAuthor = nextMap.author();
                    String nextMapMode = ServerControl.instance.lastMode == null
                            ? "<null>"
                            : ServerControl.instance.lastMode.name();

                    PLog.info("Loading next map '@' (file='@', author='@', mode='@')",
                            nextMapName, nextMapFile, nextMapAuthor, nextMapMode);

                    try {
                        world.loadMap(nextMap, nextMap.applyRules(ServerControl.instance.lastMode));
                    } catch (Throwable t) {
                        PLog.err("Failed to load next map '@' (file='@', author='@', mode='@')", t,
                                nextMapName, nextMapFile, nextMapAuthor, nextMapMode);
                        throw t;
                    }
                });
            } else {
                netServer.kickAll(Packets.KickReason.gameover);
                state.set(GameState.State.menu);
                net.closeServer();
            }
        };
    }

    /** Looks the current map's record up off the game thread, then shows the menu if the round is still over. */
    private void showGameOverMenu(Map current, Map next, CompletableFuture<MapData> nextRecord, Team winner, String mode) {
        String name = current.plainName(), file = current.file.name(), author = current.author();
        var currentRecord = async.supply(() -> mapDataRepository.findOrCreate(name, file, author, mode)).future();
        async.onMain(currentRecord.thenCombine(nextRecord, (a, b) -> new MapData[]{a, b}), (records, error) -> {
            if (error != null) {
                PLog.err("Cannot look up the maps for the game-over menu", error);
                return;
            }
            if (state.map != current || !state.gameOver) return;
            mapMenu.get().showGameOverMenu(records[0], records[1], winner, next);
        });
    }
}
