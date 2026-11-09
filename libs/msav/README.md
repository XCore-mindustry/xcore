# msav

Reads Mindustry v160 map files (`.msav`, save versions 1–13) without the engine: no content
mapper, no `SaveIO`, no deserializers, no `Vars`. It decodes the metadata, the content header and
the tiles: floors, overlays, blocks, teams, and every placed building with its team and rotation.
Building, entity and marker payloads are framed and skipped, never interpreted.

It is what the XCore plugin inspects uploaded maps and draws vote-menu previews with, and what
[`services/map-renderer`](../../services/map-renderer) draws detailed renders with.

```java
MapSnapshot map = new DetachedMsavReader().read(input, size, sha256, registry, MsavLimits.of(4 << 20, 360_000));
```

- `ContentRegistrySnapshot`: the content names and ids a map resolves against, as plain values.
  The plugin copies it from the running game (`ContentRegistryCapture`); the renderer loads it from
  the table its build step exported.
- `MsavLimits`: compressed size and tile count a caller accepts, under fixed ceilings (64 MiB
  inflated, 32 MiB per region, 1,000,000 tiles).
- `ContentTypes`: v160's `ContentType` ordinals; the plugin's `ContentTypesTest` checks them
  against the engine.

The library has no Mindustry dependency. It uses Arc's JSON parser, declared `compileOnly`: the
game server provides Arc to the plugin, and the renderer depends on it itself.

The format, its bounds and the engine-parity rules are documented with the inspection that uses
it: [`plugins/xcore/.../service/map/inspect/README.md`](../../plugins/xcore/src/main/java/org/xcore/plugin/service/map/inspect/README.md).
The tests are there too, since they compare the reader against the real engine.
