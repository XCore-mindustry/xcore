package org.xcore.plugin.vote;

import arc.Core;
import arc.util.Log;
import io.avaje.inject.AssistFactory;
import io.avaje.inject.Assisted;
import jakarta.inject.Inject;
import mindustry.game.Gamemode;
import mindustry.gen.Player;
import mindustry.maps.Map;
import org.xcore.plugin.concurrent.Async;
import org.xcore.plugin.config.TomlSecretsConfig;
import org.xcore.plugin.event.RoundScope;
import org.xcore.plugin.service.GameStateService;
import org.xcore.plugin.database.repository.MapDataRepository;
import org.xcore.plugin.model.MapData;
import org.xcore.plugin.model.enums.FinishReason;
import org.xcore.plugin.service.GameDataService;
import org.xcore.plugin.service.MatchStartService;
import org.xcore.plugin.session.SessionService;

import static com.ospx.flubundle.Bundle.args;

import static mindustry.Vars.state;
import static mindustry.Vars.world;

@AssistFactory(VoteRtvFactory.class)
public class VoteRtv extends VoteSession {
    public final Map target;
    public final boolean isManualSelection;

    private final MapDataRepository mapDataRepository;
    private final TomlSecretsConfig secretsConfig;
    private final SessionService sessionService;
    private final GameStateService gameStateService;
    private final GameDataService gameDataService;
    private final MatchStartService matchStarts;
    private final Async async;
    private final RoundScope roundScope;
    private final org.xcore.plugin.service.map.MapVoteObserverService mapVoteObserverService;

    @Inject
    public VoteRtv(
            @Assisted Map target,
            @Assisted boolean isManualSelection,

            MapDataRepository mapDataRepository,
            TomlSecretsConfig secretsConfig,
            SessionService sessionService,
            VoteService voteService,
            GameStateService gameStateService,
            GameDataService gameDataService,
            MatchStartService matchStarts,
            Async async,
            RoundScope roundScope,
            org.xcore.plugin.service.map.MapVoteObserverService mapVoteObserverService) {
        super(secretsConfig, voteService);
        this.target = target;
        this.isManualSelection = isManualSelection;
        this.mapDataRepository = mapDataRepository;
        this.secretsConfig = secretsConfig;
        this.sessionService = sessionService;
        this.gameStateService = gameStateService;
        this.gameDataService = gameDataService;
        this.matchStarts = matchStarts;
        this.async = async;
        this.roundScope = roundScope;
        this.mapVoteObserverService = mapVoteObserverService;
    }

    @Override
    public void vote(Player player, int sign) {
        super.vote(player, sign);
        sessionService.broadcast("rtv-vote", args(
                "nickname", player.coloredName(),
                "mapName", target.name(),
                "votes", votes(),
                "votesRequired", votesRequired()));

        if (mapVoteObserverService != null) {
            String mapId = target.file != null ? target.file.name() : target.plainName();
            mapVoteObserverService.notifyVoteProgress(mapId, votes(), votesRequired(), remainingSeconds());
        }
    }

    @Override
    public void left(Player player) {
        if (voted.remove(player.id) != 0) {
            sessionService.broadcast("rtv-left", args(
                    "nickname", player.coloredName(),
                    "votes", votes(),
                    "votesRequired", votesRequired()));

            if (mapVoteObserverService != null) {
                String mapId = target.file != null ? target.file.name() : target.plainName();
                mapVoteObserverService.notifyVoteProgress(mapId, votes(), votesRequired(), remainingSeconds());
            }
        }
    }

    @Override
    public void success() {
        stop();
        if (mapVoteObserverService != null) {
            String mapId = target.file != null ? target.file.name() : target.plainName();
            mapVoteObserverService.notifyVoteEnded(mapId);
        }
        sessionService.broadcast("rtv-success", args(
                "mapName", target.name(),
                "mapLoadDelay", secretsConfig.maps.voting.switchDelaySeconds));

        String mode = state.rules.mode().name();
        MapKey skipped = state.map != null && !state.isMenu() ? MapKey.of(state.map) : null;
        MapKey chosen = isManualSelection ? MapKey.of(target) : null;
        if (skipped != null || chosen != null) {
            // Write-behind: nothing on the game thread waits for these counters. One task, so a
            // record is not created twice when the chosen map is the one being skipped.
            async.supply(() -> {
                if (skipped != null) mapDataRepository.markSkip(skipped.record(mapDataRepository, mode).id);
                if (chosen != null) mapDataRepository.bumpPopularity(chosen.record(mapDataRepository, mode).id, 2.0);
                return null;
            }).thenMain((ignored, error) -> {
                if (error != null) Log.err("Cannot record the skip and popularity of an RTV", error);
            });
        }

        // Round-scoped: if the world changes during the delay, this switch is no longer wanted.
        roundScope.after(secretsConfig.maps.voting.switchDelaySeconds, () -> {
                    gameDataService.finishGame(null, FinishReason.RTV);
                    gameStateService.reloadWorld(() ->
                                    world.loadMap(target, target.applyRules(Gamemode.valueOf(Core.settings.getString("lastServerMode")))),
                            // A match the PlayEvent already started is the same one; start it only when none runs.
                            () -> {
                                if (gameDataService.getCurrent() == null) {
                                    matchStarts.start(target, state.rules.modeName, state.rules.mode().name());
                                }
                            });
                });
    }

    @Override
    public boolean endsWithRound() {
        return true;
    }

    @Override
    public void roundEnded() {
        stop();
        if (mapVoteObserverService != null) {
            String mapId = target.file != null ? target.file.name() : target.plainName();
            mapVoteObserverService.notifyVoteEnded(mapId);
        }
    }

    /** The identity of a map record, read on the game thread. */
    private record MapKey(String name, String file, String author) {
        static MapKey of(Map map) {
            return new MapKey(map.plainName(), map.file == null ? null : map.file.name(), map.author());
        }

        MapData record(MapDataRepository maps, String mode) {
            return maps.findOrCreate(name, file, author, mode);
        }
    }

    private int remainingSeconds() {
        return (int) (end.remainingMillis() / 1000L);
    }

    @Override
    public void fail() {
        stop();
        if (mapVoteObserverService != null) {
            String mapId = target.file != null ? target.file.name() : target.plainName();
            mapVoteObserverService.notifyVoteEnded(mapId);
        }
        sessionService.broadcast("rtv-fail", args("mapName", target.name()));
    }

    @Override
    public void cancelByAdmin(Player admin) {
        stop();
        if (mapVoteObserverService != null) {
            String mapId = target.file != null ? target.file.name() : target.plainName();
            mapVoteObserverService.notifyVoteEnded(mapId);
        }
        sessionService.broadcast("rtv-cancelled", args(
                "mapName", target.name(),
                "admin", admin.coloredName()));
    }
}
