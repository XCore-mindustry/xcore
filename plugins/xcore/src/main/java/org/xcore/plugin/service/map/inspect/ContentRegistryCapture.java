package org.xcore.plugin.service.map.inspect;

import mindustry.Vars;
import mindustry.ctype.MappableContent;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.io.SaveFileReader;
import mindustry.world.Block;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.OverlayFloor;
import mindustry.world.blocks.storage.CoreBlock;
import org.xcore.msav.ContentRegistrySnapshot.Entry;
import org.xcore.msav.ContentRegistrySnapshot;
import org.xcore.msav.MapRules;
import org.xcore.plugin.concurrent.GameThread;

import java.util.HashMap;
import java.util.Map;

/** Copies the running game's content into a {@link ContentRegistrySnapshot}, on the game thread. */
public final class ContentRegistryCapture {
    public static ContentRegistrySnapshot capture() {
        GameThread.require("map inspection registry capture");
        if (Vars.content == null) throw new IllegalStateException("Content registry unavailable");
        var types = new HashMap<Integer, Map<String, Entry>>();
        var blocks = new HashMap<Integer, Entry>();
        // Raw registered content bypasses temporaryMapper, including while an existing preview runs.
        var registered = Vars.content.getContentMap();
        for (int type = 0; type < registered.length; type++) {
            var names = new HashMap<String, Entry>();
            for (int i = 0; i < registered[type].size; i++) {
                if (!(registered[type].get(i) instanceof MappableContent value)) continue;
                Block block = value instanceof Block b ? b : null;
                var entry = new Entry(value.id, value.name, block instanceof Floor,
                        block instanceof OverlayFloor, block != null && block.hasBuilding(),
                        block instanceof CoreBlock, block == null ? 1 : block.size,
                        block == null ? 0 : (block.hasItems ? 1 : 0) | (block.hasPower ? 2 : 0) | (block.hasLiquids ? 4 : 0));
                names.put(entry.name(), entry);
                if (block != null) blocks.put(entry.id(), entry);
            }
            types.put(type, names);
        }
        var aliases = new HashMap<String, String>();
        for (var alias : SaveFileReader.fallback) aliases.put(alias.key, alias.value);
        for (var alias : SaveFileReader.modContentNameMap) aliases.putIfAbsent(alias.key, alias.value);
        var teams = new HashMap<Integer, String>();
        for (Team team : Team.all) teams.put(team.id, team.name);
        Rules rules = new Rules();
        return new ContentRegistrySnapshot(types, blocks, aliases, teams,
                new MapRules(rules.mode().name(), rules.defaultTeam.id, rules.waveTeam.id, rules.waves,
                        rules.waveTimer, rules.spawns.size, rules.dropZoneRadius));
    }

    private ContentRegistryCapture() {}
}
