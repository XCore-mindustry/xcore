# Map images: thumbnails in `/maps`, detailed renders in `/map`

Status: **design, phase 1 merged.** The bot has the commands, the card and the seam
for images; nothing draws a picture yet.

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

   build time (Gradle, Java, Mindustry v160)
   ├─ content-export → content.json + sprites.pack  ──► shipped with the renderer
   └─ msav golden    → expected snapshots per map   ──► renderer's parity tests
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

MapBlobRefV1 { key, sha256, sizeBytes, contentType, width?, height? }
```

The reader checks size and SHA-256 before using the bytes. Keys are content-addressed,
so two requests for the same map share one blob and a retry costs nothing. Limits: 8 MiB
per image (Discord's attachment limit is 10 MiB), 4 MiB per `.msav` (the upload limit).

## Protocol (0.18.0, phase 2 and 3)

All new fields are optional and all new messages are new, so 0.17 readers keep
working, but the bot must be deployed before servers start sending `contentHash`
(strict readers reject unknown fields; see the 0.13–0.15 notes in `CHANGELOG.md`).

| Message | Route | Payload |
|---|---|---|
| `MapEntryV1.contentHash` | — | SHA-256 of the map file; the cache key for both pictures (`MapContentHash` already computes it) |
| `maps.preview.request` / `.response` | `xcore:rpc:req:{server}` | `{server, fileName, maxSide}` → `{status: ok/not_found/failed, contentHash, image: MapBlobRefV1}` |
| `maps.file.request` / `.response` | `xcore:rpc:req:{server}` | `{server, fileName}` → `{status, contentHash, file: MapBlobRefV1}` |
| `maps.render.request` / `.response` | `xcore:rpc:req:map-renderer` | `{contentHash, file: MapBlobRefV1, maxSide}` → `{status: ok/too_large/invalid_map/failed, image: MapBlobRefV1, renderMs}` |

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

**Language: open, to be decided by a measured prototype.** The candidates are Java (on
the JVM with small-heap flags, or compiled with GraalVM `native-image`) and Rust. Both
use the same design. Mindustry's content data and the sprite atlas are not loaded at
run time but produced by a Gradle build step, and the image is drawn in bands. This
section describes that shared part, then where the two languages differ.

### 1. Content table, generated from Mindustry

A Gradle task, `:tools:map-content-export` (Java, Mindustry `v160` from
`gradle/libs.versions.toml`), loads content headlessly (`new ContentLoader()`,
`createBaseContent()`, `init()`) and writes `content.json`:

- per block id and name: `size`, `rotate`, `variants`, `isFloor`, `isOverlay`,
  `wallOre`, `synthetic`, `solid`, `mapColor` (with `MapColorPalette`'s fallbacks), the
  sprite names it draws with (`{name}{1..variants}`, the full icon, the `-team` region);
- per team id: colour;
- the Mindustry version and a hash of the whole table.

The renderer bundles this table and resolves the names of a map's
content header through it, as `ContentRegistrySnapshot` does. A name it does not know
makes the map `unknown_content`. A Mindustry bump (the `mindustry` version in
`gradle/libs.versions.toml`) re-runs the export, and CI fails if the committed table differs from a fresh
one, so the table cannot fall behind the servers.

### 2. Sprite pack, cut once instead of loading the atlas

The client's atlas pages are up to 4096×4096, about 64 MB each once decoded, and
holding them is exactly the memory we are avoiding. The same Gradle task reads
`sprites.aatls` from the pinned client release (downloaded, checksum verified, never
committed), keeps only the regions in `content.json`, and writes `sprites.pack`:

- each region pre-scaled with a box filter to 32, 16 and 8 px per tile (floors, ores and
  walls are 1×1; buildings are `size × size`);
- stored as one zstd-compressed blob with an index of offsets, so the renderer can
  decompress only the scale it uses into one buffer. That is about 10–15 MB for 32 px
  and a quarter of that for 16 px.

The pack is a release asset of the renderer, rebuilt with the table.

### 3. The reader: shared in Java, pinned by goldens in Rust

- **Java:** `DetachedMsavReader`, `MapSnapshot` and `InspectionSettings` move from
  `plugins/xcore/.../service/map/inspect` into `libs/msav`. `ContentRegistrySnapshot`
  becomes an interface: the plugin fills it from `Vars.content`, the renderer from
  `content.json`. One decoder serves both, and `EngineCoreOracleTest` keeps checking
  it against the engine. `MapSnapshot` gains building centres with rotation and team.
- **Rust:** a render-only reader for save versions 4–13. Modern building chunks are
  length-prefixed and start with the shared base (revision, health, rotation, team),
  so it takes rotation and team and skips the rest by length. Versions 1–3 are
  `unsupported_version`. Parity is automated: a Gradle task, `exportMsavGolden`, runs
  the Java reader over the bundled maps and wire fixtures and writes golden snapshots
  (dimensions, hashes of the tile arrays, building centres). The Rust tests must match
  them or reject the same files, and CI fails when the goldens are stale.

### Drawing, in bands

Scale `s = clamp(⌊maxSide / max(width, height)⌋, 8, 32)`, rounded down to 8, 16 or 32,
the scales in the pack. `maxSide` is 4096 by default. The image is drawn in bands of
16 tile rows and handed to a streaming PNG encoder band by band, top row first: one
`IDAT` deflate stream over the filtered rows in Java (`java.util.zip.Deflater`), the
`png` crate's `StreamWriter` in Rust. Only one band is ever in memory: for a
4096 px wide image at 16 rows × 16 px, that is 4 MB, whatever the map's height.
Per tile, bottom to top:

1. floor: variant `1 + hash(x, y) % variants`; edge blending between floors is phase 4;
2. ore overlay, then static wall (`wallOre` on top of the wall);
3. buildings whose footprint (`-(size - 1) / 2` from the centre, as the engine places
   it) crosses the band: full icon, rotated by `rotation · 90°` for `rotate` blocks,
   then the `-team` region multiplied by the team colour. Conveyor and conduit
   autotiling is phase 4.

If the encoded PNG exceeds 8 MiB, the renderer drops to the next smaller scale and
renders again.

### Service shape

- `services/map-renderer`, one process. It links no Mindustry code at run time: the
  table and the pack are its only Mindustry inputs.
- It consumes `xcore:rpc:req:map-renderer` with a consumer group and answers on the
  requester's response stream, using the same envelope as `RedisTransport`. In Java the
  generated `xcore-protocol-java` models are used as they are. In Rust, serde structs are
  checked against the fixtures in `protocol/spec/fixtures/`.
- Two renders at a time (a semaphore); a third request waits up to its TTL, then gets
  `busy`. Results are cached on disk by `(contentHash, rendererVersion, maxSide)`, with
  an LRU of 1 GB. `rendererVersion` combines the service version and the content table
  hash.
- Health: a `/ready` HTTP probe like the gateway's; render count, time and peak RSS for
  Prometheus.
- Deploy: `ops/deploy map-renderer` and `ops/systemd/xcore-map-renderer.service`.
  The server needs no build toolchain: it gets a jar (JVM), a native binary from
  `native-image`, or a static musl binary (Rust).

### Memory budget (estimates, to be replaced by measurements)

The working set is the same in both languages:

| | |
|---|---|
| content table | < 1 MB |
| sprites at the scale in use | 4–15 MB |
| per render: decoded map (≤ 360 000 tiles) + `.msav` bytes | ≤ 10 MB |
| per render: one band + encoder buffers | ≤ 8 MB |
| **two concurrent renders** | **≈ 50 MB** |

What the runtime adds on top:

| Runtime | Expected peak RSS |
|---|---|
| Rust | ≈ 60 MB |
| Java, GraalVM `native-image` | ≈ 40–70 MB |
| Java, JVM with `-Xmx96m -XX:+UseSerialGC -Xss256k`, capped metaspace, CDS | ≈ 130–180 MB |

Speed should not decide it: the work is tight loops over `int[]`/`u32` buffers, which
the JIT and `native-image` handle within a small factor of Rust, and a 300×300 map
should render in under a second either way.

### Deciding

The prototype comes first: the build step plus a renderer that draws three bundled maps
(small, 300×300 and the largest), written in Java. It is measured on the JVM with the
flags above and as a `native-image`: peak RSS during two concurrent renders, render
time and image size. If either Java build fits the host's budget, Java stays: one
decoder, no fourth language in the repository. If neither does, the renderer moves to
Rust, and the content export, the pack and the golden snapshots from the prototype
carry over unchanged.

## Phases

1. **Done here.** `/maps` group, `/map <server> <map>` card, `MapImages` seam with
   `NoMapImages`, tests.
2. **Thumbnails.** Protocol 0.18 (`MapBlobRefV1`, `contentHash`, `maps.preview`).
   Plugin handler around `MapPreviewService`'s existing PNG path plus integer upscale.
   `ServerThumbnails` in the bot. A select menu on `/maps list` pages that opens a
   map's card.
3. **Detailed renders.**
   a. `:tools:map-content-export` (content table + sprite pack) with its CI freshness
      check;
   b. the measured prototype, and the language decision (see *Deciding*);
   c. the reader (`libs/msav`, or the Rust reader against goldens) and the compositor,
      checked by snapshot tests on a few bundled maps (perceptual diff, so a changed
      filter does not fail on single pixels);
   d. the service around it, `maps.file`, `maps.render` and `RendererImages` in the
      bot;
   e. CI release, `ops/systemd/xcore-map-renderer.service`, `ops/deploy map-renderer`.
4. **Polish.** Floor edge blending, conveyor autotiling, a gallery view in `/maps list`
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
