"""The bot against the shared MongoDB contract in protocol/storage.

Readers get every stored shape from the shared fixtures and may only use fields the schema
declares as read by the bot; what the bot's stores write must pass the schema. A field renamed
in a schema and its fixtures therefore fails here as soon as the bot still uses the old name.
"""

from __future__ import annotations

import json
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
from backends import connected_store
from bson import ObjectId, json_util
from bson.codec_options import DatetimeConversion
from bson.json_util import CANONICAL_JSON_OPTIONS, JSONOptions
from mongo_fakes import Database

from xcore_discord_bot.account_merge_store import AccountMergeStore
from xcore_discord_bot.audit_store import AuditStore
from xcore_discord_bot.cases.flows.appeals import _row_ban
from xcore_discord_bot.cases.store import CaseStore
from xcore_discord_bot.mongo_store import BanDoc, MongoStore, MuteDoc
from xcore_discord_bot.punishment_store import PunishmentStore
from xcore_discord_bot.store_mappers import (
    ban_record_from_doc,
    mute_record_from_doc,
    player_record_from_doc,
)

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "protocol/storage"))
import contract

# As the bot's MongoClient decodes them: naive UTC datetimes, and a long past year 9999.
STORED = JSONOptions(
    datetime_conversion=DatetimeConversion.DATETIME_AUTO, tz_aware=False
)
NOW = datetime(2026, 6, 1, tzinfo=UTC)


class Reads(dict):
    """A stored document that remembers which of its fields were read."""

    def __init__(self, data: dict[str, Any], seen: set[str]) -> None:
        super().__init__(data)
        self.seen = seen

    def get(self, key: Any, default: Any = None) -> Any:
        self.seen.add(key)
        return super().get(key, default)

    def __getitem__(self, key: Any) -> Any:
        self.seen.add(key)
        return super().__getitem__(key)

    def __contains__(self, key: object) -> bool:
        self.seen.add(str(key))
        return super().__contains__(key)

    def __deepcopy__(self, memo: dict[int, Any]) -> Reads:
        from copy import deepcopy

        return Reads(deepcopy(dict(self), memo), self.seen)


def stored(collection: str) -> list[tuple[str, dict[str, Any]]]:
    return [
        (path.name, json_util.loads(path.read_text(), json_options=STORED))
        for path in contract.stored(collection)
    ]


def assert_reads_declared(collection: str, seen: set[str]) -> None:
    declared = contract.fields(collection, "bot")
    assert seen, f"nothing read from {collection}"
    assert seen <= declared, (
        f"the bot reads {sorted(seen - declared)} from {collection}, which the schema "
        "does not declare as read by the bot"
    )


async def assert_written_match_schema(db: Database, collection: str) -> None:
    validator = contract.validator(collection)
    rows = await db[collection].find({}).to_list()
    assert rows, f"nothing written to {collection}"
    for row in rows:
        canonical = json.loads(
            json_util.dumps(row, json_options=CANONICAL_JSON_OPTIONS)
        )
        errors = [
            f"{list(e.path)}: {e.message}" for e in validator.iter_errors(canonical)
        ]
        assert not errors, f"{collection} row {canonical}: {errors}"


async def _alone(
    collection: str, doc: dict[str, Any], seen: set[str]
) -> tuple[MongoStore, Database]:
    """A store holding one stored shape: a migration's before and after share their ids."""
    store, db = await connected_store()
    await db[collection].insert_one(Reads(doc, seen))
    return store, db


@pytest.mark.parametrize(
    "name,doc", stored("players"), ids=lambda v: v if isinstance(v, str) else ""
)
def test_player_reader_accepts_every_stored_shape(
    name: str, doc: dict[str, Any]
) -> None:
    seen: set[str] = set()
    record = player_record_from_doc(Reads(doc, seen))

    assert record.nickname == (str(doc.get("nickname") or "").strip() or "Unknown")
    assert record.uuid == (doc.get("uuid") or None)
    assert record.pid == doc.get("pid", -(2**31))
    assert_reads_declared("players", seen)


async def test_player_lookups_and_merge_read_declared_fields() -> None:
    seen: set[str] = set()
    for _, doc in stored("players"):
        store, _ = await _alone("players", doc, seen)
        found = await store.find_player_by_uuid(doc["uuid"])
        assert found is not None and found.nickname == (
            doc.get("nickname") or "Unknown"
        )
    merges = AccountMergeStore(store, PunishmentStore(store), AuditStore(store))
    source, target = (
        doc
        for name, doc in stored("players")
        if name in ("never-linked.json", "plugin-written.json")
    )
    merges._plan_merge(Reads(source, seen), Reads(target, seen), keep_pid="target")
    assert_reads_declared("players", seen)


@pytest.mark.parametrize(
    "name,doc", stored("bans"), ids=lambda v: v if isinstance(v, str) else ""
)
def test_ban_readers_accept_every_stored_shape(name: str, doc: dict[str, Any]) -> None:
    seen: set[str] = set(BanDoc.model_fields)
    record = ban_record_from_doc(Reads(BanDoc.model_validate(doc).model_dump(), seen))
    _row_ban(Reads(doc, seen))

    assert record.ban_id == (doc.get("ban_id") or None)
    assert record.uuid == (doc.get("uuid") or None)
    assert record.name == (doc.get("name") or "Unknown")
    assert record.expire_date == doc.get("expire_date")
    assert_reads_declared("bans", seen)


async def test_ban_store_reads_declared_fields() -> None:
    seen: set[str] = set(BanDoc.model_fields)
    for _, doc in stored("bans"):
        store, _ = await _alone("bans", doc, seen)
        punishments = PunishmentStore(store)
        assert len(await punishments.list_bans(limit=10)) == 1
        if doc.get("uuid"):
            assert await punishments.find_ban(uuid=doc["uuid"], ip=None) is not None
    assert_reads_declared("bans", seen)


@pytest.mark.parametrize(
    "name,doc", stored("mutes"), ids=lambda v: v if isinstance(v, str) else ""
)
async def test_mute_readers_accept_every_stored_shape(
    name: str, doc: dict[str, Any]
) -> None:
    seen: set[str] = set(MuteDoc.model_fields)
    record = mute_record_from_doc(Reads(MuteDoc.model_validate(doc).model_dump(), seen))
    store, db = await connected_store()
    await db["mutes"].insert_one(Reads(doc, seen))

    assert await PunishmentStore(store).find_mute(uuid=doc["uuid"]) == record
    assert record.reason == (doc.get("reason") or "Not Specified")
    assert record.expire_date == doc["expire_date"]
    assert_reads_declared("mutes", seen)


async def test_counters_continue_every_stored_sequence() -> None:
    for _, doc in stored("counters"):
        if doc["_id"] not in ("ban_id", "case_number"):
            continue
        seen: set[str] = set()
        store, db = await connected_store()
        await db["counters"].insert_one(Reads(doc, seen))
        if doc["_id"] == "ban_id":
            assert await PunishmentStore(store).next_ban_id() == int(doc["seq"]) + 1
        else:
            assert await CaseStore(store).next_case_number() == int(doc["seq"]) + 1
        assert_reads_declared("counters", seen | {"_id"})


async def test_punishment_writes_match_the_schema() -> None:
    store, db = await connected_store()
    punishments = PunishmentStore(store)
    await punishments.ensure_indexes()
    common = {"admin_name": "Mod", "admin_discord_id": None, "reason": "r"}
    await punishments.upsert_ban(
        uuid="u1",
        ip="192.0.2.1",
        pid=5,
        name="One",
        expire_date=NOW,
        case_number=2001,
        **common,
    )
    await punishments.upsert_ban(
        uuid="u2", ip=None, pid=None, name="Two", expire_date=NOW, **common
    )
    await punishments.update_ban_expiry(ban_id=1, new_expiry=NOW - timedelta(days=1))
    await punishments.release_ban_ip(ban_id=1, ip="192.0.2.1")
    await db["bans"].insert_one(
        json_util.loads(
            contract.fixtures("bans", "legacy")[0].read_text(), json_options=STORED
        )
    )
    await punishments.ensure_ban_id(uuid="uuid-banned-old", ip=None)
    await punishments.upsert_mute(
        uuid="u1", pid=5, name="One", expire_date=NOW, case_number=2002, **common
    )
    await punishments.update_mute_expiry(
        uuid="u1", expire_date=NOW, new_expiry=NOW + timedelta(hours=1)
    )
    await CaseStore(store).ensure_indexes()
    await CaseStore(store).next_case_number()

    for collection in ("bans", "mutes", "counters"):
        await assert_written_match_schema(db, collection)


async def test_badge_and_merge_writes_match_the_schema() -> None:
    store, db = await connected_store()
    for path in contract.fixtures("players", "valid"):
        await db["players"].insert_one(
            json_util.loads(path.read_text(), json_options=STORED)
        )
    # The account merged away is banned, so the merge copies its ban to the surviving one.
    ban = dict(stored("bans")[0][1], _id=ObjectId(), uuid="uuid-new-player")
    await db["bans"].insert_one(ban)
    await db["counters"].insert_one({"_id": "ban_id", "seq": 50})
    await db["players"].update_many({}, {"$set": {"online": False}})
    await store.grant_badge(uuid="uuid-contract", badge_id="veteran")
    await store.revoke_badge(uuid="uuid-contract", badge_id="translator")

    merges = AccountMergeStore(store, PunishmentStore(store), AuditStore(store))
    result = await merges.merge_player_accounts(
        source_pid=1351,
        target_pid=42,
        actor_name="Admin",
        actor_discord_id="1",
        reason="contract",
    )

    assert result.success, result.error
    for collection in ("players", "bans", "counters"):
        await assert_written_match_schema(db, collection)
