package org.xcore.maprenderer;

/**
 * What a render shows besides the map itself.
 *
 * @param darkness  the game's darkness over static walls and outside the map
 * @param buildings buildings and their shadows; without them only the terrain is drawn
 * @param ores      ores stand out: every tile without one is dimmed and greyed
 * @param grid      a line every 10 tiles, a brighter one every 50 with its coordinates
 * @param spawns    enemy spawns with their drop zones (units die inside)
 */
public record RenderOptions(boolean darkness, boolean buildings, boolean ores, boolean grid, boolean spawns) {
    /** As the game shows the map. */
    public static final RenderOptions GAME = new RenderOptions(true, true, false, false, false);

    /** A short, stable name for cache keys; empty for {@link #GAME}. */
    public String key() {
        if (equals(GAME)) return "";
        return (darkness ? "d" : "") + (buildings ? "b" : "") + (ores ? "o" : "") + (grid ? "g" : "") + (spawns ? "s" : "");
    }
}
