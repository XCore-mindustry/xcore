# Map images: thumbnails in `/maps`, detailed renders in `/map`

Status: **phases 1–3 built, not yet deployed.** `/map` shows the server's thumbnail,
`/maps list` opens map cards, and the **Detailed render** button goes through
`maps.file` and the map renderer service. Anyone may press it, at most once every 30 s
per person for a new render (`RenderCooldown`); a render already in the bot's cache is
shown again without waiting.

## What the player sees

| Command | Shows | Picture |
|---|---|---|
| `/maps list <server> [creator]` | the server's maps, 15 per page, sortable | phase 2: a thumbnail per map, on request |
| `/map <server> <map>` | one map: author, size, mode, rating, activity, Discord attribution, file | a thumbnail right away; the **Detailed render** button swaps it for a render with block sprites |
| `/maps upload`, `/maps remove`, `/maps review` | map management (map reviewers) | — |

`/map` used to be the management group. Discord does not let one name be a group and
a command at once, so management moved to `/maps`.

The two pictures are different on purpose:

- **Thumbnail**: one pixel per tile, coloured by floor, ore, wall and team (what
  `DetachedMapPreview` already draws for the in-game vote menu), scaled up by a whole
  number so the long side is about 512 px. Small (tens of KB), drawn in milliseconds,
  cheap enough to show next to every map.
- **Detailed render**: block sprites, like the attached reference picture: floors with
  their variants, ores, static walls, buildings rotated and team-tinted. 8–16 px per
  tile, at most 4096 px on the long side, up to several MB, hundreds of milliseconds
  to seconds per map. Drawn only when someone asks, and cached by map content.

## Where each picture is drawn

```
            ┌──────────── Discord bot ─────────────┐
            │ /maps, /map → MapImages (cache)       │
            │   ├─ ServerThumbnails  (phase 2)      │
            │   └─ RendererImages    (phase 3)      │
            └──────┬───────────────────────┬────────┘
   maps.preview    │                       │  maps.render
   maps.file       ▼                       ▼
   ┌──────── game server (XCore) ───┐   ┌─── map-renderer ───────────┐
   │ DetachedMapPreview: 1 px/tile  │   │ msav reader                │
   │ publishes .msav bytes as blob  │   │ content table + sprite pack│
   └────────────────────────────────┘   │ banded compositor → PNG    │
                 │                       └────────────┬───────────────┘
                 └──────── Redis: blobs with TTL ─────┘

   build time (Gradle, Mindustry v160 + client atlas)
   └─ exportContent → content.json + sprites.pack  ──► shipped with the renderer
```

**Thumbnails on the game server.** It owns the map files, the decoder and the colour
palette, and already draws exactly this picture off the game thread under
`DetachedMapReadLock`, with an on-disk cache next to each map (`map.previewFile()`).
The only new work is answering an RPC and scaling up by a whole number.

**Detailed renders in a separate service**, `services/map-renderer`, never on a game
server:

- The game server's jar has no sprites: a headless server runs without the texture
  atlas (this is also why `MapColorPalette` exists). The sprites come from the client
  release of the same Mindustry version (`sprites/sprites.aatls` and its page PNGs).
- A render allocates tens of MB of pixels and keeps a core busy for up to seconds.
  That must not share a heap and CPU budget with the game tick.
- The services run on the same host as the game servers (see `ops/README.md`), and the
  host has little memory to spare. The memory goes to three things, whatever the
  language: Mindustry's content loaded at run time, the client's atlas pages (about
  64 MB each once decoded) and a canvas for the whole map (64 MB at 4096²). The design
  below removes all three with build steps and banded drawing. The renderer then
  renders at most two maps at once, with `Nice=10`, `CPUQuota=100%` and a `MemoryMax`
  set from the measured peak.

**Not in the bot.** That would be a third decoder, in a process that also serves
Discord. The bot keeps its bounded header check (`cases/msav.py`) and nothing more.

## Moving bytes: blobs, not stream entries

RPC responses live in Redis streams trimmed to 20 000 entries (`MAXLEN_RPC_RESP`).
A multi-megabyte PNG or `.msav` inside an entry would stay in memory until trimmed.
So binary payloads go into a plain Redis key with a TTL, and the message carries a
reference:

```
xcore:blob:{sha256}          SET … EX 900   (raw bytes)

MapBlobRefV1 { key, sha256, sizeBytes, contentType, width?, height?, expiresAt }
```

The reader checks size and SHA-256 before using the bytes. Keys are content-addressed,
so two requests for the same map share one blob and a retry costs nothing. Limits: 8 MiB
per image (Discord's attachment limit is 10 MiB), 4 MiB per `.msav` (the upload limit).

## Protocol (0.18.0, done)

All new fields are optional and all new messages are new, so 0.17 readers keep
working, but the bot must be deployed before servers start sending `contentHash`
(strict readers reject unknown fields; see the 0.13–0.15 notes in `CHANGELOG.md`).

| Message | Route | Payload |
|---|---|---|
| `MapEntryV1.contentHash` | — | SHA-256 of the map file; the cache key for both pictures (`MapContentHash` already computes it) |
| `maps.preview.request` / `.response` | `xcore:rpc:req:{server}` | `{server, fileName, maxSide}` → `{status: ok/not_found/failed, contentHash, image: MapBlobRefV1}` |
| `maps.file.request` / `.response` | `xcore:rpc:req:{server}` | `{server, fileName}` → `{status, contentHash, file: MapBlobRefV1}` |
| `maps.render.request` / `.response` | `xcore:rpc:req:map-renderer` | `{contentHash, file: MapBlobRefV1, maxSide?, maxBytes?}` → `{status: ok/busy/too_large/invalid_map/download_failed/failed, image: MapBlobRefV1, scale, renderMs, reason?}` |

`maps.preview` could later carry several file names, so that a page of `/maps list`
costs one round trip.

## The bot side (phase 1, in place)

- `services/map_images.py`: `MapImageKind` (`thumbnail`, `detailed`), `MapRef`
  (server, file, optional content hash), `MapImage` (PNG + size), the
  `MapImageSource` protocol and `MapImages`, an LRU of 64 pictures for 15 minutes. It
  is keyed by content hash when known, otherwise by server and file name, and dropped
  when a map is uploaded or removed. `NoMapImages` is the source until phase 2.
- `map_card.py`: the embed of one map. `handlers_maps.cmd_map_details` lists the
  server's maps, finds the file, asks for the thumbnail and adds the render button
  (`server_views.MapDetailsView`) only if the source supports `detailed`.
- Phase 2 adds `ServerThumbnails` and phase 3 `RendererImages`, each a
  `MapImageSource`. A small composite source routes each kind to one of them. The
  handlers do not change.

`RendererImages.fetch` will:

1. look up `xcore:map-render:{contentHash}:{rendererVersion}:{maxSide}` (a pointer to a
   blob with a 7-day TTL) and return it if present;
2. otherwise `maps.file` from the game server, then `maps.render` to the renderer with
   a 60 s timeout (the button keeps the interaction alive for 15 minutes);
3. read the image blob, verify it and store the pointer.

## The renderer (phase 3)

**Java, decided by a measured prototype** (`services/map-renderer`, see its README and
*Measurements* below). Rust was considered for memory. The prototype showed that the
memory goes to the design, not to the language: loading content at run time, whole atlas
pages and a full-map canvas. Without those three, the JVM peaks at 162 MB at `-Xmx64m`
and a `native-image` build at 87 MB, within the 256 MB budget. Java keeps one `.msav`
decoder for the plugin and the renderer and adds no new language to the repository.

### 1. Content table, generated from Mindustry

The Gradle task `:services:map-renderer:exportContent` (source set `export`, Mindustry
`v160` from `gradle/libs.versions.toml`) loads content headlessly (`new ContentLoader()`,
`createBaseContent()`, `init()`) and writes `content.json`:

- every mappable content name and id per type, the block flags the reader needs,
  save-file aliases, teams and default rules: exactly what
  `ContentRegistrySnapshot.capture()` copies in the plugin;
- per block: its layer (floor, overlay, static, building), `size`, whether its sprite
  rotates (`rotate && rotateDraw`), `wallOre`, the colour from `block_colors.png`, and
  the sprite names the engine draws with (`{name}{1..variants}` as in `Floor.load`, else
  the full icon as in `loadIcon`, plus the `-team` region);
- per team: colour.

The renderer resolves a map's content header through this table, as the plugin does.
The table is a build output, not a committed file: a Mindustry bump in
`gradle/libs.versions.toml` regenerates it, so it cannot fall behind the servers.

### 2. Sprite pack, cut once instead of loading the atlas

The client's atlas pages are up to 4096×4096, about 64 MB each once decoded, and
holding them is exactly the memory we are avoiding. The same Gradle task reads
`sprites.aatls` from the pinned client release (downloaded, checksum verified, never
committed), keeps only the regions in `content.json` (616 of 5161), puts back the
whitespace the packer stripped, and writes `sprites.pack`:

- each region box-filtered (premultiplied alpha) to 32, 16, 8 and 4 px per tile;
- one deflated section per scale, so the renderer inflates only the scale it draws at.
  The whole pack is 1.2 MB.

The pack is a build output of the renderer, rebuilt with the table.

### 3. The reader, shared with the plugin

`DetachedMsavReader`, `MapSnapshot`, `MapRules` and the value part of
`ContentRegistrySnapshot` live in `libs/msav` (done), with no Mindustry dependency. The
registry's `capture()` from `Vars.content` stays in the plugin as
`ContentRegistryCapture`. The renderer builds the same record from `content.json`. One
decoder serves both, and `EngineCoreOracleTest` keeps checking it against the engine.
`MapSnapshot.buildings()` lists every placed building and multiblock centre with its
team and rotation. The renderer's runtime classpath is now `msav.jar` and Arc, 1.3 MB.

### Drawing, in bands

Scale `s`: the largest of 32, 16, 8 and 4 px per tile whose long side fits `maxSide`
(4096 by default). The image is drawn in bands of 16 tile rows and handed to a streaming
PNG encoder band by band, top row first: RGB, the Sub filter, one `IDAT` deflate stream
(`java.util.zip.Deflater`) cut into 64 KB chunks. Only one band is ever in memory: for a
4096 px wide image at 16 rows × 16 px, that is 4 MB, whatever the map's height.
Layers, bottom to top, in the engine's order:

1. floor variant, the edges of higher floors around it (`Floor.drawEdges`), the ore;
2. block shadows, a per-tile mask sampled bilinearly as the engine's linear-filtered
   frame buffer is;
3. natural walls (2x2 `-large` quarters where four equal walls allow it), wall ores;
4. buildings whose footprint (`-(size - 1) / 2` from the centre, as the engine places
   it) crosses the band: full icon, rotated by `rotation · 90°` for `rotate` blocks,
   then the `-team` region multiplied by the team colour. Conveyors, ducts and
   conduits use the sprite for their blend case instead (`Autotiler`), with caps;
5. darkness: wall masses by distance to open ground, and the map border.

If the encoded PNG exceeds 8 MiB, the renderer drops to the next smaller scale and
renders again. With edges and shading, map 263 (480×480) is the one bundled map that
steps down to 4 px for it.

### Service shape

- `services/map-renderer`, one process. It links no Mindustry code at run time: the
  table and the pack are its only Mindustry inputs.
- It consumes `xcore:rpc:req:map-renderer` with a consumer group and answers on the
  requester's response stream, using the same envelope as `RedisTransport` and the
  generated `xcore-protocol-java` models.
- Two renders at a time (a semaphore); a third request waits up to its TTL, then gets
  `busy`. Results are cached on disk by `(contentHash, rendererVersion, maxSide)`, with
  an LRU of 1 GB. `rendererVersion` combines the service version and the content table
  hash.
- Health: a `/ready` HTTP probe like the gateway's; render count, time and peak RSS for
  Prometheus.
- Deploy: `ops/deploy map-renderer` and `ops/systemd/xcore-map-renderer.service`, with
  `-Xmx64m -XX:+UseSerialGC -Xss512k -XX:MaxMetaspaceSize=48m
  -XX:ReservedCodeCacheSize=32m` and `MemoryMax=256M`. The host already has Java 25 for
  the game servers. If the budget shrinks, the same code is built with `native-image`
  and shipped as one binary.

### Measurements

From the prototype, 2026-11-09: the 113 readable maps bundled with v160, three rounds,
two renders at a time, 4 cores. Peak RSS is the kernel's `VmHWM` for the process.

| Runtime and flags | Peak RSS | 339 renders |
|---|---|---|
| JVM, `-Xmx128m -XX:+UseSerialGC` | 223 MB | 80 s |
| JVM, `-Xmx96m`, Serial GC, small stacks, metaspace and code cache | 191 MB | 79 s |
| JVM, same with `-Xmx64m` | **162 MB** | 78 s |
| JVM, `-Xmx64m`, C1 only (`-XX:TieredStopAtLevel=1`) | 116 MB | 98 s |
| GraalVM `native-image`, `-Xmx64m` | 87 MB | 95 s |
| GraalVM `native-image`, `-Xmx48m` | 79 MB | 100 s |

With the first, icons-only composer one render took 0.1–0.8 s on the JVM, a 512×512 map
included. The full composer (edges, shadows, darkness, autotiling) takes 0.2–1.9 s and
peaks at 167 MB at `-Xmx64m`. The extra time is mostly PNG encoding of the busier images. Content and sprites
cost 13 MB of RSS once loaded. Of the JVM's 162 MB, Native Memory Tracking accounts for
93 MB (64 MB heap, 14 MB shared classes, 5 MB code). Most of the rest is the C2
compiler's native memory. `native-image` draws byte-identical images about 20 % slower.

## Phases

1. **Done here.** `/maps` group, `/map <server> <map>` card, `MapImages` seam with
   `NoMapImages`, tests.
2. **Thumbnails (done).** Protocol 0.18 (`MapBlobRefV1`, `contentHash`, `maps.preview`,
   `maps.file`, `maps.render`). The plugin's `MapImageService` draws the preview with
   `DetachedMapPreview`, scales it by a whole number and publishes it with `MapBlobStore`;
   it also publishes map files for the renderer. The bot's `ServerThumbnails` reads the
   blob (`NEVER_DECODE`, size and SHA-256 checked). A menu on `/maps list` pages opens a
   map's card, ephemeral.
3. **Detailed renders.**
   a. **done in the prototype:** `exportContent` (content table + sprite pack);
   b. **done:** the measured prototype and the language decision (Java);
   c. **done:** `libs/msav`; still to do: snapshot tests of the compositor on a few bundled maps
      (perceptual diff, so a changed filter does not fail on single pixels);
   d. the service around it, `maps.file`, `maps.render` and `RendererImages` in the
      bot;
   e. CI release, `ops/systemd/xcore-map-renderer.service`, `ops/deploy map-renderer`.
4. **Polish.** Bridges and power links (needs the reader to keep their config), a gallery view in `/maps list`
   (up to four embeds sharing one `url` show as one image grid), the same renders on
   map pages of the website.

## Prior art (read for approach, no code taken)

- [Anuken/CoreBot](https://github.com/Anuken/CoreBot), `src/corebot/ContentHandler.java`
  (Java). This is Mindustry's own Discord bot. It initialises content headlessly and
  reads `sprites.aatls` from a Mindustry checkout, loading region images from the
  unpacked sprites. It draws schematics at 32 px per tile through an AWT-backed
  `SpriteBatch`, with rotation by affine transform and team colour by multiply. Maps
  are drawn only as a 1 px-per-tile colour image, as our thumbnail is. It shows that
  sprite drawing without a GPU works. We do the same with a sprite pack cut at build time.
- [mindus](https://docs.rs/crate/mindus/latest) and the bot
  [plent](https://git.bendn.org/plent/tree/src/bot/map.rs) (Rust, GPL-3.0). They render
  whole maps with sprites in the style of the reference picture and lower the sprite
  scale for large maps (a quarter or an eighth of the 32 px sprite, by map size). This
  is the closest match to the goal and the reason for the scale rule above. Its
  licence (GPL-3.0; this repository is MPL-2.0) is the reason we take neither code nor
  binaries from it: our reader, table and compositor are written from Mindustry's own
  sources and our Java reader.
- Our own `DetachedMapPreview` and `MapColorPalette` in the plugin. These draw the
  thumbnail and are what phase 2 exposes.
