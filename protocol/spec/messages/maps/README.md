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
- `maps.trash.list.request` / `maps.trash.list.response` — the map files a server set aside instead of deleting (removed, or replaced by an upload).
- `maps.restore.request` / `maps.restore.response` — put one of them back, under its own name or another.
- `maps.render.request` / `maps.render.response` — a detailed render from the map renderer service (`xcore:rpc:req:map-renderer`).

## Blobs
Images and map files travel as `MapBlobRefV1`: a Redis string `xcore:blob:{sha256}` with a TTL, referenced
from the message. Stream entries stay small; readers check `sizeBytes` and `sha256` before use.
Game servers publish previews and map files, the renderer its renders, and the Discord bot map files
it holds itself (a file submitted for review), always under the bytes' own SHA-256.

## Renderer presence
The map renderer keeps the Redis string `xcore:service:map-renderer` (JSON with `version`,
`concurrency` and `inFlight`) with a 30 s TTL while it consumes requests. Clients may skip
`maps.render` while the key is absent instead of waiting out the RPC timeout.

## Conditional writes
An upload file may carry `expectedContentHash` (replace only that exact file) or `expectAbsent` (install only under a free name), and a removal `expectedContentHash`. When the condition does not hold, the server changes nothing and answers `conflict`, so a reviewer never overwrites or removes a file other than the one they looked at. Removed and replaced files are kept aside (`trashId`).

## Field Policy
- Payload fields use camelCase.
- Legacy aliases such as `file_name` and legacy upload key `urls` are consumer migration concerns, not canonical protocol fields.
- Map filenames are represented as `.msav` basenames, not paths.
