# Discord map submission attribution

File author, declared creator and uploader are independent. The author embedded in
`.msav` keeps its existing role in statistics; selecting a Discord creator is a
reviewer's declaration, not verified authorship.

`/map upload` snapshots the interaction user's ID, display name and handle. Optional
`creator` is the default for all attachments; `creator1`, `creator2`, `creator3`
override it by original attachment position, even when another attachment is skipped.
An omitted creator is not inferred from the uploader. Names and handles are snapshots;
IDs remain the stable reference after renaming or leaving Discord.

Protocol 0.14.0 adds optional `uploadedByHandle`/`creatorHandle` to `discordUpload`:

```json
{
  "uploadedBy": {"discordId": "123456789012345678", "discordUsername": "Reviewer"},
  "uploadedByHandle": "reviewer",
  "uploadedAt": 1791806400000,
  "creator": {"discordId": "234567890123456789", "discordUsername": "Mapper"},
  "creatorHandle": "mapper"
}
```

`uploadedAt` is Unix epoch milliseconds. Attribution remains optional in files and
map entries. Generated Python models enforce schema minima, minimum lengths, patterns
and minimum array sizes, including direct constructors.

## Storage and identity

`map_uploads` holds the latest installation per exact `(server, fileName)`; its `_id`
is an injective length-prefixed server/filename key. Each row contains the SHA-256 of
the downloaded bytes and optional attribution. `map_upload_history` stores advisory
append-only snapshots, including removals. A history insert failure is logged without
claiming that an acknowledged current record was lost.

Attribution writes never rename or overwrite documents in the statistics collection
`maps`. After a successful installation, the existing `findOrCreate` path runs on a
storage worker to keep voting available for fresh maps. Lookup is by filename, then by name and author for a renamed
file; the second step never hands a record to another file while the file it is stored under is
installed, so two installed files with the same name and author keep separate statistics
(map-updates-design-spec.md, stage 3). No automatic merging
based only on display names is introduced. Other servers and game-mode statistics cannot
be modified by attribution writes.

Attribution is shown only when the server, exact filename and current installed SHA-256
match. A separate in-memory cache is populated on storage workers after the identity
catalog hashes the installed files. Both game menus and Discord read this same cache;
a statistics alias cannot hide the installed file's attribution or expose an alias's.
Catalog generations and cache revisions discard obsolete asynchronous reads. A reload
clears the old cache first. Server load, upload and removal rebuild it. World load refreshes attribution without
clearing the verified cache or hashing unchanged files. File changes must go through
a catalog rebuild (plugin upload/removal paths do this automatically). Refreshes are
blocked while hashing is in flight, so old hashes cannot repopulate a cleared cache.
No new Mongo query is added to synchronous `findOrCreate` calls on the game thread.

For transition from 0.13.0, a missing installation row permits read-only lookup of legacy
`maps.discord_upload` by exact filename **and matching content hash**. Legacy rows do not
have server provenance; identical bytes may reuse their attribution until a scoped row
is written. A scoped row, including a removal tombstone or an upload without attribution,
suppresses this fallback. Replacing a file manually with different bytes never matches
an old submission. No history is invented for old records.

## Upload processing and confirmation

The bot uses `maps.upload.request`/`maps.upload.response`. The legacy `maps.load.command`
remains supported. Up to three files are downloaded in parallel, with a 30-second HTTP
timeout. Fixed worker-side lock stripes serialize writes of the same physical filename
without blocking the game thread or doing I/O inside `ConcurrentHashMap.compute`.

Every write gets a distinct installation token, including identical bytes. Delayed
batches and queued metadata writes must still match their token. Persistence is ordered
per file; failures do not block successors. Tokens are retired once all batches using
a filename finish, preventing unbounded growth. Unsafe and duplicate transport filenames
are rejected. Failed downloads are not attributed. A missing registry entry is reported
as an invalid map. Removal writes an unconditional scoped tombstone in the same persistence queue; a
concurrent download cannot cancel it. A subsequently accepted upload is queued later.

The response reports each file as `installed`, `download_failed`, `invalid_map`,
`superseded`, `metadata_failed` or `rejected`. `metadata_failed` explicitly means the map
was installed but attribution was not saved. Confirmation waits for catalog refresh so
an immediate `/map list` can see the result. Corrupt stored attribution is omitted per
map rather than breaking the entire list. A client timeout says the result is unconfirmed;
it does not claim installation failed or automatically retry a potentially applied action.

## Reviews, conditional writes and the trash (protocol 0.19.0)

`discordUpload` may also carry the review a file came from: `caseNumber`, `threadId` (the
forum post) and `revision`, and `fixedBy`/`fixedByHandle` for a fix of someone else's map.
`creator` stays the original creator; the fixer is recorded next to it. Both game menus,
`/maps list` (🔧, "rev. N") and the `/map` card show them; the card links the post from
`threadId` when the bot's own case store has no review for those bytes.

A file source may say what the server must still hold for the write to go ahead:
`expectedContentHash` (the SHA-256 of the file the reviewer saw under that name) or
`expectAbsent` (no file under that name). The check runs under the same lock stripe as the
write; a mismatch is reported as `conflict` and nothing is written. `maps.remove.request`
takes `expectedContentHash` the same way and its response carries `status`
(`removed`/`not_found`/`conflict`/`failed`). The bot sends the expectation from the map list
it showed: `/maps upload`, accepting a review, `/maps rollback`, `/maps remove` and
**Restore**. A server whose list could not be read gets no expectation, as before.

Instead of deleting a removed file or silently overwriting a replaced one, the server sets it
aside in `<custom maps>/.trash` as `<trashId>.msav.kept` with a `<trashId>.json` sidecar (file
name, hash, size, time, reason and the attribution it had). The engine only loads `*.msav`
directly in the maps directory, so nothing there is offered as a map. Upload results and the
removal response return `trashId` (and the hash), `maps.trash.list` lists the entries and
`maps.restore` puts one back, under its own name or a given one, with its attribution; a name
that is taken is a `conflict`. Retention is configured in the server secrets:

```toml
[maps.trash]
retention_days = 30   # entries older than this are deleted when a file is kept
max_entries = 200     # the oldest beyond this are deleted too
```

The bot's **Restore** button tries the server's own copy first and falls back to the copy it
posted in Discord (uploaded with `expectAbsent`); `/maps trash <server>` lists what a server
kept and puts a chosen file back. Older servers lack these RPCs; the bot falls back where it
can, but servers and bot must still be deployed together (strict decoders reject the new
fields). Order: publish the protocol, upgrade servers, restart the bot.

## Presentation

Discord lists use a compact attribution line with profile links and a legend for creator
and uploader. Link-label brackets, parentheses and existing Markdown escapes are handled
without letting a saved name insert a URL. Mentions are disabled in upload messages.
Pages retain sorting, at most 15 entries, and a 3800-character description budget below
Discord's 4096 limit. `/map list creator:@user` filters by stable declared-creator ID.

Both game interfaces distinguish the file author and show declared creator, uploader and
UTC date. Display names and handles are sanitized; IDs are shown to administrators.
The declared creator of the next map appears in the game-over menu and is announced after
world load. All nine locales share the labels. A previous upload's display name/handle is
not fetched from Discord at render time.

## Release and rollout

Both consumers require protocol 0.14.0. Publish the protocol first, pause bot map commands,
upgrade servers, then restart the bot so its normal slash-command sync exposes the new
options. Verify a partial upload, an omitted creator, a repeated upload and both map UIs.
Older strict decoders reject the optional handle fields and lack the upload RPC; deploy
server and bot together. Collections are additive and require no destructive migration.
A rollback reads legacy statistics unchanged; it cannot display new scoped attribution.

Validation: protocol generation/fixtures/Java-Python compatibility; real Mongo POJO codec
round-trip; server/name/hash isolation; alias statistics and both menus; ordered/stale
uploads, partial RPC results and cache generations; Discord label injection, creator
positions/filtering, pagination and timeout handling. For unpublished protocol changes,
use `gradle/local-protocol.init.gradle` and the sibling sources generated for Python; release
builds use the published dependency and exact Git tag.
