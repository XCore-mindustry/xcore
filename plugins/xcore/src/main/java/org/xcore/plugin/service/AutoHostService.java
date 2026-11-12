package org.xcore.plugin.service;

import arc.Core;
import arc.Events;
import arc.struct.ObjectSet;
import io.avaje.inject.PreDestroy;
import org.xcore.plugin.event.EventSubscriptions;
import org.xcore.plugin.startup.ShutdownOrder;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.game.EventType;
import mindustry.game.Gamemode;
import mindustry.game.Rules;
import mindustry.maps.Map;
import mindustry.maps.MapException;
import mindustry.net.Administration;
import mindustry.server.ServerControl;
import org.xcore.plugin.common.PLog;
import org.xcore.plugin.config.TomlXcoreConfig;

@Singleton
public class AutoHostService {
    /** Maps tried before giving up; a broken map no longer blocks auto-start on its own. */
    static final int MAX_ATTEMPTS = 5;

    private final EventSubscriptions subscriptions = new EventSubscriptions();

    private final TomlXcoreConfig config;
    private boolean initialized = false;

    @Inject
    public AutoHostService(TomlXcoreConfig config) {
        this.config = config;
    }

    public void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        // Run after the remaining ServerLoadEvent listeners (map converters such as Patchsthosus register
        // their maps there) and after ServerControl applies command-line/startup commands like 'port'.
        subscriptions.on(EventType.ServerLoadEvent.class, event -> Core.app.post(this::onServerLoad));
    }

    public boolean onServerLoad() {
        if (!config.server.autoStart) {
            return false;
        }

        if (Vars.state.isGame()) {
            PLog.warn("Auto-start skipped: server is already hosting a game.");
            return false;
        }

        Gamemode preset = parseGamemode(config.server.autoStartGamemode);
        ObjectSet<Map> tried = new ObjectSet<>();
        Map previous = Vars.state.map;
        int failures = 0;

        // The shuffle is random, so draw a few extra times to get past maps that already failed.
        for (int draw = 0; draw < MAX_ATTEMPTS * 3 && failures < MAX_ATTEMPTS; draw++) {
            Map candidate = Vars.maps.getNextMap(preset, previous);
            if (candidate == null) {
                break;
            }
            if (!tried.add(candidate)) {
                continue;
            }
            previous = candidate;
            switch (host(candidate, preset)) {
                case HOSTED:
                    return true;
                case SOCKET_FAILED:
                    // Another map cannot fix a port that will not open.
                    return false;
                case MAP_FAILED:
                    failures++;
                    break;
            }
        }

        if (tried.isEmpty()) {
            PLog.warn("Auto-start skipped: no map available to host for mode '@'.", preset.name());
        } else {
            PLog.err("Auto-start gave up after @ map(s) failed to load in @ mode.", tried.size, preset.name());
        }
        return false;
    }

    private enum HostResult { HOSTED, MAP_FAILED, SOCKET_FAILED }

    private HostResult host(Map result, Gamemode preset) {
        PLog.info("Auto-start: hosting map '@' in @ mode...", result.plainName(), preset.name());

        if (ServerControl.instance != null) {
            ServerControl.instance.cancelPlayTask();
            ServerControl.instance.lastMode = preset;
        }

        Vars.logic.reset();
        Core.settings.put("lastServerMode", preset.name());

        try {
            Rules rules = result.applyRules(preset);
            Vars.world.loadMap(result, rules);
            Vars.state.rules = rules;
            Events.fire(new EventType.RulesLoadEvent(rules));
            Vars.logic.play();
            Vars.netServer.openServer();

            if (!Vars.state.isGame() || (Vars.net != null && !Vars.net.server())) {
                PLog.err("Auto-start failed: server socket could not be opened on port @.", Administration.Config.port.num());
                cleanupFailedHost();
                return HostResult.SOCKET_FAILED;
            }

            PLog.info("Auto-start: map '@' loaded and server opened on port @.",
                    result.plainName(),
                    Administration.Config.port.num());
            return HostResult.HOSTED;
        } catch (MapException e) {
            cleanupFailedHost();
            PLog.warn("Auto-start could not load map '@': @; trying another map.",
                    e.map != null ? e.map.plainName() : result.plainName(),
                    e.getMessage());
            return HostResult.MAP_FAILED;
        } catch (Throwable t) {
            cleanupFailedHost();
            PLog.err("Auto-start failed to host map '" + result.plainName() + "'; trying another map.", t);
            return HostResult.MAP_FAILED;
        }
    }

    private void cleanupFailedHost() {
        if (Vars.state != null && Vars.state.isGame()) {
            Vars.state.set(GameState.State.menu);
        }
    }

    public static Gamemode parseGamemode(String name) {
        if (name == null || name.isBlank()) {
            return Gamemode.survival;
        }
        try {
            Gamemode mode = Gamemode.valueOf(name.trim().toLowerCase());
            if (mode.hidden) {
                PLog.warn("Gamemode '@' is hidden/not playable on server, falling back to survival", name);
                return Gamemode.survival;
            }
            return mode;
        } catch (IllegalArgumentException e) {
            PLog.warn("Unknown gamemode '@', falling back to survival", name);
            return Gamemode.survival;
        }
    }

    @PreDestroy(priority = ShutdownOrder.STOP_PRODUCERS)
    public void shutdownEventSubscriptions() {
        subscriptions.close();
    }
}
