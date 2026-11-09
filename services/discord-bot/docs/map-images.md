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
   ┌──────── game server (XCore) ───┐   ┌──── map-renderer (JVM) ────┐
   │ DetachedMapPreview: 1 px/tile  │   │ msav decoder (libs/msav)   │
   │ publishes .msav bytes as blob  │   │ sprite atlas from client   │
   └────────────────────────────────┘   │ Pixmap compositor          │
                 │                       └────────────┬───────────────┘
                 └──────── Redis: blobs with TTL ─────┘
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
- The services run on the same host as the game servers (see `ops/README.md`), so the
  renderer gets a systemd unit with `Nice=10`, `CPUQuota=100%`, `MemoryMax=1G`, and
  renders at most two maps at once.

**Not in the bot.** Decoding `.msav` in Python means a second decoder next to the
Java one that is pinned against the engine by `EngineCoreOracleTest`, plus block
sizes, rotations and sprite names that only exist in Mindustry's content classes.
The bot keeps its bounded header check (`cases/msav.py`) and nothing more.

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

**Decoder.** Move `DetachedMsavReader`, `ContentRegistrySnapshot`, `MapSnapshot` and
`InspectionSettings` from `plugins/xcore/.../service/map/inspect` into `libs/msav`
(Mindustry `compileOnly`), used by the plugin and the renderer. `MapSnapshot` gains
what a render needs and inspection ignores: the building centre index, rotation and
team per building (the reader already parses `rotation` in the shared building base),
and the variant seed per tile.

**Content.** At start-up, without a GPU: `Vars.content = new ContentLoader()`,
`createBaseContent()`, `init()` per content. Block sizes, `rotate`, `variants`,
`teamRegion` names and `mapColor` come from there. The Mindustry version is pinned to
the servers' version (v160), and the renderer refuses maps whose content header names
blocks it does not know (`unknown-content`, as in inspection).

**Sprites.** Read `sprites.aatls` with Arc's `TextureAtlasData`, load each page PNG as
a `Pixmap` (the plugin already uses Arc's native Pixmap off the GL thread), and cut
regions into `PixmapRegion`s on first use. No `Texture`, no `SpriteBatch`, no GL
context. The client jar is downloaded at build time and its checksum pinned. It is
never committed.

**Drawing,** into one `Pixmap` of `width·s × height·s`, where
`s = clamp(⌊maxSide / max(width, height)⌋, 1, 32)`, with each 32 px sprite
box-filtered down to `s` once and cached per block and scale:

1. floor: `{floor}{1 + hash(x, y) % variants}`; edge blending between floors is phase 4;
2. ore overlay: `{ore}{variant}`;
3. static wall: `{wall}{variant}`; `wallOre` draws on top;
4. buildings, at the centre with the engine's offset (`-(size - 1) / 2`): the block's
   full icon, rotated by `rotation · 90°` for `rotate` blocks, then the `-team` region
   multiplied by the team colour if the block has one. Conveyor and conduit
   autotiling is phase 4; until then they use their icons;
5. encode PNG. If it exceeds 8 MiB, render again at `0.75 · maxSide`.

Budget: a 300×300 map at 13 px per tile is a 3900 px square, about 60 MB of pixels.
That is about 270k sprite blits, well under a second on one core. The renderer keeps
results on disk by `(contentHash, rendererVersion, maxSide)` with an LRU of 1 GB;
`rendererVersion` changes when the drawing changes or Mindustry is updated.

## Phases

1. **Done here.** `/maps` group, `/map <server> <map>` card, `MapImages` seam with
   `NoMapImages`, tests.
2. **Thumbnails.** Protocol 0.18 (`MapBlobRefV1`, `contentHash`, `maps.preview`).
   Plugin handler around `MapPreviewService`'s existing PNG path plus integer upscale.
   `ServerThumbnails` in the bot. A select menu on `/maps list` pages that opens a
   map's card.
3. **Detailed renders.** `libs/msav` extraction, `services/map-renderer`,
   `maps.file`, `maps.render`, `RendererImages`, `ops/systemd/xcore-map-renderer.service`
   and `ops/deploy map-renderer`.
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
  sprite drawing without a GPU works; our renderer draws into Arc `Pixmap`s instead of AWT.
- [mindus](https://docs.rs/crate/mindus/latest) and the bot
  [plent](https://git.bendn.org/plent/tree/src/bot/map.rs) (Rust, GPL-3.0). They render
  whole maps with sprites in the style of the reference picture and lower the sprite
  scale for large maps (a quarter or an eighth of the 32 px sprite, by map size). This
  is the closest match to the goal and the reason for the scale rule above. Its
  licence is the reason we take neither code nor binaries from it.
- Our own `DetachedMapPreview` and `MapColorPalette` in the plugin. These draw the
  thumbnail and are what phase 2 exposes.
