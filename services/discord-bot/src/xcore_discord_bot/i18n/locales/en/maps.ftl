cmd-map-description = Show one map in detail
cmd-map-param-server-description = Server name
cmd-map-param-file_name-name = map
cmd-map-param-file_name-description = The map
cmd-maps-review-name = review
cmd-maps-review-description = Start map review in this forum post
maps-file-needed = Please attach the map as a .msav file in this post. Its author must upload the file.
maps-review-in-post = Use /maps review inside a post in the maps forum.
maps-review-no-file = No map review started. Check the post's tags and attach a .msav file from its author.
maps-map = Map
maps-file = File
maps-file-info = { $file } · { $size } bytes
maps-mode = Mode
maps-dimensions = { $width } × { $height }
maps-validation = File validation
maps-checks = Deep checks
maps-checks-pending = Waiting for a game server. The bot will retry the deep checks.
maps-accept = Accept
maps-recheck = Re-check
maps-rechecked = The map checks have been refreshed.
maps-select-server = Choose the server to install this map on.
maps-no-server = No live server is available. Try again later.
maps-accept-anyway = This map has error-level findings. Accept anyway and install it?
maps-accept-pending = Deep checks are still pending. Install this map without their results?
maps-not-valid = This file has not passed the container and attachment checks. Re-check it before accepting.
maps-file-changed = The reviewed attachment is missing or has changed. Re-check the latest file before accepting.
maps-review-changed = The map or its checks changed during this review. Press Accept again to review the current results.
maps-upload = Upload result
maps-upload-unknown = The upload outcome is unknown. Another upload is blocked. Staff must check the server; the bot will recover acceptance if the matching installed audit appears.
maps-upload-result = { $file }: { $status }. { $detail }
maps-upload-status-installed = installed
maps-upload-status-download_failed = download failed
maps-upload-status-invalid_map = invalid map
maps-upload-status-metadata_failed = metadata could not be saved; check the installed file before retrying
maps-installed = Installed map
maps-installed-info = { $file } · { $server }
maps-outcome-accepted = Your map { $file } was accepted in case #{ $number } and installed on { $server }.
maps-outcome-rejected = Your map was rejected in case #{ $number }. Reason: { $reason }
maps-severity-error = Errors
maps-severity-warning = Warnings
maps-severity-info = Information
maps-more = … and { $count } more
maps-finding-count = count: { $count }
maps-invalid-extension = Please attach a .msav file.
maps-invalid-size = The file is empty, exceeds the size limit, or its size has changed.
maps-invalid-inflation = The unpacked file exceeds the hard size limit.
maps-invalid-truncated = The file is truncated.
maps-invalid-zlib = The file is not a complete zlib stream.
maps-invalid-magic = The file has no valid MSAV header.
maps-invalid-version = This save version is not supported (versions 1–13 are supported).
maps-invalid-meta = The file's metadata is malformed or exceeds the size limit.
maps-invalid-unavailable = The attachment could not be read. The bot will retry.
maps-finding-size-out-of-range = Map dimensions exceed the inspection limits
maps-finding-no-core = No core found
maps-finding-no-spawn = No enemy spawn found
maps-finding-pvp-single-team = PvP requires cores for at least two teams
maps-finding-attack-no-enemy-core = The enemy team has no core
maps-finding-td-road-floor-mixed = Spawn tiles use different road floors
maps-finding-td-road-floor-unexpected = The road uses an unexpected floor
maps-finding-td-road-disconnected = The road does not reach a core
maps-finding-td-spawn-not-at-road-start = A spawn is in the middle of the road
maps-finding-td-border-wall-missing = Road border walls are missing
maps-finding-td-drop-zone-radius = The drop zone radius is not zero
maps-finding-td-waves-enabled = Vanilla waves are enabled
maps-finding-invalid-map = The server could not read this map
maps-finding-inspection-mismatch = The server inspected different bytes; re-check before accepting
maps-upload-status-superseded = superseded by another upload
maps-upload-status-rejected = rejected by the server
maps-finding-missing-name = The map has no name
maps-finding-missing-author = The map has no declared author
maps-finding-requires-mods = The map requires mods
maps-finding-data-patches = The map changes gameplay with data patches; the other checks ignored them
maps-patches-attached = Data patches in this map ({ $count }), file { $sha }:
maps-preview-attached = Render of this map, file { $sha }:
maps-file-updated = Review #{ $number } now checks { $file }. Results are in its card: { $card }
maps-file-misnamed = { $file } was not checked: a map's file name must end in .msav. Rename it and attach it again.
maps-finding-unknown-content = The map uses content unknown to this server
maps-badge-link-needed = The map's author has no linked game account, so the Map maker badge is not granted yet. The bot grants it on its own once the account is linked (within 30 days).

# /maps list

maps-list-timeout = No response from target server (timeout).
maps-list-empty = No maps found on server `{ $server }`
maps-list-empty-creator = No maps found on server `{ $server }` for this creator.
maps-list-title = Maps on `{ $server }`
maps-list-footer = Sort: { $sort } • Page { $page }/{ $pages } • total maps: { $total } • entries on page: { $shown }
maps-list-open = Open a map…
maps-list-open-gone = This map is no longer in the list.
maps-list-legend = 🎨 declared creator • 🔧 fixed by • ⬆ added via Discord
maps-list-sort-reputation = reputation
maps-list-sort-popularity = popularity
maps-list-sort-name = name
maps-list-unknown = Unknown
maps-list-by = by `{ $author }`
maps-list-file-author = file author `{ $author }`
maps-list-reputation = rep { NUMBER($reputation, useGrouping: 1) }

# /map

maps-card-not-found = There is no map `{ $file }` on `{ $server }`.
maps-card-author = Author
maps-card-size = Size
maps-card-mode = Mode
maps-card-rating = Rating
maps-card-activity = Activity
maps-card-activity-value = popularity { NUMBER($popularity, maximumFractionDigits: 2) } • interest { NUMBER($interest, maximumFractionDigits: 2) }
maps-card-discord = Added via Discord
maps-card-file = File
maps-card-review = Review
maps-card-footer = Server: { $server }
maps-render-time = Rendered in { NUMBER($seconds, maximumFractionDigits: 2) } s
maps-card-render-button = Detailed render
maps-card-layers = Render layers…
maps-card-layer-buildings = Buildings
maps-card-layer-buildings-hint = Blocks and their shadows; off shows the terrain only
maps-card-layer-darkness = Wall darkness
maps-card-layer-darkness-hint = Darkens static walls and the map edge, as in game
maps-card-layer-ores = Ores
maps-card-layer-ores-hint = Dims everything but ores, to judge resources
maps-card-layer-grid = Coordinate grid
maps-card-layer-grid-hint = A line every 10 tiles, coordinates every 50
maps-card-layer-spawns = Spawns and drop zones
maps-card-layer-spawns-hint = Where enemies appear; units inside the circle die
maps-card-render-timeout = The renderer did not answer in time. Try again later.
maps-card-render-busy = The renderer is busy with other maps. Try again in a minute.
maps-card-render-cooldown = You can start a new render in { $seconds } s.
maps-card-render-failed = Could not draw this map.
maps-card-render-unavailable = The renderer is not running right now. Try again later.

# Comparing a file with the maps already installed

maps-match-same-bytes = same file contents
maps-match-same-file = same file name
maps-match-same-title = same map name and author
maps-candidate = { $file } — { $name } by { $author } ({ $reasons })
maps-choice-expired = No choice was made in time; nothing was changed.
maps-target-choose = A map like this one is already installed on `{ $server }`. Choose what this file does:
maps-target-replace = Replace { $file }
maps-target-new = Install as a new map { $file }
maps-target-overwrite = Overwrite { $file } (another map)
maps-target-unlisted = Could not read the map list of `{ $server }`, so duplicates were not checked. Install the map as { $file } anyway?

# /maps upload

cmd-maps-upload-param-replace-description = Installed map this file replaces (one file only)
maps-upload-checking = Checking { $count } map file(s) against `{ $server }`…
maps-upload-uploading = Uploading { $count } map file(s) to `{ $server }`…
maps-upload-invalid-no-files = No valid .msav files attached.
maps-upload-invalid-same-names = Each attachment must have a different filename.
maps-upload-invalid-invalid = The upload details are invalid; nothing was sent to the server.
maps-upload-replace-one = `replace` works with exactly one .msav file.
maps-upload-replace-missing = There is no map { $file } on `{ $server }` to replace. Nothing was uploaded.
maps-upload-confirm-title = Check before uploading to `{ $server }`:
maps-plan-new = { $file } — a new map
maps-plan-same = { $file } — this exact file is already installed
maps-plan-update = { $file } — updates the installed version
maps-plan-replace = { $source } → replaces { $file }
maps-plan-unchecked = { $file } — not checked against the installed maps
maps-plan-overwrite = { $file } — ⚠️ overwrites a different map:
maps-plan-duplicate = { $file } — ⚠️ looks like a map that is already installed:
maps-upload-button-replace = Replace { $file }
maps-upload-button-as-is = Upload as is
maps-upload-button-cancel = Cancel
maps-upload-cancelled = Upload cancelled; nothing was sent to the server.
maps-upload-result-title = Maps on `{ $server }`:
maps-upload-row = { $status } — { $file }
maps-upload-label-installed = ✅ Installed; attribution saved
maps-upload-label-download_failed = ❌ Download or file write failed
maps-upload-label-invalid_map = ❌ Invalid map file
maps-upload-label-superseded = ⚠️ Replaced by a newer upload
maps-upload-label-metadata_failed = ⚠️ Installed; attribution was not saved
maps-upload-label-rejected = ❌ Rejected filename
maps-upload-note-new = new map
maps-upload-note-update = updated the installed version
maps-upload-note-same = the same file was already installed
maps-upload-note-replaced = from { $source }
maps-upload-unchecked = Could not read the map list of `{ $server }`: duplicates were not checked.
maps-upload-timeout = The server has not confirmed the result yet. Some files may already be installed; check /maps list before retrying.
maps-upload-unconfirmed = Could not confirm the upload result. Check /maps list before retrying.

# /maps remove

maps-remove-empty = Map file name must not be empty.
maps-remove-question = Remove { $file } from `{ $server }`?
maps-remove-unlisted = ⚠️ Could not read the map list of the server, so this map could not be shown.
maps-remove-siblings = Other installed files hold the same map:
maps-remove-backup = A copy of the file is posted in this channel with a **Restore** button.
maps-remove-no-backup = ⚠️ The server could not send a copy of the file: once removed, it cannot be restored.
maps-remove-button-remove = Remove
maps-remove-button-cancel = Cancel
maps-remove-cancelled = Map removal cancelled.
maps-remove-removing = Removing { $file }…
maps-remove-duplicate = This map removal was already processed.
maps-remove-timeout = No response from target server (timeout).
maps-remove-done = 🗑️ `{ $server }`: removed { $file }. { $detail }
maps-remove-failed = ❌ `{ $server }`: { $file } was not removed. { $detail }
maps-remove-public-pending = 🗑️ { $user } is removing { $file } from `{ $server }`…
maps-remove-public-done = 🗑️ { $user } removed { $file } from `{ $server }`. A copy of the file is attached.
maps-remove-public-failed = { $user } tried to remove { $file } from `{ $server }`; nothing was removed.
maps-remove-public-no-backup = No copy of the file was kept.

# Restore

maps-restore-button = Restore
maps-restore-denied = Only map reviewers can restore maps.
maps-restore-gone = The copy of this map is no longer available.
maps-restore-done-already = This map has already been restored.
maps-restore-busy = This map is being restored right now.
maps-restore-no-server = `{ $server }` did not answer; nothing was restored.
maps-restore-present = `{ $server }` already has a file { $file }. Remove it first, or upload this copy with /maps upload.
maps-restore-present-same = `{ $server }` already has this exact file as { $file }.
maps-restore-result = { $file } on `{ $server }`: { $status }

# /maps duplicates

cmd-maps-duplicates-description = Find maps installed twice on a server (map reviewer)
maps-duplicates-title = Possible duplicates on { $server }
maps-duplicates-none = No duplicate maps found on `{ $server }`.
maps-duplicates-same-bytes = **The same file under different names:**
maps-duplicates-same-title = **The same map name and author:**

# Maps log

maps-log-upload = ⬆ { $user } used /maps upload.
maps-log-accepted = ✅ { $user } accepted case #{ $number }: { $file } on `{ $server }`
maps-log-removed = 🗑️ { $user } removed { $file } from `{ $server }`.
maps-log-restored = ♻️ { $user } restored { $file } on `{ $server }`.

# Revisions

maps-update = Update
maps-revision = Revision
maps-revision-info = Revision { $revision } · the previous one: case #{ $case }, { $status }
maps-revision-status-resolved = accepted
maps-revision-status-rejected = rejected
maps-revision-status-cancelled = cancelled
maps-installed-now = Installed now
maps-changes = Changes since the previous revision
maps-changes-identical = The file is identical to revision { $revision }.
maps-changes-size = File size: { $before } → { $after } bytes
maps-changes-dimensions = Size: { $before } → { $after }
maps-changes-cores = Cores: { $before } → { $after }
maps-changes-patches = Data patches: { $before } → { $after }
maps-changes-none = No change in size, cores or data patches.
maps-select-server-update = Choose the server. The one with the earlier version comes first; the update replaces it there.
maps-server-installed = { $server } — update { $file }
maps-accept-plan = It will be installed as { $file } on `{ $server }`.
maps-review-nothing-new = This post has no newer file from its author since its last review.
maps-review-binding-both = Give both `server` and `replaces`, or neither.
maps-review-binding-unused = `server` and `replaces` were not saved: no review was started. Run the command again once the post has a new file.
maps-outcome-updated = The update of your map { $file } was accepted in case #{ $number } and installed on { $server }.
maps-outcome-fixed = Your fix of { $file } was accepted in case #{ $number } and installed on { $server }. The map keeps its creator; you are recorded as the one who fixed it.

# Request changes

maps-changes-button = Request changes
maps-changes-reason = What should the author change?
maps-changes-requested = The author was asked for changes. The review stays open; their next file is checked again.
maps-changes-requested-field = Changes requested
maps-changes-requested-note = { $user }, the reviewer asks for changes before this map can be accepted: { $reason }
    Post the corrected .msav file in this post.

# A fix of someone else's map

maps-fixed-by = Fixed by
maps-card-fixed-by = Fixed by
maps-creator-choose = { $file } has no recorded creator. Who made this map?
maps-creator-author = The author of this post made it
maps-creator-fix = Someone else made it; this post fixes it

# /maps upload of a file under review, /maps rollback

maps-upload-under-review = This file is under review in case #{ $number } in this post. Use the buttons on its card, so the review is closed and the author credited.
cmd-maps-rollback-description = Put an earlier accepted revision of this post's map back (map reviewer)
maps-rollback-nothing = This post has no earlier accepted revision to go back to.
maps-rollback-choose = Now installed: { $file } on `{ $server }`, from case #{ $number }. Which accepted revision should replace it?
maps-rollback-option = Revision { $revision } · case #{ $number }
maps-rollback-running = Putting an earlier revision back as { $file }…
maps-rollback-file-gone = The file of case #{ $number } is no longer in this post, or it has changed. Nothing was uploaded.
maps-rollback-note = ↩️ { $user } put revision { $revision } (case #{ $number }) back in place of case #{ $current }: { $file } on `{ $server }`.

# Protocol 0.19.0: conditional writes and files kept aside

maps-upload-label-conflict = ⚠️ Not written: the server holds a different file now
maps-upload-status-conflict = not written: the server holds a different file now. Press Accept again to see what is there
maps-upload-note-conflict = check /maps list and try again
maps-upload-note-kept = the earlier file is kept on the server (/maps trash)
maps-remove-conflict = ⚠️ `{ $server }`: { $file } changed since you looked at it; nothing was removed.
maps-remove-public-server-copy = The server kept a copy of the file.
maps-restore-status-restored = ♻️ put back, with its attribution
maps-restore-status-not_found = the server no longer has this copy
maps-restore-status-conflict = the name is taken; nothing was changed
maps-restore-status-failed = the server could not put it back
maps-list-revision = rev. { $revision }
cmd-maps-trash-description = Maps a server set aside when they were removed or replaced (map reviewer)
maps-trash-title = Files `{ $server }` set aside instead of deleting them. Pick one to put it back:
maps-trash-empty = `{ $server }` has no files set aside.
maps-trash-row = { $file } — { $name } · { $reason } · { $date }
maps-trash-reason-removed = removed
maps-trash-reason-replaced = replaced by an upload
maps-trash-restoring = Putting { $file } back…

## Map posts on several servers, and frozen servers
cmd-maps-install-description = Install this post's accepted map on one more server (map reviewer)
cmd-maps-install-param-server-description = Server name
cmd-maps-install-param-replace-description = Installed map it replaces there (when it looks like another map)
maps-install-nothing = This post has no accepted revision to install.
maps-install-unlisted = `{ $server }` did not list its maps, so nothing can be checked or written there now. Try again later.
maps-install-same = `{ $server }` already has this revision as { $file }.
maps-install-confirm = `{ $server }` has maps that look like this one. Run the command again with `replace:` naming the map it replaces, or rename the duplicate first:
maps-install-plan-update = Update { $file } on `{ $server }` to revision { $revision } (case #{ $number })?
maps-install-plan-replace = Replace { $file } on `{ $server }` with revision { $revision } (case #{ $number })? The map keeps its name, statistics and creator.
maps-install-plan-new = Install revision { $revision } (case #{ $number }) on `{ $server }` as { $file }?
maps-install-button = Install
maps-install-running = Installing { $file }…
maps-install-note = 📦 { $user } installed revision { $revision } (case #{ $number }) on `{ $server }` as { $file }.
maps-others-choose = Other servers also have this post's map: { $servers }. Update them to this revision too, after `{ $server }`?
maps-others-all = Also update { $servers }
maps-others-only = Only { $server }
maps-others-note = 📦 { $user } accepted case #{ $number }; the other servers with this map:
maps-others-row = `{ $server }`: { $status }
maps-others-frozen = `{ $server }`: ❄️ frozen, left as it was
maps-others-retry = Use /maps install in this post for a server that was not updated.
maps-upload-label-file_gone = ❌ The reviewed file is no longer in the post
maps-upload-label-timeout = ⚠️ No answer in time; check the server's maps
maps-upload-label-unconfirmed = ⚠️ The server did not confirm the upload
cmd-maps-freeze-description = Warn before any map change on a server; no server lists them (map reviewer)
cmd-maps-freeze-param-server-description = Server name
cmd-maps-freeze-param-reason-description = Why it is frozen
cmd-maps-unfreeze-description = Lift a server's map freeze (map reviewer)
cmd-maps-unfreeze-param-server-description = Server name
maps-frozen-warning = ❄️ `{ $server }` is frozen by { $user }: { $reason }
maps-frozen-no-reason = no reason given
maps-frozen-none = No server is frozen.
maps-freeze-done = `{ $server }` is frozen: every change of its maps now warns first. /maps unfreeze lifts it.
maps-unfreeze-done = `{ $server }` is no longer frozen.
maps-unfreeze-not-frozen = `{ $server }` was not frozen.
maps-log-unfreeze = ☀️ { $user } lifted the freeze of `{ $server }`.
