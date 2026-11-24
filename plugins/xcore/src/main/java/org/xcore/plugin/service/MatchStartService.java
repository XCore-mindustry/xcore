package org.xcore.plugin.service;

import arc.util.Log;
import io.avaje.inject.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import mindustry.gen.Groups;
import mindustry.maps.Map;
import org.xcore.plugin.concurrent.Async;
import org.xcore.plugin.concurrent.ScheduledTasks;
import org.xcore.plugin.config.TomlXcoreConfig;
import org.xcore.plugin.database.repository.EventDataRepository;
import org.xcore.plugin.database.repository.MapDataRepository;
import org.xcore.plugin.model.EventData;
import org.xcore.plugin.model.GameData;
import org.xcore.plugin.model.MapData;
import org.xcore.plugin.startup.ShutdownOrder;

/**
 * Starts capturing a match on the game thread without waiting for MongoDB: the match begins at
 * once, and its map record (and, on the event server, the running event) is looked up on a
 * storage thread and filled in when it arrives. A failed lookup is retried while the match runs.
 */
@Singleton
public class MatchStartService {
    /** Waits between lookups; the last one repeats until the match ends. */
    private static final float[] RETRY_SECONDS = {5, 30, 60};

    private final Async async;
    private final MapDataRepository maps;
    private final EventDataRepository events;
    private final GameDataService games;
    private final TomlXcoreConfig config;
    private final ScheduledTasks tasks = new ScheduledTasks(MatchStartService.class);

    @Inject
    public MatchStartService(Async async, MapDataRepository maps, EventDataRepository events,
                             GameDataService games, TomlXcoreConfig config) {
        this.async = async;
        this.maps = maps;
        this.events = events;
        this.games = games;
        this.config = config;
    }

    /**
     * Begins the match on {@code map} with every online player and resolves its records.
     *
     * @param modeName the rules' mode name, which classifies the game
     * @param mode     the canonical mode, which keys the map record
     */
    public void start(Map map, String modeName, String mode) {
        GameData game = games.startGameWithoutMap(modeName);
        if (game == null) return;
        Groups.player.each(games::addPlayer);
        if (map == null) return;
        String file = map.file == null ? null : map.file.name();
        resolve(game, new MapKey(map.plainName(), file, map.author(), mode), 0);
    }

    private void resolve(GameData game, MapKey key, int attempt) {
        boolean eventServer = "event".equals(config.server.name);
        async.supply(() -> new Records(
                maps.findOrCreate(key.name(), key.file(), key.author(), key.mode()),
                eventServer ? events.findActive().orElse(null) : null
        )).thenMain((records, error) -> {
            if (games.getCurrent() != game) return;
            if (error == null) {
                games.attachMap(game, records.map(), records.event());
                return;
            }
            float delay = RETRY_SECONDS[Math.min(attempt, RETRY_SECONDS.length - 1)];
            Log.err("[Stats] Cannot look up the map of the running match; retrying in @ s", delay, error);
            tasks.after(delay, () -> {
                if (games.getCurrent() == game) resolve(game, key, attempt + 1);
            });
        });
    }

    @PreDestroy(priority = ShutdownOrder.STOP_PRODUCERS)
    public void stop() {
        tasks.close();
    }

    private record MapKey(String name, String file, String author, String mode) {
    }

    private record Records(MapData map, EventData event) {
    }
}
