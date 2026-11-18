# Storage contract

The MongoDB collections that more than one component reads or writes, described the way
`spec/` describes messages: JSON Schemas, canonical fixtures, and tests in every component that
uses them. [INVENTORY.md](INVENTORY.md) lists who reads and writes what.

This directory is not part of the published protocol. The wheel is built from `python/` and the
jar from `java/`, and the generators read only `spec/`, so a change here needs no protocol release.

## Layout

- `schemas/<collection>.v1.json` — what a stored document can look like today, legacy shapes
  included. `bson-types.v1.json` holds the BSON types in Extended JSON.
- `fixtures/<collection>/valid/` — documents as each component writes them now.
- `fixtures/<collection>/legacy/` — shapes older versions left behind that readers still meet.
- `fixtures/<collection>/invalid/` — shapes the schema must reject.
- `migrations/V<n>/before/<collection>/`, `migrations/V<n>/after/<collection>/` — what a plugin
  migration was given and what it produced.
- `collections.json` — shared collections without a schema yet, and collections only one
  component uses.
- `contract.py` — loads the above and generates INVENTORY.md; `test_contract.py` checks them.

Fixtures are relaxed Extended JSON. A number that fits in 32 bits is read as an int32, so write
an int64 field with a small value as `{"$numberLong": "0"}`.

## Annotations

Each field in a schema says who uses it, with the files that do:

- `x-written-by`, `x-read-by` — component (`plugin`, `bot`, `web`) to the files.
- `x-fallback` — what each reader does when the field is missing or empty.
- `x-disagreement` — where the components do not agree on the field. Recorded, not resolved.
- `x-note` — a constraint the type alone does not show.

The protocol tests check that every named file mentions the field, so renaming a field in code
without updating the schema fails.

## What the tests check

| Component | Test | Checks |
|---|---|---|
| protocol | `test_contract.py` (`uv run pytest storage` in `protocol/`) | schemas are valid; stored fixtures pass and invalid ones fail; named files mention their fields; INVENTORY.md is current |
| plugin | `plugins/xcore/src/test/java/org/xcore/plugin/database/storage/` | the codec and the repositories write documents that pass the schema; every stored shape decodes with the fields the plugin reads intact; migrations turn their before fixtures into their after fixtures, and a new one that changes a shared collection brings fixtures |
| bot | `services/discord-bot/tests/test_storage_contract.py` | the stores write documents that pass the schema; every stored shape goes through the readers, which use only fields the schema says the bot reads |
| web | `web/tests/storage-contract.test.ts` | every stored players shape goes through the site's readers, which use only fields the schema says the site reads |

CI runs all of them when anything under `protocol/storage/` changes.

## Changing a shared document

1. Change the schema and the fixtures, then the code. Keep the old shape in `legacy/` for as long
   as stored documents or servers on an older jar can still produce it: game servers pick up a new
   jar only when they restart, so for days several plugin versions share the database.
2. Run `python protocol/storage/contract.py` to regenerate INVENTORY.md.
3. A plugin migration that changes a collection with a schema needs fixtures in
   `migrations/V<n>/before/<collection>` and `after/<collection>`. The plugin's tests run every
   migration over every stored shape and fail if it changes one without them; they also replay
   `before` and compare with `after`. A collection without an `after` directory must come out
   unchanged. The bot's and the website's tests read both sides.
4. Migrations up to V11 predate this contract and need no fixtures; V7 has some as an example.

## Adding a collection

Write `schemas/<collection>.v1.json` with `x-collection` set and fixtures for it, move the
collection out of the `shared` list in `collections.json`, and extend each component's contract
test to run its readers and writers of the collection.
