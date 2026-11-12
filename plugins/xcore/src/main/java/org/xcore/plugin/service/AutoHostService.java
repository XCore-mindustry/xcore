package org.xcore.plugin.service;

import arc.Core;
import arc.Events;
import io.avaje.inject.PreDestroy;
import org.xcore.plugin.event.EventSubscriptions;
import org.xcore.plugin.startup.ShutdownOrder;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Gamemode;
import mindustry.game.Rules;
import mindustry.maps.Map;
import mindustry.maps.MapException;
import mindustry.server.ServerControl;
import org.xcore.plugin.common.PLog;
import org.xcore.plugin.config.TomlXcoreConfig;

@Singleton
public class AutoHostService {
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
        // Next frame: after other plugins' ServerLoadEvent handlers (Patchsthosus registers its maps there)
        // and after ServerControl applies startup commands such as 'port'.
        subscriptions.on(EventType.ServerLoadEvent.class, event -> Core.app.post(this::onServerLoad));
    }

    /** Same steps as the vanilla 'host' command. */
    public boolean onServerLoad() {
        if (!config.server.autoStart || Vars.state.isGame()) {
            return false;
        }

        Gamemode mode = parseGamemode(config.server.autoStartGamemode);
        Map map = Vars.maps.getNextMap(mode, Vars.state.map);
        if (map == null) {
            PLog.warn("Auto-start skipped: no map available for mode '@'.", mode.name());
            return false;
        }

        PLog.info("Auto-start: hosting map '@' in @ mode.", map.plainName(), mode.name());
        if (ServerControl.instance != null) {
            ServerControl.instance.lastMode = mode;
        }
        Core.settings.put("lastServerMode", mode.name());

        try {
            Vars.logic.reset();
            Rules rules = map.applyRules(mode);
            Vars.world.loadMap(map, rules);
            Vars.state.rules = rules;
            Events.fire(new EventType.RulesLoadEvent(rules));
            Vars.logic.play();
            Vars.netServer.openServer();
            return true;
        } catch (MapException e) {
            PLog.err("Auto-start failed to load map '@': @", map.plainName(), e.getMessage());
            return false;
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
