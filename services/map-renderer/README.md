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
  - `sprites.pack`: the 616 sprites maps use, untrimmed, at 32, 16, 8 and 4 px per tile; one
    deflated section per scale, 1.2 MB in total.
- The renderer (source set `main`) never loads Mindustry content. It decodes the map with the
  plugin's `DetachedMsavReader` and draws it in bands of 16 tile rows, top first. Each band goes
  straight to a streaming PNG encoder (RGB, Sub filter). Layers per tile: floor variant, ore,
  static wall, wall ore. Then buildings, as full icons, rotated and with the team region tinted.
  Variants are picked as the engine picks them (`Mathf.randomSeed(pos, 0, n - 1)`).
- Scale: the largest of 32/16/8/4 px per tile whose long side fits `maxSide` (4096). If the PNG
  is over the byte limit (8 MiB), it is rendered again one scale smaller.

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
- Floor edge blending, conveyor/conduit autotiling, cliffs and the darkness over solid walls
  are not drawn.
