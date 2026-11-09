package org.xcore.msav;

import java.util.HashMap;
import java.util.Map;

/**
 * The content a map's names resolve against, as plain values: no ContentLoader, Block, Rules,
 * Team or Tile. The plugin copies it from the running game; the renderer loads it from the
 * table its build step exported.
 */
public record ContentRegistrySnapshot(Map<Integer, Map<String, Entry>> types, Map<Integer, Entry> blocks,
                                      Map<String, String> aliases, Map<Integer, String> teams, MapRules defaults) {
    public static final int BLOCK_TYPE = ContentTypes.BLOCK;

    public ContentRegistrySnapshot {
        var copy = new HashMap<Integer, Map<String, Entry>>();
        types.forEach((id, entries) -> copy.put(id, Map.copyOf(entries)));
        types = Map.copyOf(copy);
        blocks = Map.copyOf(blocks);
        aliases = Map.copyOf(aliases);
        teams = Map.copyOf(teams);
    }

    public Entry resolve(int type, String name) {
        if (type == BLOCK_TYPE) name = aliases.getOrDefault(name, name);
        return types.getOrDefault(type, Map.of()).get(name);
    }

    public String teamName(int id) { return teams.getOrDefault(id, "team#" + id); }

    /** Module presence for pre-bitmask base formats, copied from Block fields without constructing a Building. */
    public record Entry(int id, String name, boolean floor, boolean overlay, boolean building, boolean core, int size,
                        int modules) {
        public Entry(int id, String name, boolean floor, boolean overlay, boolean building, boolean core, int size) {
            this(id, name, floor, overlay, building, core, size, 0);
        }
    }
}
