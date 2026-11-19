# Account merges

Staff can merge one player account (the *source*) into another (the *target*) from the server
console (`merge-player`, the plugin's `AccountMergeService`) or from Discord (`/merge-player`, the
bot's `account_merge_store.py`). Both must leave the database in the same state. This file says what
that state is; the cases below show it, and both implementations' tests run every case.

## What a merge does

A merge is all or nothing. It is refused, and nothing changes, when the two are one account, when
either account is already closed (its uuid starts with `merged:`), or when the target's
`player_totals.redirect_uuid` chain leads back to the source.

The *surviving PID* is the target's. Staff on Discord may keep the source's instead
(`keep_pid=source`), and then the two accounts swap PIDs. The console merge has no such option.

### `players`, the target (the account that survives)

| Fields | After the merge |
|---|---|
| `uuid` | the target's |
| `pid` | the surviving PID |
| `total_play_time`, `hexed_points` | the sum of both |
| `hexed_rank` | the rank the summed points reach, by [`hexed-ranks.v1.json`](../hexed-ranks.v1.json) |
| `legacy_pvp_rating` | the higher of the two |
| `unlocked_badges`, `blocked_private_uuids` | both sets together |
| `map_votes`, `event_votes` | the target's votes, plus the source's on maps and events the target has not voted on |
| `username`, `custom_nickname`, `description`, `active_badge` | the target's, or the source's where the target's is empty |
| `can_change_username` | true if either could |
| `discord_id`, `discord_username`, `discord_linked_at` | the target's link; the source's link, all three fields, if the target has none |
| `discord_link_generation` | raised to at least `discord_linked_at` |
| `password_hash`, `device_tokens`, `device_token_hashes`, `is_admin`, `admin_source` | the target's own; nothing comes from the source |
| everything else | unchanged |

### `players`, the source (the account that is closed)

| Fields | After the merge |
|---|---|
| `uuid` | `merged:` + its uuid |
| `pid` | unchanged, or the target's PID with `keep_pid=source` |
| `username`, `password_hash` | `""` |
| `can_change_username`, `is_admin` | false |
| `admin_source` | `"NONE"` |
| `device_tokens`, `device_token_hashes` | empty |
| `discord_id`, `discord_username` | `""` |
| `discord_linked_at` | 0 |
| `discord_link_generation` | raised to at least 0 (it is written even when it was missing) |
| `total_play_time` | 0 |
| `description` | `Merged into PID #<surviving PID> (<target's nickname>)` |
| everything else | unchanged |

### `bans` and `mutes`

Each account's ban and mute is the document with its uuid. One is *active* if it has no
`expire_date` (a permanent ban), or one in the future.

- The source's ban or mute is copied to the target only if it is active and the target has no
  active one of its own. An expired punishment restricts nobody, so it does not follow the player.
  An active one on the target stands, and the source's stays where it is.
- The copy is the source's document with `uuid` set to the target's uuid, `pid` to the surviving PID,
  `name` to the target's nickname, and `reason` prefixed with `[Merged from #<source's PID>] `.
  Every other field is kept, `case_number`, `admin_discord_id`, `expire_date` and timestamps among
  them. A ban copy also gets the next `ban_id` from `counters.ban_id`, because the original keeps its own.
- If the target has an expired ban or mute, the copy replaces it (it keeps that document's `_id`).
  There is one ban and one mute per uuid, and the join-time ban check reads the first match.
- A copied ban takes the IP with it: the original loses its `ip` (`$unset`). The original keeps
  banning the source's old uuid, and lifting the copy lifts the IP as well. A ban that is not copied
  keeps its IP.
- With `keep_pid=source`, the source's punishments get the target's old PID and the target's own
  get the surviving PID. Otherwise their `pid` stays as it is.
- A ban on an IP alone (no `uuid`) belongs to no account and is left alone.

An appeal finds a ban by `ban_id`, then by uuid, then by IP. The copy has its own `ban_id`, the
surviving account's uuid and the banned IP, so it is found in each of these three ways.

### `games_v2`, `player_totals`

- Every `player_stats` entry with the source's uuid gets the target's uuid.
- Both accounts' `player_totals` are invalidated, created if missing: `revision` is incremented by
  an int64 1, `rebuild.dirty` is set, `rebuild.queued_at` is lowered to the merge time, and a new
  document gets `status: "pending"` and `schema_version: 1`. The source's gets `redirect_uuid` set to
  the target's uuid.

### Not part of the shared result

These are listed in `compare.json`:

- `moderation_audit`: each implementation appends one `MERGE` entry in its own format.
- `rating_standings`, `rating_matches`: the plugin moves them in the merge's transaction; the bot
  asks a game server to move them afterwards (`rating.accounts.merge.request`), which runs the
  plugin's own code.

What each entry point does around the merge also differs. The bot refuses while either account
is online. The console merges from the game server's session cache, kicks the source and refreshes
the target, then tells the other servers to reload their caches.

## Cases

`<case>/case.json` describes the case and holds the request:
`{"source_pid": 10, "target_pid": 20, "keep_pid": "target"}`. `"refused": true` means the merge
must be refused. `<case>/before/<collection>/` holds the database before the merge, and
`<case>/after/<collection>/` holds what the collection holds after it. The rules are the
migrations' (see [../README.md](../README.md)):

- A collection named in `before` and missing from `after` must come out unchanged.
- Every other collection, apart from those in `not_compared`, must stay empty.
- A refused case has no `after`.

Comparison, as both runners do it:

- Documents are matched by `_id`. A document whose `_id` is an ObjectId not in `before` was created
  by the merge, and its id is not compared, since the merge generates it.
- Numbers compare by value whatever their width, and arrays compare as sets.
- The fields in `compare.json`'s `stamped` hold the time of the merge (or, for `updated_at`, the
  plugin's save time) and are not compared.

Every document in `before` and `after` must pass its collection's schema, and the components'
storage contract tests read them as stored shapes too.

Runners:

- plugin: `plugins/xcore/src/test/java/org/xcore/plugin/database/storage/StorageMergeContractIntegrationTest.java`
- bot: `services/discord-bot/tests/test_merge_contract.py`

`keep-source-pid` runs only in the bot, since the console merge cannot keep the source's PID.
