package org.xcore.maprenderer;

import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import org.xcore.msav.ContentRegistrySnapshot;
import org.xcore.msav.MapRules;

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
    /** Autotiled blocks, by the rules their Autotiler.blends implementation follows. */
    public static final byte NO_TILING = 0, CONVEYOR = 1, ARMORED_CONVEYOR = 2, DUCT = 3, ARMORED_DUCT = 4,
            CONDUIT = 5, ARMORED_CONDUIT = 6, STACK = 7;

    private final ContentRegistrySnapshot registry;
    private final String mindustry;
    // Per block id.
    final byte[] layer;
    final int[] size;
    final boolean[] rotate, wallOre;
    final int[] color;
    final String[][] sprites;
    final String[] teamSprite;
    // What neighbours, shadows and darkness need, per block id.
    final boolean[] darkens, shadow, outputsItems, hasItems, hasLiquids, outputsLiquid, rotatedOutput, isDuct,
            conveyor, liquidJunction, stack, acceptsItems, noSideBlend, outputRouter;
    // Floors: blend id (-1 for other blocks), edge flags, cache layer, the 3x3 edge sheet as [x * 3 + y].
    final int[] blendId, cache;
    final boolean[] edgeIn, edgeOut;
    final String[][] edges;
    // Static walls: the 2x2 "-large" sheet as [y * 2 + x], or null.
    final String[][] large;
    // Autotiling.
    final byte[] tiling;
    final String[][] tilingTop, tilingBottom;
    final String[] cap;
    final int[] bottomTint;
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
        darkens = new boolean[blocks];
        shadow = new boolean[blocks];
        outputsItems = new boolean[blocks];
        hasItems = new boolean[blocks];
        hasLiquids = new boolean[blocks];
        outputsLiquid = new boolean[blocks];
        rotatedOutput = new boolean[blocks];
        isDuct = new boolean[blocks];
        conveyor = new boolean[blocks];
        liquidJunction = new boolean[blocks];
        stack = new boolean[blocks];
        acceptsItems = new boolean[blocks];
        noSideBlend = new boolean[blocks];
        outputRouter = new boolean[blocks];
        blendId = new int[blocks];
        cache = new int[blocks];
        edgeIn = new boolean[blocks];
        edgeOut = new boolean[blocks];
        edges = new String[blocks][];
        large = new String[blocks][];
        tiling = new byte[blocks];
        tilingTop = new String[blocks][];
        tilingBottom = new String[blocks][];
        cap = new String[blocks];
        bottomTint = new int[blocks];
        teamColor = new int[teams];
    }

    public ContentRegistrySnapshot registry() { return registry; }
    public String mindustryVersion() { return mindustry; }
    public int blockCount() { return layer.length; }

    /** A JSON array of names, nulls included. */
    private static String[] strings(JsonValue array) {
        var result = new String[array.size];
        int i = 0;
        for (JsonValue item = array.child; item != null; item = item.next) result[i++] = item.isNull() ? null : item.asString();
        return result;
    }

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
            content.darkens[id] = block.getBoolean("darkens", false);
            content.shadow[id] = block.getBoolean("shadow", false);
            content.outputsItems[id] = block.getBoolean("outputsItems", false);
            content.hasItems[id] = block.getBoolean("hasItems", false);
            content.hasLiquids[id] = block.getBoolean("hasLiquids", false);
            content.outputsLiquid[id] = block.getBoolean("outputsLiquid", false);
            content.rotatedOutput[id] = block.getBoolean("rotatedOutput", false);
            content.isDuct[id] = block.getBoolean("isDuct", false);
            content.conveyor[id] = block.getBoolean("conveyor", false);
            content.liquidJunction[id] = block.getBoolean("liquidJunction", false);
            content.stack[id] = block.getBoolean("stack", false);
            content.acceptsItems[id] = block.getBoolean("acceptsItems", false);
            content.noSideBlend[id] = block.getBoolean("noSideBlend", false);
            content.outputRouter[id] = block.getBoolean("outputRouter", false);
            content.blendId[id] = block.getInt("blendId", -1);
            content.cache[id] = block.getInt("cache", -1);
            content.edgeIn[id] = block.getBoolean("edgeIn", false);
            content.edgeOut[id] = block.getBoolean("edgeOut", false);
            content.edges[id] = block.has("edges") ? block.get("edges").asStringArray() : null;
            content.large[id] = block.has("large") ? block.get("large").asStringArray() : null;
            content.tiling[id] = switch (block.getString("tiling", "")) {
                case "conveyor" -> CONVEYOR;
                case "armoredConveyor" -> ARMORED_CONVEYOR;
                case "duct" -> DUCT;
                case "armoredDuct" -> ARMORED_DUCT;
                case "conduit" -> CONDUIT;
                case "armoredConduit" -> ARMORED_CONDUIT;
                case "stack" -> STACK;
                default -> NO_TILING;
            };
            content.tilingTop[id] = block.has("top") ? strings(block.get("top")) : null;
            content.tilingBottom[id] = block.has("bottom") ? strings(block.get("bottom")) : null;
            content.cap[id] = block.has("cap") && !block.get("cap").isNull() ? block.getString("cap") : null;
            content.bottomTint[id] = block.has("bottomTint") ? (int) (block.getLong("bottomTint") >>> 8) & 0xffffff : -1;
        }
        return content;
    }
}
