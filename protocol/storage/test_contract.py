from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator

sys.path.insert(0, str(Path(__file__).resolve().parent))
import contract  # noqa: E402

COLLECTIONS = sorted(contract.schemas())


@pytest.mark.parametrize("path", sorted(contract.SCHEMAS.glob("*.json")), ids=lambda p: p.name)
def test_schema_is_valid_draft_2020_12(path: Path) -> None:
    Draft202012Validator.check_schema(contract.load(path))


@pytest.mark.parametrize("collection", COLLECTIONS)
def test_stored_fixtures_match_the_schema(collection: str) -> None:
    validator = contract.validator(collection)
    paths = contract.stored(collection)
    assert paths, f"{collection} has no fixtures"
    for path in paths:
        errors = [f"{list(e.path)}: {e.message}" for e in validator.iter_errors(contract.load(path))]
        assert not errors, f"{path.relative_to(contract.ROOT)}: {errors}"


@pytest.mark.parametrize("collection", COLLECTIONS)
def test_invalid_fixtures_are_rejected(collection: str) -> None:
    validator = contract.validator(collection)
    for path in contract.fixtures(collection, "invalid"):
        assert not validator.is_valid(contract.load(path)), f"{path.relative_to(contract.ROOT)} passes the schema"


@pytest.mark.parametrize("collection", COLLECTIONS)
def test_every_field_names_who_uses_it(collection: str) -> None:
    for name, field in contract.schemas()[collection]["properties"].items():
        assert field.get("x-written-by") or field.get("x-read-by"), f"{collection}.{name}"
        for role in ("x-written-by", "x-read-by", "x-fallback"):
            assert set(field.get(role, {})) <= set(contract.COMPONENTS), f"{collection}.{name} {role}"


def test_every_named_file_mentions_the_field() -> None:
    assert contract.literal_misses() == []


def test_inventory_is_up_to_date() -> None:
    assert contract.INVENTORY.read_text(encoding="utf-8") == contract.inventory(), (
        "INVENTORY.md is stale: run python protocol/storage/contract.py"
    )


CASES = contract.merge_cases()
KNOWN = set(contract.schemas()) | set(contract.load(contract.ROOT / "collections.json")["shared"])


def test_merge_cases_are_found() -> None:
    assert len(CASES) >= 10


@pytest.mark.parametrize("case", CASES, ids=lambda p: p.name)
def test_merge_case_is_well_formed(case: Path) -> None:
    spec = contract.load(case / "case.json")
    assert set(spec) <= {"description", "request", "refused"}, spec
    assert spec["description"].strip()
    request = spec["request"]
    assert set(request) == {"source_pid", "target_pid", "keep_pid"}, request
    assert request["keep_pid"] in ("target", "source")
    before, after = contract.merge_side(case, "before"), contract.merge_side(case, "after")
    assert before, "a merge case needs a before side"
    if spec.get("refused"):
        assert not (case / "after").exists(), "a refused merge changes nothing, so it has no after side"
    else:
        assert after, "a merge that is not refused has an after side"
    compare = contract.load(contract.MERGES / "compare.json")
    for collection in {*before, *after}:
        assert collection in KNOWN, f"{collection} is not a shared collection"
        assert collection not in compare["not_compared"], f"{collection} is not compared"


@pytest.mark.parametrize("case", CASES, ids=lambda p: p.name)
def test_merge_case_documents_have_ids(case: Path) -> None:
    for side in ("before", "after"):
        for collection, paths in contract.merge_side(case, side).items():
            ids = [json.dumps(contract.load(p)["_id"], sort_keys=True) for p in paths]
            assert len(ids) == len(set(ids)), f"{case.name}/{side}/{collection} repeats an _id"


def test_hexed_rank_points_rise_from_zero() -> None:
    points = contract.hexed_rank_points()
    assert points[0] == 0
    assert all(isinstance(p, int) for p in points)
    assert points == sorted(set(points)), points


def test_hexed_rank_readers_mention_the_file() -> None:
    for component, paths in contract.load(contract.HEXED_RANKS)["x-read-by"].items():
        assert component in contract.COMPONENTS
        for path in paths:
            text = (contract.REPO / path).read_text(encoding="utf-8")
            assert contract.HEXED_RANKS.name in text, f"{path} never names {contract.HEXED_RANKS.name}"
