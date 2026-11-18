# ADR: One writer for each shared MongoDB collection, starting with moderation

## Status
Proposed

## Context
The plugin, the Discord bot and the website share one MongoDB database. `protocol/storage` now
describes the shared collections as JSON Schemas with fixtures, and each component's tests check
its readers and writers against them (see `protocol/storage/INVENTORY.md`). That catches a renamed
field in CI. It does not remove the reason fields drift: several collections have more than one
writer, and each writer encodes the same rules in its own language.

Moderation is where this costs the most. `bans`, `mutes`, the `ban_id` sequence in `counters` and
`moderation_audit` are each written by the plugin and by the bot:

- **Two shapes for the same fact.** A permanent ban from the plugin has no `expire_date` (its POJO
  codec skips nulls); one from the bot has `expire_date: null`. Until the storage contract surfaced
  it, the bot's appeal flow treated a row without the key as no ban, so a permanent ban issued in
  game could not be appealed from Discord. "No IP" and "no moderator id" have two or three shapes
  each in the same way.
- **Fields lost on rewrite.** The bot wrote `pid` and `case_number` on mutes, but the plugin's
  `MuteData` had neither, and its save replaces the document, so a mute the plugin wrote dropped
  both. The plugin's model now carries them, and a contract test checks that replacing any stored
  ban or mute keeps every field.
- **Two implementations of one operation.** An account merge exists in the plugin
  (`AccountMergeService`) and in the bot (`account_merge_store.py`). They copy a ban differently: the
  plugin gives the copy the target's IP, the bot keeps the source's IP on the copy and removes it
  from the original. The bot also recomputes Hexed ranks from its own copy of the thresholds.
- **A shared sequence.** Both components allocate ban numbers from `counters.ban_id`. The unique
  index on `bans.ban_id` only covers 32-bit ints, so a writer that ever stores an int64 escapes it.
- **Several plugin versions at once.** Servers take a new jar when they restart, so for days old
  and new plugin versions write the same documents. V10 had to stamp `rules_version` on
  `player_totals` so old jars would leave migrated players alone; every such guard is invented ad
  hoc.

## Options

### A. An append-only moderation log with read projections
Every component appends events (`ban`, `unban`, `mute`, `ip-released`, ...) to one log; a
projector folds them into `bans` and `mutes`, which become read-only views.

- Appends never conflict, so there is nothing to coordinate between writers.
- `moderation_audit` is already close to such a log.
- But the projector is a new process with its own deploy and failure modes, and a projection lags
  the log. The join-time ban check reads `bans`; a player banned a moment ago could reconnect in
  the gap, so the check would have to read the log as well.
- The log's event schema becomes the shared contract instead of the documents, and both writers
  still encode the rules of when to append what. The duplication moves; it does not go away.
- Existing bans and mutes need a backfill into events, and old jars keep writing the projections
  directly until every server restarts.

### B. Route the bot's moderation writes through the plugin over protocol RPC
The bot stops writing moderation collections. It sends an RPC request to a live game server (as
the website already does for Discord linking with `discord.account.operation.request`, chosen by
heartbeat, carrying an idempotency key and answered from `account_operation_receipts`), and the
plugin's `ModerationService` performs the change. It already implements bans, mutes, unbans and IP
releases for in-game commands; expiry changes (appeal outcomes in the bot today) would be new.

- One implementation of each rule: ban number allocation, PID lookup, the shape of "permanent",
  audit entries, and the merge.
- The protocol gains request/response messages, which are versioned, generated and
  compatibility-tested like every other message.
- The bot's Discord-side state (cases, notices, panels) stays where it is; only the shared
  collections move behind the plugin.
- The pattern exists, is tested, and is in production for linking.

### C. A schema version on every document
Each document carries `schema_version`. Readers handle every version they know and refuse newer
ones; writers never overwrite a document whose version is newer than theirs.

- Makes a fleet with mixed plugin versions safe in general, instead of one guard per migration.
- But it keeps every writer. Both components still encode the same rules, they just refuse each
  other's newer documents. It addresses mixed versions of one writer, not two writers.
- Every write becomes conditional on the stored version, and the existing `version` field cannot
  be reused: it is always 1, V1 back-filled it as an int32, and the bot never writes it.

## Decision
Adopt **B** for moderation: the plugin becomes the only writer of `bans`, `mutes`,
`counters.ban_id` and the moderation entries of `moderation_audit`. The bot asks a live server to
perform each change and reads the result back.

B is the only option that removes a writer rather than coordinating two. The disagreements above
are all of the form "two programs implement one rule"; with one implementation they cannot occur.
It reuses a mechanism the system already runs, and it leaves the documents' shape as it is, so the
storage contract and its fixtures stay valid throughout.

C is kept for the problem it does solve. When a migration changes a shape that old jars would
overwrite, give that collection a `schema_version` and a guarded write, as V10 did with
`rules_version`, rather than inventing a new guard each time.

## Costs
- **Discord moderation needs a live game server.** When none answers, the bot cannot ban. The bot
  must say so and offer a retry; moderation from Discord during a full outage stops. If that is
  unacceptable, the fallback is a single-writer queue the plugin drains on start, which is option A
  in miniature.
- **A protocol release and a staged rollout.** The new messages ship in a protocol release. Until
  every server runs a jar that handles them, the bot keeps its direct writes behind a check: the
  heartbeat (or a capability flag in it) says whether the chosen server supports the RPC.
- **Latency.** A Discord ban waits for a round trip through Redis and the game server, as linking
  does today (about ten seconds at worst before it reports "unknown").
- **The bot's account merge moves too.** Its merge writes `players`, `bans`, `mutes`,
  `player_totals` and `games_v2` in one transaction. It has to become an RPC to the plugin's
  `AccountMergeService`, and the two merges must be reconciled first (IP handling, Hexed ranks).
- **Receipts and idempotency.** Each operation needs a receipt so a retried request is not applied
  twice, as `account_operation_receipts` does for linking.

## Rollout
1. Fix the shape disagreements in place while both writers exist, each with its schema and
   fixtures in `protocol/storage`. Done for the two that lost data: the appeal reader accepts a ban
   without `expire_date`, and mutes keep `pid` and `case_number`.
2. Add `moderation.*.request` / `.response` RPC messages for ban, unban, mute, unmute, IP release
   and expiry changes, with receipts, and handle them in `ModerationService`.
3. Switch the bot to the RPC per operation. It decides before sending: if the chosen server does
   not support the RPC, it writes directly as today. Once a request is sent, it never falls back
   to a direct write: if the answer is lost, it retries with the same idempotency key until the
   receipt settles the outcome, because the server may already have applied the change.
4. Move the account merge.
5. Remove the direct writes once every server has restarted onto a jar with the handlers. Only then
   remove `bot` from `x-written-by` of each field, when no path in the bot (fallback or merge)
   still writes it.

## Typed document models
The natural next step after the schemas is to generate document models (Java records, Python
dataclasses, TypeScript types) from `protocol/storage/schemas`, so each component's reader is typed
by the contract instead of by hand.

This does not fall out of `protocol/generators` cheaply. The generators read `spec/` message
families and route manifests and emit wire DTOs whose fields are JSON types; storage documents need
BSON types (ObjectId, date, int32 versus int64) mapped per language and per driver, tolerate legacy
shapes the wire models must reject, and in the plugin would replace or wrap the Lombok POJOs the
codec maps today. It is a separate generator target, worth doing once B has reduced the writers to
one per collection, when the plugin's models can be generated outright and the bot and website
only need read models.

## Consequences

### Positive
- One implementation of each moderation rule, so its stored shape has one source.
- The ban number sequence has a single allocator.
- The storage contract shrinks: each moderation field has one writer to test.

### Costs
- Discord moderation depends on a live game server.
- New protocol messages, a release, and a transition with two code paths in the bot.
- Moving the account merge is the largest piece and needs the two merges reconciled first.

## Acceptance Criteria For This Decision
- The moderation RPCs are specified in `protocol/spec` with fixtures and compatibility tests.
- `bans.v1.json`, `mutes.v1.json` and the `ban_id` counter list only `plugin` in `x-written-by`.
- The bot's contract test checks that it no longer writes those collections.
