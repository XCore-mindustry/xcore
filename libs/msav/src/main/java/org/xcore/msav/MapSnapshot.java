package org.xcore.msav;

import java.util.List;
import java.util.Map;

/** Detached values in row-major (x + y * width) order; arrays are never exposed. */
public final class MapSnapshot {
    private final Metadata metadata;
    private final ContentRegistrySnapshot registry;
    private final int[] floors, overlays, blocks, teams;
    private final byte[] overlayData;
    private final List<Core> cores;
    private final List<Placed> buildings;
    private final List<String> unknownContent;
    private final boolean tilesAvailable;

    public MapSnapshot(Metadata metadata, ContentRegistrySnapshot registry, int[] floors, int[] overlays,
                       int[] blocks, int[] teams, List<Core> cores, List<String> unknownContent, boolean tilesAvailable) {
        this(metadata, registry, floors, overlays, blocks, teams, cores, List.of(), unknownContent, tilesAvailable);
    }

    public MapSnapshot(Metadata metadata, ContentRegistrySnapshot registry, int[] floors, int[] overlays,
                       int[] blocks, int[] teams, List<Core> cores, List<Placed> buildings, List<String> unknownContent,
                       boolean tilesAvailable) {
        this(metadata, registry, floors, overlays, blocks, teams, new byte[floors.length], cores, buildings, unknownContent,
                tilesAvailable);
    }

    public MapSnapshot(Metadata metadata, ContentRegistrySnapshot registry, int[] floors, int[] overlays,
                       int[] blocks, int[] teams, byte[] overlayData, List<Core> cores, List<Placed> buildings,
                       List<String> unknownContent, boolean tilesAvailable) {
        this.metadata = metadata;
        this.registry = registry;
        int expected = tilesAvailable ? Math.multiplyExact(metadata.width(), metadata.height()) : 0;
        if (floors.length != expected || overlays.length != expected || blocks.length != expected || teams.length != expected
                || overlayData.length != expected)
            throw new IllegalArgumentException("Snapshot dimensions disagree");
        this.floors = floors.clone();
        this.overlays = overlays.clone();
        this.blocks = blocks.clone();
        this.teams = teams.clone();
        this.overlayData = overlayData.clone();
        this.cores = List.copyOf(cores);
        this.buildings = List.copyOf(buildings);
        this.unknownContent = List.copyOf(unknownContent);
        this.tilesAvailable = tilesAvailable;
    }

    public Metadata metadata() { return metadata; }
    public ContentRegistrySnapshot registry() { return registry; }
    public boolean tilesAvailable() { return tilesAvailable; }
    public int tileCount() { return floors.length; }
    public int floor(int index) { return floors[index]; }
    public int overlay(int index) { return overlays[index]; }
    public int block(int index) { return blocks[index]; }
    public int team(int index) { return teams[index]; }
    /** The overlay's byte of tile data, unsigned: the letter of a character or rune overlay. */
    public int overlayData(int index) { return overlayData[index] & 0xff; }
    public List<Core> cores() { return cores; }
    /** Every placed building and multiblock centre, in file order, as the engine keeps it. */
    public List<Placed> buildings() { return buildings; }
    public List<String> unknownContent() { return unknownContent; }

    public Map<String, Integer> coresByTeam() {
        var result = new java.util.TreeMap<String, Integer>();
        for (Core core : cores) result.merge(registry.teamName(core.team()), 1, Integer::sum);
        return Map.copyOf(result);
    }

    public int spawnCount() {
        var spawn = registry.resolve(ContentRegistrySnapshot.BLOCK_TYPE, "spawn");
        if (spawn == null) return 0;
        int count = 0;
        for (int overlay : overlays) if (overlay == spawn.id()) count++;
        return count;
    }

    public record Core(int x, int y, int size, int team) {}
    /** A building or multiblock centre; {@code rotation} is 0..3 counter-clockwise from +x, 0 without an entity. */
    public record Placed(int x, int y, int block, int team, int rotation) {}
    public record Metadata(String name, String author, String description, int width, int height, int saveVersion,
                           Integer build, MapRules rules, List<String> mods, String sha256, int sizeBytes,
                           int dataPatches) {
        public Metadata { mods = List.copyOf(mods); }
    }
}
