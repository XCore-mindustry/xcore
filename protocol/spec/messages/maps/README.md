# Maps RPC Messages

Maps request/response contracts belong here.

## Canonical V1 Messages
- `maps.list.request` — request the current custom map inventory for one server.
- `maps.list.response` — return canonical map entries for one server.
- `maps.remove.request` — request removal of one `.msav` map file from one server.
- `maps.remove.response` — return the server result for a remove request.
- `maps.upload.request` — upload maps and await per-file installation/attribution results.
- `maps.upload.response` — report installed, failed, superseded or rejected files in request order.
- `maps.inspect.request` — inspect one `.msav` without installing it; an absent profile is inferred from map rules.
- `maps.inspect.response` — return inspection status, optional map metadata and open-code findings.
- `maps.load.command` — command one server to download and load uploaded `.msav` files.
- `maps.preview.request` / `maps.preview.response` — the thumbnail of one installed map, as a blob.
- `maps.file.request` / `maps.file.response` — one installed map file, published as a blob.
- `maps.render.request` / `maps.render.response` — a detailed render from the map renderer service (`xcore:rpc:req:map-renderer`).

## Blobs
Images and map files travel as `MapBlobRefV1`: a Redis string `xcore:blob:{sha256}` with a TTL, referenced
from the message. Stream entries stay small; readers check `sizeBytes` and `sha256` before use.

## Field Policy
- Payload fields use camelCase.
- Legacy aliases such as `file_name` and legacy upload key `urls` are consumer migration concerns, not canonical protocol fields.
- Map filenames are represented as `.msav` basenames, not paths.
