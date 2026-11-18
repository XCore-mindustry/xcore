from __future__ import annotations

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
