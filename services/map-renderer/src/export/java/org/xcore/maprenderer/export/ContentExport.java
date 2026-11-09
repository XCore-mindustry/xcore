package org.xcore.maprenderer.export;

import arc.Core;
import arc.Settings;
import arc.files.Fi;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData;
import arc.util.serialization.JsonWriter;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.ctype.ContentType;
import mindustry.ctype.MappableContent;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.io.SaveFileReader;
import mindustry.world.Block;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.OverlayFloor;
import mindustry.world.blocks.environment.Prop;
import mindustry.world.blocks.environment.StaticWall;
import mindustry.world.blocks.storage.CoreBlock;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipFile;

/**
 * Build step: loads Mindustry's content headlessly and writes what the renderer needs, so the
 * renderer itself never loads Mindustry.
 *
 * <ul>
 *   <li>{@code content.json}: every mappable content name and id (the same values the plugin's
 *       {@code ContentRegistrySnapshot.capture()} copies), plus, per block, how it is drawn: its
 *       sprite names, size, rotation, team region and fallback colour.</li>
 *   <li>{@code sprites.pack}: only those sprites, cut from the client's atlas, untrimmed, and
 *       box-filtered to 32, 16, 8 and 4 px per tile. One deflated section per scale, so the
 *       renderer inflates only the scale it draws at.</li>
 * </ul>
 *
 * <p>Usage: {@code ContentExport <Mindustry client jar> <output dir>}.
 */
public final class ContentExport {
    static final int[] SCALES = {32, 16, 8, 4};

    public static void main(String[] args) throws Exception {
        Path clientJar = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Files.createDirectories(out);

        Core.settings = new Settings();
        Vars.headless = true;
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();

        Path atlasDir = Files.createTempDirectory("mindustry-atlas");
        int[] blockColors;
        try (var zip = new ZipFile(clientJar.toFile())) {
            for (var entry : java.util.Collections.list(zip.entries())) {
                String name = entry.getName();
                if (!name.startsWith("sprites/sprites") && !name.equals("sprites/block_colors.png")) continue;
                try (var input = zip.getInputStream(entry)) {
                    Files.copy(input, atlasDir.resolve(name.substring("sprites/".length())));
                }
            }
        }
        BufferedImage colors = ImageIO.read(atlasDir.resolve("block_colors.png").toFile());
        blockColors = new int[colors.getWidth()];
        for (int x = 0; x < blockColors.length; x++) blockColors[x] = colors.getRGB(x, 0);

        var atlas = new TextureAtlasData(new Fi(atlasDir.resolve("sprites.aatls").toFile()),
                new Fi(atlasDir.toFile()), false);
        var regions = new HashMap<String, TextureAtlasData.Region>();
        for (var region : atlas.getRegions()) regions.putIfAbsent(region.name, region);

        var wanted = new LinkedHashSet<String>();
        String json = contentJson(regions, wanted, blockColors);
        Files.writeString(out.resolve("content.json"), json);

        var pages = new HashMap<Fi, BufferedImage>();
        try (OutputStream file = Files.newOutputStream(out.resolve("sprites.pack"))) {
            writePack(new DataOutputStream(file), wanted, regions, pages);
        }
        System.out.printf("Exported %d sprites from %d atlas regions to %s%n", wanted.size(), regions.size(), out);
    }

    private static String contentJson(Map<String, TextureAtlasData.Region> atlas, Set<String> wanted, int[] blockColors) {
        var text = new StringWriter();
        var json = new JsonWriter(text);
        json.setOutputType(JsonWriter.OutputType.json);
        try {
            json.object();
            json.set("mindustry", System.getProperty("mindustry.version", ""));
            json.set("contentTypes", ContentType.all.length);

            json.object("types");
            var registered = Vars.content.getContentMap();
            for (int type = 0; type < registered.length; type++) {
                json.array(Integer.toString(type));
                for (int i = 0; i < registered[type].size; i++) {
                    if (!(registered[type].get(i) instanceof MappableContent value)) continue;
                    Block block = value instanceof Block b ? b : null;
                    json.object();
                    json.set("id", value.id);
                    json.set("name", value.name);
                    if (block != null) {
                        json.set("floor", block instanceof Floor);
                        json.set("overlay", block instanceof OverlayFloor);
                        json.set("building", block.hasBuilding());
                        json.set("core", block instanceof CoreBlock);
                        json.set("size", block.size);
                        json.set("modules", (block.hasItems ? 1 : 0) | (block.hasPower ? 2 : 0) | (block.hasLiquids ? 4 : 0));
                    }
                    json.pop();
                }
                json.pop();
            }
            json.pop();

            json.object("aliases");
            for (var alias : SaveFileReader.fallback) json.set(alias.key, alias.value);
            for (var alias : SaveFileReader.modContentNameMap) json.set(alias.key, alias.value);
            json.pop();

            json.array("teams");
            for (Team team : Team.all) {
                json.object();
                json.set("id", team.id);
                json.set("name", team.name);
                json.set("color", team.color.rgba());
                json.pop();
            }
            json.pop();

            Rules rules = new Rules();
            json.object("rules");
            json.set("mode", rules.mode().name());
            json.set("defaultTeam", rules.defaultTeam.id);
            json.set("waveTeam", rules.waveTeam.id);
            json.set("waves", rules.waves);
            json.set("waveTimer", rules.waveTimer);
            json.set("spawns", rules.spawns.size);
            json.set("dropZoneRadius", rules.dropZoneRadius);
            json.pop();

            json.array("blocks");
            for (Block block : Vars.content.blocks()) {
                json.object();
                json.set("id", block.id);
                json.set("name", block.name);
                json.set("layer", layer(block));
                json.set("size", block.size);
                json.set("rotate", block.rotate && block.rotateDraw);
                json.set("wallOre", block instanceof Floor floor && floor.wallOre);
                json.set("color", block.id < blockColors.length ? argbToRgba(blockColors[block.id]) : 0);
                json.array("sprites");
                for (String name : sprites(block, atlas)) {
                    json.value(name);
                    wanted.add(name);
                }
                json.pop();
                String team = block.name + "-team";
                if (block.hasBuilding() && atlas.containsKey(team)) {
                    json.set("teamSprite", team);
                    wanted.add(team);
                }
                json.pop();
            }
            json.pop();
            json.pop();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return text.toString();
    }

    /** How the renderer treats a block; "building" covers every block placed from a centre. */
    private static String layer(Block block) {
        if (block == mindustry.content.Blocks.air) return "air";
        if (block instanceof OverlayFloor) return "overlay";
        if (block instanceof Floor) return "floor";
        if (block.hasBuilding() || block.size > 1) return "building";
        return "static";
    }

    /** The regions the engine draws for this block, as found in the client's atlas; see Floor.load and loadIcon. */
    private static List<String> sprites(Block block, Map<String, TextureAtlasData.Region> atlas) {
        var names = new ArrayList<String>();
        boolean variants = (block instanceof Floor || block instanceof StaticWall || block instanceof Prop) && block.variants > 0;
        if (variants) {
            for (int i = 1; i <= block.variants; i++) if (atlas.containsKey(block.name + i)) names.add(block.name + i);
        }
        if (names.isEmpty()) {
            String type = block.getContentType().name();
            for (String candidate : new String[]{type + "-" + block.name + "-full", block.name + "-full", block.name,
                    type + "-" + block.name, block.name + "1"}) {
                if (atlas.containsKey(candidate)) {
                    names.add(candidate);
                    break;
                }
            }
        }
        return names;
    }

    private static int argbToRgba(int argb) {
        return argb << 8 | argb >>> 24;
    }

    // ---- sprite pack ----------------------------------------------------------------------

    private static void writePack(DataOutputStream out, Set<String> names, Map<String, TextureAtlasData.Region> atlas,
                                  Map<Fi, BufferedImage> pages) throws IOException {
        var sprites = new LinkedHashMap<String, int[]>();
        var sizes = new HashMap<String, int[]>();
        for (String name : names) {
            var region = atlas.get(name);
            if (region.rotate) throw new IOException("Rotated atlas regions are not supported: " + name);
            BufferedImage page = pages.computeIfAbsent(region.page.textureFile, fi -> {
                try {
                    return ImageIO.read(fi.file());
                } catch (IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });
            // The binary atlas leaves the original size at 0 for regions that were not trimmed.
            int width = Math.max(region.originalWidth, region.width), height = Math.max(region.originalHeight, region.height);
            int[] pixels = new int[width * height];
            // Whitespace was stripped around the centre; put the pixels back where they were.
            int left = Math.round(region.offsetX);
            int top = height - region.height - Math.round(region.offsetY);
            for (int y = 0; y < region.height; y++) {
                for (int x = 0; x < region.width; x++) {
                    pixels[(top + y) * width + left + x] = page.getRGB(region.left + x, region.top + y);
                }
            }
            sprites.put(name, pixels);
            sizes.put(name, new int[]{width, height});
        }

        out.writeInt(0x584d5250); // "XMRP"
        out.writeInt(1);
        out.writeInt(SCALES.length);
        for (int scale : SCALES) {
            var section = new java.io.ByteArrayOutputStream();
            var deflater = new Deflater(9);
            try (var data = new DataOutputStream(new DeflaterOutputStream(section, deflater))) {
                data.writeInt(sprites.size());
                for (var entry : sprites.entrySet()) {
                    int[] size = sizes.get(entry.getKey());
                    int factor = 32 / scale;
                    int width = Math.max(1, (size[0] + factor - 1) / factor);
                    int height = Math.max(1, (size[1] + factor - 1) / factor);
                    int[] scaled = factor == 1 ? entry.getValue() : boxFilter(entry.getValue(), size[0], size[1], factor, width, height);
                    data.writeUTF(entry.getKey());
                    data.writeShort(width);
                    data.writeShort(height);
                    for (int pixel : scaled) data.writeInt(pixel);
                }
            } finally {
                deflater.end();
            }
            out.writeInt(scale);
            out.writeInt(section.size());
            section.writeTo(out);
        }
    }

    /** Averages each factor×factor block with premultiplied alpha, so transparent edges do not darken. */
    static int[] boxFilter(int[] source, int sourceWidth, int sourceHeight, int factor, int width, int height) {
        int[] result = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                long a = 0, r = 0, g = 0, b = 0;
                int count = 0;
                for (int dy = 0; dy < factor; dy++) {
                    int sy = y * factor + dy;
                    if (sy >= sourceHeight) break;
                    for (int dx = 0; dx < factor; dx++) {
                        int sx = x * factor + dx;
                        if (sx >= sourceWidth) break;
                        int argb = source[sy * sourceWidth + sx];
                        int alpha = argb >>> 24;
                        a += alpha;
                        r += (long) (argb >> 16 & 0xff) * alpha;
                        g += (long) (argb >> 8 & 0xff) * alpha;
                        b += (long) (argb & 0xff) * alpha;
                        count++;
                    }
                }
                if (a == 0) continue;
                int alpha = (int) (a / count);
                result[y * width + x] = alpha << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
            }
        }
        return result;
    }

    private ContentExport() {}
}
