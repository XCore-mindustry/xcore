"""The Discord merge against the shared merge cases in protocol/storage/merges.

Seeded with a case's before documents, the bot's merge must leave every collection as the case's
after documents say. The plugin's StorageMergeContractIntegrationTest runs the console merge over
the same cases, so the two merges cannot drift apart without one of them failing.
"""

from __future__ import annotations

import sys
from copy import deepcopy
from pathlib import Path
from typing import Any

import pytest
from backends import connected_store
from bson import ObjectId, json_util
from bson.codec_options import DatetimeConversion
from bson.json_util import JSONOptions

from xcore_discord_bot.account_merge_store import AccountMergeStore
from xcore_discord_bot.audit_store import AuditStore
from xcore_discord_bot.punishment_store import PunishmentStore

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "protocol/storage"))
import contract

# As the bot's MongoClient decodes them: naive UTC datetimes.
STORED = JSONOptions(
    datetime_conversion=DatetimeConversion.DATETIME_AUTO, tz_aware=False
)
CASES = contract.merge_cases()
COMPARE = contract.load(contract.MERGES / "compare.json")


def side(case: Path, name: str) -> dict[str, list[dict[str, Any]]]:
    return {
        collection: [json_util.loads(p.read_text(), json_options=STORED) for p in paths]
        for collection, paths in contract.merge_side(case, name).items()
    }


def test_cases_are_found() -> None:
    assert len(CASES) >= 10


@pytest.mark.parametrize("case", CASES, ids=lambda p: p.name)
async def test_the_discord_merge_leaves_what_each_case_expects(case: Path) -> None:
    spec = contract.load(case / "case.json")
    request = spec["request"]
    store, db = await connected_store()
    before = side(case, "before")
    for collection, docs in before.items():
        for doc in docs:
            await db[collection].insert_one(deepcopy(doc))

    merges = AccountMergeStore(store, PunishmentStore(store), AuditStore(store))
    result = await merges.merge_player_accounts(
        source_pid=request["source_pid"],
        target_pid=request["target_pid"],
        actor_name="merge contract",
        actor_discord_id=None,
        reason="merge contract",
        keep_pid=request["keep_pid"],
    )
    assert result.success is not spec.get("refused", False), result.error

    after = side(case, "after")
    collections = {*before, *after, *db.collections} - set(COMPARE["not_compared"])
    for collection in sorted(collections):
        expected = after.get(collection, before.get(collection, []))
        assert_same(
            f"{case.name}/{collection}",
            collection,
            before.get(collection, []),
            expected,
            db[collection].docs,
        )


def assert_same(
    label: str,
    collection: str,
    before: list[dict[str, Any]],
    expected: list[dict[str, Any]],
    actual: list[dict[str, Any]],
) -> None:
    """Documents match by _id, except those whose ObjectId the merge generated: they match by
    their other fields. Fields holding the time of the merge are left out on both sides."""
    stamped = COMPARE["stamped"].get(collection, [])
    known = [doc["_id"] for doc in before]
    want_kept, want_new = split(expected, known, stamped)
    got_kept, got_new = split(actual, known, stamped)

    assert sorted(got_kept) == sorted(want_kept), f"{label}: documents by _id"
    for key, want in want_kept.items():
        got = got_kept[key]
        assert same(want, got), f"{label}: _id {key}\n expected {want}\n but was  {got}"
    assert len(got_new) == len(want_new), (
        f"{label}: documents the merge created: {got_new}"
    )
    unmatched = list(got_new)
    for want in want_new:
        match = next((got for got in unmatched if same(want, got)), None)
        assert match is not None, (
            f"{label}: no created document matches\n expected {want}\n among    {unmatched}"
        )
        unmatched.remove(match)


def split(
    docs: list[dict[str, Any]], known: list[Any], stamped: list[str]
) -> tuple[dict[str, dict[str, Any]], list[dict[str, Any]]]:
    kept: dict[str, dict[str, Any]] = {}
    created: list[dict[str, Any]] = []
    for original in docs:
        doc = deepcopy(original)
        for path in stamped:
            remove(doc, path.split("."))
        if isinstance(doc["_id"], ObjectId) and doc["_id"] not in known:
            del doc["_id"]
            created.append(doc)
        else:
            kept[str(doc["_id"])] = doc
    return kept, created


def remove(doc: dict[str, Any], path: list[str]) -> None:
    if len(path) == 1:
        doc.pop(path[0], None)
    elif isinstance(doc.get(path[0]), dict):
        remove(doc[path[0]], path[1:])


def same(expected: Any, actual: Any) -> bool:
    """Numbers by value, whatever their width; arrays as sets, as the plugin keeps them in sets."""
    if isinstance(expected, bool) or isinstance(actual, bool):
        return type(expected) is type(actual) and expected == actual
    if isinstance(expected, (int, float)) and isinstance(actual, (int, float)):
        return expected == actual
    if isinstance(expected, dict) and isinstance(actual, dict):
        return expected.keys() == actual.keys() and all(
            same(expected[k], actual[k]) for k in expected
        )
    if isinstance(expected, list) and isinstance(actual, list):
        return all(any(same(e, a) for a in actual) for e in expected) and all(
            any(same(e, a) for e in expected) for a in actual
        )
    return type(expected) is type(actual) and expected == actual
