# Changelog

## 0.18.0

- Add `MapBlobRefV1`: bytes stored beside a message instead of inside it, as a Redis string `xcore:blob:{sha256}` with `sha256`, `sizeBytes` (at most 16 MiB), `contentType` (`image/png` or `application/octet-stream`), optional `width`/`height` and `expiresAt` in epoch milliseconds. Readers check size and hash before using the bytes. RPC response streams keep thousands of entries, so images and map files never go into them.
- Add the `maps.preview.request` / `maps.preview.response` v1 RPC on `xcore:rpc:req:{server}`: the thumbnail of one installed map (one pixel per tile, scaled up to at most `maxSide`, default 512), with `status` `ok`/`not_found`/`failed`, the map's `contentHash` and the image blob.
- Add the `maps.file.request` / `maps.file.response` v1 RPC on `xcore:rpc:req:{server}`: a server publishes one installed map file as a blob, with `status` `ok`/`not_found`/`too_large`/`failed`.
- Add the `maps.render.request` / `maps.render.response` v1 RPC on `xcore:rpc:req:map-renderer` (target scope `service`, owner `map-renderer`): a detailed render of the map in a file blob, bounded by `maxSide` (default 4096) and `maxBytes` (default 8 MiB), with or without the game's darkness over static walls and outside the map (`darkness`, default true). The response carries `status` `ok`/`busy`/`too_large`/`invalid_map`/`download_failed`/`failed`, the image blob, `scale` (pixels per tile), `renderMs` and an optional `reason`.
- Add optional `contentHash` (SHA-256 of the map file) to `MapEntryV1`. Java's `MapEntryV1` constructor gains a final nullable `String` argument.
- Deploy the bot before servers send `contentHash` or answer the new RPCs: strict 0.17 readers reject the new field.

## 0.17.0

- Add the `discord.account.operation.request` / `discord.account.operation.response` v1 RPC: one targeted link or unlink on `xcore:rpc:req:{server}` with a UUID business `operationId`, epoch-millisecond `issuedAt`/`expiresAt`, `code` (required for link) and `expectedLinkedAt` (required for unlink). The response carries `status` `success`/`rejected`/`failed`/`unknown`, an optional `reason` and the stored `linkedAt`.
- Generate TypeScript types for these messages into `typescript/discord-accounts.ts`; `generate`/`check` cover them (`--language typescript`).
- `discord.link.confirm.command` and `discord.unlink.command` stay for servers that have not moved to the RPC yet.

## 0.16.0

- Remove `discord.admin-access.changed.command` v1 and `player.password-reset.command` v1, with their routes, generated Java/Python models and fixtures. Staff rights come only from grants: Discord roles reach the servers through `security.staff.sync`, password resets through `security.staff.reset-password`.
- Breaking for consumers that still reference `DiscordAdminAccessChangedCommandV1`, `PlayerPasswordResetCommandV1` or their route constants; upgrade the bot and the game servers to releases without them first.

## 0.15.0

- Add the cases family: durable report events, notice wake-up commands and live evidence RPC, with generated Java/Python models and routes.
- Add detached map inspection RPC with typed metadata, status and findings; reason and finding codes remain open strings.
- Add shared case chat, block summary and map inspection types. Nested block entries and areas use named shared types; chat PIDs retain the repository's signed PID policy.
- Map inspection counts and dimensions, and per-block action counts, are nonnegative. Inspected map `name`/`author` are capped at 256 characters, `description` at 1024 and `mods` at 32 entries; servers truncate metadata before sending. Evidence responses carry at most 30 chat lines.
- Add optional positive `banId` to `moderation.ban.created` v1. Existing payloads without it remain valid; the Java constructor gains a final nullable `Integer` argument.
- Deploy 0.15.0 bot readers before servers emit `banId`; older strict readers reject it. Roll servers back before reverting the bot.
- Schema validation remains authoritative for upper bounds, formats and the report-specific omission of player IPs. Generated models enforce the established supported subset.

## 0.14.0

- Add maps upload RPC with typed per-file results; retain the existing load command.
- Add optional uploader and declared-creator Discord handle snapshots.
- Enforce scalar minima, minimum string lengths, patterns and minimum array sizes in generated Python model constructors and decoders, matching schema constraints.
- New readers accept 0.13 payloads; strict 0.13 readers do not accept new handle fields or upload RPC. Coordinate server/bot upgrades. Java constructors for MapDiscordUploadV1 gain two nullable fields.

## 0.13.0

- Added optional `discordUpload` to `MapFileSourceV1` and `MapEntryV1`, carrying the uploader's Discord identity, submission time in epoch milliseconds and an optional declared creator.
- Generated Java and Python `MapDiscordUploadV1` models and added valid, invalid and roundtrip fixtures.
- Existing v1 payloads without attribution remain valid. Older strict decoders reject the new field: deploy the updated bot and map servers together.
- Java record constructors for `MapEntryV1` and `MapFileSourceV1` gain an optional attribution argument. Pass `null` when no attribution is available.
