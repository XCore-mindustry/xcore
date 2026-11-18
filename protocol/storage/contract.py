"""Schemas, fixtures and the generated inventory of the MongoDB collections that several
components share.

Run ``python protocol/storage/contract.py`` to rewrite INVENTORY.md after changing a schema or
collections.json. Only the standard library and jsonschema are needed, so the protocol's and the
bot's tests can both import this file.
"""

from __future__ import annotations

import json
import re
import sys
from collections.abc import Iterator
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
SCHEMAS = ROOT / "schemas"
FIXTURES = ROOT / "fixtures"
MIGRATIONS = ROOT / "migrations"
INVENTORY = ROOT / "INVENTORY.md"
COMPONENTS = ("plugin", "bot", "web")
# A stored document can have any of these shapes; "invalid" fixtures must be rejected.
STORED_KINDS = ("valid", "legacy")


def load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def schemas() -> dict[str, dict[str, Any]]:
    """Collection name to schema, for every collection with one."""
    found = {}
    for path in sorted(SCHEMAS.glob("*.json")):
        schema = load(path)
        if "x-collection" in schema:
            found[schema["x-collection"]] = schema
    return found


def validator(collection: str):
    from jsonschema import Draft202012Validator
    from referencing import Registry, Resource

    registry = Registry()
    for path in SCHEMAS.glob("*.json"):
        schema = load(path)
        registry = registry.with_resource(schema["$id"], Resource.from_contents(schema))
    return Draft202012Validator(
        schemas()[collection],
        registry=registry,
        format_checker=Draft202012Validator.FORMAT_CHECKER,
    )


def fixtures(collection: str, *kinds: str) -> list[Path]:
    """Fixture files of a collection. Kinds are valid, legacy and invalid, plus before and
    after for the documents each migration was given and produced."""
    paths: list[Path] = []
    for kind in kinds:
        if kind in ("before", "after"):
            paths += sorted(MIGRATIONS.glob(f"V*/{kind}/{collection}/*.json"))
        else:
            paths += sorted((FIXTURES / collection / kind).glob("*.json"))
    return paths


def stored(collection: str) -> list[Path]:
    """Every shape a reader can meet: current and legacy documents, and both sides of each
    migration, because a server on the previous jar keeps writing the old shape until it restarts."""
    return fixtures(collection, *STORED_KINDS, "before", "after")


def fields(collection: str, component: str, role: str = "x-read-by") -> set[str]:
    """Top-level fields that a component reads (x-read-by) or writes (x-written-by)."""
    properties = schemas()[collection]["properties"]
    return {name for name, field in properties.items() if component in field.get(role, {})}


def declared(collection: str) -> set[str]:
    return set(schemas()[collection]["properties"])


def literal_misses() -> list[str]:
    """Annotations naming a file that does not mention the field, or a file that does not exist.

    A rename on either side leaves the old name in the schema and drops it from the file, so the
    inventory cannot quietly go stale.
    """
    misses = []
    for collection, schema in schemas().items():
        for name, field in schema["properties"].items():
            for role in ("x-written-by", "x-read-by"):
                for component, paths in field.get(role, {}).items():
                    for path in paths:
                        misses += _literal_miss(collection, name, f"{role} {component}", path)
    shared = load(ROOT / "collections.json")["shared"]
    for collection, entry in shared.items():
        for role in ("writers", "readers"):
            for component, paths in entry[role].items():
                for path in paths:
                    misses += _literal_miss(collection, None, f"{role} {component}", path)
    return misses


def _literal_miss(collection: str, name: str | None, role: str, path: str) -> list[str]:
    file = REPO / path
    if not file.is_file():
        return [f"{collection}.{name or '*'}: {role} names {path}, which does not exist"]
    if name is None or name == "_id":
        name = collection
    camel = re.sub(r"_(\w)", lambda m: m.group(1).upper(), name)
    spellings = {name, camel, "is" + camel[:1].upper() + camel[1:]}
    text = file.read_text(encoding="utf-8")
    if any(re.search(rf"(?<![\w$]){re.escape(s)}(?![\w])", text) for s in spellings):
        return []
    return [f"{collection}.{name}: {role} names {path}, which never mentions it"]


def _type(field: dict[str, Any]) -> str:
    if "not" in field:
        return "never stored"
    if "$ref" in field:
        return field["$ref"].rsplit("/", 1)[-1]
    if "enum" in field:
        return " \\| ".join(json.dumps(v) for v in field["enum"])
    if "oneOf" in field:
        return " \\| ".join(_type(option) for option in field["oneOf"])
    kind = field.get("type", "")
    if kind == "array":
        return f"array of {_type(field['items'])}"
    if kind == "object" and isinstance(field.get("additionalProperties"), dict):
        return f"map to {_type(field['additionalProperties'])}"
    return " \\| ".join(kind) if isinstance(kind, list) else kind


def _files(entries: dict[str, list[str]]) -> str:
    return "<br>".join(f"**{c}** " + ", ".join(f"`{Path(p).name}`" for p in entries[c]) for c in COMPONENTS if c in entries)


def _components(entries: dict[str, list[str]]) -> str:
    return ", ".join(c for c in COMPONENTS if c in entries) or "—"


def _cell(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


def _paths(collection_entries: dict[str, dict[str, list[str]]]) -> list[str]:
    lines = []
    for component in COMPONENTS:
        paths = sorted({p for entries in collection_entries.values() for p in entries.get(component, [])})
        if paths:
            lines.append(f"- **{component}**: " + ", ".join(f"[`{Path(p).name}`](../../{p})" for p in paths))
    return lines


def inventory() -> str:
    """INVENTORY.md, generated from the schemas and collections.json."""
    shared = load(ROOT / "collections.json")
    lines = [
        "# Shared MongoDB collections",
        "",
        "<!-- Generated by protocol/storage/contract.py from schemas/*.json and collections.json. Do not edit. -->",
        "",
        "Who reads and writes each collection that more than one component touches. Collections with a",
        "schema are described field by field; the schema is the contract and the contract tests of each",
        "component check it. See [README.md](README.md) for how to change a shared document.",
        "",
        "| Collection | Schema | Writers | Readers |",
        "|---|---|---|---|",
    ]
    described = schemas()
    rows = []
    for collection, schema in described.items():
        props = schema["properties"].values()
        writers = {c for p in props for c in p.get("x-written-by", {})}
        readers = {c for p in props for c in p.get("x-read-by", {})}
        rows.append((collection, f"[{collection}.v1](schemas/{collection}.v1.json)",
                     ", ".join(c for c in COMPONENTS if c in writers), ", ".join(c for c in COMPONENTS if c in readers)))
    for collection, entry in shared["shared"].items():
        rows.append((collection, "—", _components(entry["writers"]), _components(entry["readers"])))
    lines += [f"| `{c}` | {s} | {w} | {r} |" for c, s, w, r in sorted(rows)]
    lines += ["", "Used by one component only: " + ", ".join(
        f"`{c}` ({owner})" for c, owner in sorted(shared["single_component"].items())) + ".", ""]

    for collection, schema in described.items():
        props = schema["properties"]
        lines += [f"## `{collection}`", "", schema["description"], ""]
        lines += _paths({"w": _merge(props, "x-written-by"), "r": _merge(props, "x-read-by")})
        lines += ["", "| Field | Type | Written by | Read by | Fallback when missing |", "|---|---|---|---|---|"]
        for name, field in props.items():
            fallback = "; ".join(f"**{c}** {field['x-fallback'][c]}" for c in COMPONENTS if c in field.get("x-fallback", {}))
            lines.append(f"| `{name}` | {_type(field)} | {_files(field.get('x-written-by', {}))} "
                         f"| {_files(field.get('x-read-by', {}))} | {_cell(fallback)} |")
        notes = [(name, field[key]) for name, field in props.items() for key in ("x-disagreement", "x-note") if key in field]
        notes += [(d, sub["x-note"]) for d, sub in schema.get("$defs", {}).items() if "x-note" in sub]
        if notes:
            lines += ["", "Disagreements and constraints:", ""]
            lines += [f"- `{name}`: {text}" for name, text in notes]
        lines.append("")

    for collection, entry in shared["shared"].items():
        lines += [f"## `{collection}`", "", "No schema yet.", ""]
        lines += _paths({"w": entry["writers"], "r": entry["readers"]})
        lines += [""] + [f"- {note}" for note in entry.get("notes", [])]
        if entry.get("notes"):
            lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def _merge(props: dict[str, Any], role: str) -> dict[str, list[str]]:
    merged: dict[str, list[str]] = {}
    for field in props.values():
        for component, paths in field.get(role, {}).items():
            merged.setdefault(component, []).extend(paths)
    return merged


def documents(collection: str, *kinds: str) -> Iterator[tuple[Path, Any]]:
    for path in fixtures(collection, *kinds):
        yield path, load(path)


if __name__ == "__main__":
    INVENTORY.write_text(inventory(), encoding="utf-8")
    sys.exit(0)
