package org.xcore.maprenderer;

import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import org.xcore.plugin.service.map.inspect.ContentRegistrySnapshot;
import org.xcore.plugin.service.map.inspect.MapRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The content table written by {@code ContentExport}: the registry the map reader resolves names
 * against, and how each block is drawn. Immutable; one instance serves every render.
 */
public final class RenderContent {
    public static final byte AIR = 0, FLOOR = 1, OVERLAY = 2, STATIC = 3, BUILDING = 4;

    private final ContentRegistrySnapshot registry;
    private final String mindustry;
    // Per block id.
    final byte[] layer;
    final int[] size;
    final boolean[] rotate, wallOre;
    final int[] color;
    final String[][] sprites;
    final String[] teamSprite;
    // Per team id, 0xRRGGBB.
    final int[] teamColor;

    private RenderContent(ContentRegistrySnapshot registry, String mindustry, int blocks, int teams) {
        this.registry = registry;
        this.mindustry = mindustry;
        layer = new byte[blocks];
        size = new int[blocks];
        rotate = new boolean[blocks];
        wallOre = new boolean[blocks];
        color = new int[blocks];
        sprites = new String[blocks][];
        teamSprite = new String[blocks];
        teamColor = new int[teams];
    }

    public ContentRegistrySnapshot registry() { return registry; }
    public String mindustryVersion() { return mindustry; }
    public int blockCount() { return layer.length; }

    public static RenderContent load(Path file) throws IOException {
        JsonValue root = new JsonReader().parse(Files.readString(file));

        var types = new HashMap<Integer, Map<String, ContentRegistrySnapshot.Entry>>();
        var registryBlocks = new HashMap<Integer, ContentRegistrySnapshot.Entry>();
        int blockType = ContentRegistrySnapshot.BLOCK_TYPE;
        for (JsonValue type = root.get("types").child; type != null; type = type.next) {
            int id = Integer.parseInt(type.name);
            var names = new HashMap<String, ContentRegistrySnapshot.Entry>();
            for (JsonValue item = type.child; item != null; item = item.next) {
                var entry = new ContentRegistrySnapshot.Entry(item.getInt("id"), item.getString("name"),
                        item.getBoolean("floor", false), item.getBoolean("overlay", false),
                        item.getBoolean("building", false), item.getBoolean("core", false),
                        item.getInt("size", 1), item.getInt("modules", 0));
                names.put(entry.name(), entry);
                if (id == blockType) registryBlocks.put(entry.id(), entry);
            }
            types.put(id, names);
        }
        var aliases = new HashMap<String, String>();
        for (JsonValue alias = root.get("aliases").child; alias != null; alias = alias.next) {
            aliases.put(alias.name, alias.asString());
        }
        var teamNames = new HashMap<Integer, String>();
        JsonValue teams = root.get("teams");
        int teamCount = 0;
        for (JsonValue team = teams.child; team != null; team = team.next) {
            teamNames.put(team.getInt("id"), team.getString("name"));
            teamCount = Math.max(teamCount, team.getInt("id") + 1);
        }
        JsonValue rules = root.get("rules");
        var defaults = new MapRules(rules.getString("mode"), rules.getInt("defaultTeam"), rules.getInt("waveTeam"),
                rules.getBoolean("waves"), rules.getBoolean("waveTimer"), rules.getInt("spawns"),
                rules.getDouble("dropZoneRadius"));
        var registry = new ContentRegistrySnapshot(types, registryBlocks, aliases, teamNames, defaults);

        JsonValue blocks = root.get("blocks");
        int blockCount = 0;
        for (JsonValue block = blocks.child; block != null; block = block.next) {
            blockCount = Math.max(blockCount, block.getInt("id") + 1);
        }
        var content = new RenderContent(registry, root.getString("mindustry", ""), blockCount, teamCount);
        for (JsonValue team = teams.child; team != null; team = team.next) {
            content.teamColor[team.getInt("id")] = (int) (team.getLong("color") >>> 8) & 0xffffff;
        }
        for (JsonValue block = blocks.child; block != null; block = block.next) {
            int id = block.getInt("id");
            content.layer[id] = switch (block.getString("layer")) {
                case "floor" -> FLOOR;
                case "overlay" -> OVERLAY;
                case "static" -> STATIC;
                case "building" -> BUILDING;
                default -> AIR;
            };
            content.size[id] = block.getInt("size");
            content.rotate[id] = block.getBoolean("rotate");
            content.wallOre[id] = block.getBoolean("wallOre");
            content.color[id] = (int) (block.getLong("color") >>> 8) & 0xffffff;
            content.sprites[id] = block.get("sprites").asStringArray();
            content.teamSprite[id] = block.getString("teamSprite", null);
        }
        return content;
    }
}
