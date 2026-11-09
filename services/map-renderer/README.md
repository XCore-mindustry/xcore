# map-renderer (prototype)

Draws a Mindustry map with block sprites, for the **Detailed render** button of `/map` in the
Discord bot. Design and status: [`services/discord-bot/docs/map-images.md`](../discord-bot/docs/map-images.md).

This is the measured prototype. It renders and benchmarks; it does not listen on Redis yet.

## How it works

- `exportContent` (build time, source set `export`) loads Mindustry v160 content headlessly and
  reads the atlas of the client release (`Mindustry.jar`, SHA-256 pinned in `build.gradle.kts`).
  It writes `build/render-data/`:
  - `content.json`: every content name and id (what the plugin's `ContentRegistrySnapshot`
    holds), plus how each block is drawn: sprite names, size, rotation, team region, colour;
  - `sprites.pack`: the 1260 sprites maps use (block sprites, the cells of floor edge sheets and
    of 2x2 wall sheets, the autotile cases of conveyors, ducts and conduits), untrimmed, at 32,
    16, 8 and 4 px per tile; one deflated section per scale, 1.6 MB in total.
- The renderer (source set `main`) never loads Mindustry content. It decodes the map with the
  plugin's `DetachedMsavReader` and draws it in bands of 16 tile rows, top first. Each band goes
  straight to a streaming PNG encoder (RGB, Sub filter). Layers, in the engine's order:
  - floor variant, then the edges of higher floors around it (`Floor.drawEdges`: blend id order,
    same cache layer), then the ore;
  - block shadows (`BlockRenderer.drawShadows`: every `hasShadow` tile, 0.71 black, linear
    filtered);
  - natural walls, with a quarter of the 2x2 `-large` sprite where four equal walls allow it
    (`StaticWall.drawBase`), and wall ores;
  - conveyors, ducts and conduits joined to their neighbours (`Tiling`, a port of `Autotiler`
    and each block's `blends`, with conduit and duct caps); plastanium conveyors by
    `StackConveyorBuild`'s rules: load/move/unload state from the line around them (settled over
    a few passes, since each depends on its neighbours' states) and an edge on every side that
    does not join;
  - other buildings: full icon, rotated, team region tinted;
  - darkness (`World.getWallDarkness` and `borderDarkness`): wall masses go black with their
    distance to open ground, and so do the two tiles at the map border.

  Variants are picked as the engine picks them (`Mathf.randomSeed(pos, 0, n - 1)`).
- Scale: the largest of 32/16/8/4 px per tile whose long side fits `maxSide` (4096). If the PNG
  is over the byte limit (8 MiB), it is rendered again one scale smaller. Memory per render
  grows with the image width (a band is `width × 16 tiles × scale`), so `maxSide` 4096 is what
  the `-Xmx64m` budget is measured for. The bench takes `-DmaxSide` and `-DmaxBytes` to render
  larger images for inspection, with a larger heap.

## Running

```sh
./gradlew :services:map-renderer:installDist          # also downloads the client and exports
build/install/map-renderer/bin/map-renderer \
  services/map-renderer/build/render-data out/ 2 3 maps/*.msav
# args: render data, output dir, concurrent renders, rounds, maps
```

`-PmindustryClientJar=<path>` uses a local `Mindustry.jar` instead of downloading it.
Pass JVM flags in `JAVA_OPTS`.

## Measurements (2026-11-09)

### Current composer

The 113 readable maps bundled with v160 (110×400 to 600×600 tiles), 3 rounds, the last one
reported (JIT warm), `-Xmx64m -XX:+UseSerialGC -Xss512k -XX:MaxMetaspaceSize=48m
-XX:ReservedCodeCacheSize=32m`, PNG deflate level 3, 4 cores.

| | 1 render at a time | 2 at a time |
|---|---|---|
| per map, median / p90 / max | 0.47 / 0.75 / 1.09 s | 0.52 / 0.82 / 1.52 s |
| decode / draw / PNG encode | 10 % / 45 % / 46 % | same |
| CPU per render | 0.56 s | 0.58 s |
| peak RSS | 170 MB | 166 MB |

- Idle after loading content and sprites: 56 MB RSS. JVM start to ready: about 0.2 s; a cold
  process renders its first 256×256 map in 1.3 s.
- Drawing runs at about 40 Mpx/s per core and so does encoding at level 3. Level 6 encodes at
  half that speed for 3 % smaller files; level 1 saves little more than level 3.
- Output: 79 maps at 8 px per tile, 21 at 4 px, 13 at 16 px; PNG median 3.1 MB, max 7.9 MB.
- The max includes a second attempt one scale smaller when the first PNG is over 8 MiB.

### First composer (icons only), runtimes compared

The 113 readable maps bundled with v160 (the 114th, `canyon`, is a legacy map that the plugin's
reader rejects too), 3 rounds, 2 renders at a time, 4 cores. Peak RSS is `VmHWM`.

| Runtime and flags | Peak RSS | 339 renders |
|---|---|---|
| JVM, `-Xmx128m -XX:+UseSerialGC` | 223 MB | 80 s |
| JVM, `-Xmx96m`, Serial GC, `-Xss512k`, metaspace 48m, code cache 32m | 191 MB | 79 s |
| JVM, same with `-Xmx64m` | 162 MB | 78 s |
| JVM, `-Xmx64m` + `MALLOC_ARENA_MAX=2` | 157 MB | 86 s |
| JVM, `-Xmx64m` + `-XX:TieredStopAtLevel=1` (C1 only) | 116 MB | 98 s |
| GraalVM `native-image`, `-Xmx64m` | 87 MB | 95 s |
| GraalVM `native-image`, `-Xmx48m` | 79 MB | 100 s |

- One render takes 0.1–0.8 s on the JVM; decoding is 3–290 ms of it. A 512×512 map is
  4096×4096 px at 8 px per tile, 2–7 MB of PNG.
- With Native Memory Tracking, the JVM accounts for 93 MB at `-Xmx64m`: 64 MB heap, 14 MB CDS,
  5 MB code. The rest of the RSS is native memory outside it, mostly the C2 compiler's arenas
  (C1 only saves 46 MB).
- The `native-image` build produces identical images. It is about 20 % slower than the JIT
  and needs GraalVM to build (1 min 50 s, 2.5 GB peak).

With a 256 MB budget, the plain JVM at `-Xmx64m` fits with about 90 MB to spare.
`native-image` is the next step if the budget shrinks.

## Known gaps (to be closed before production)

- The reader comes from `:plugins:xcore` with all of its dependencies; it moves to `libs/msav`.
- No Redis consumer, protocol messages or disk cache yet.
- Not drawn: items on conveyors and liquids in conduits, bridge and power-node links (their
  configuration is in building payloads the reader leaves opaque), cliffs, autotiled Erekir floors and walls, and animated water.
