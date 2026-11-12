"""What installing a map file would do to the maps already on a server.

A server installs a file under its file name: a different name adds a second map, the
same name overwrites whatever is there. Before an upload the bot compares the incoming file
with the server's map list (``maps.list``: file name, map name, author and SHA-256 of each
file) and finds the maps it is probably a version of. Nothing here talks to Discord or the
server; the caller lists the maps and decides what to ask.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

from .utils.mindustry_colors import strip_mindustry_colors

# Authors a server reports for a map without one; they say nothing about who made it.
_NO_AUTHOR = frozenset({"", "unknown"})


class Match(StrEnum):
    """Why an installed map looks like the incoming file."""

    SAME_BYTES = "same-bytes"  # identical SHA-256
    SAME_FILE = "same-file"  # the file name it would be installed under
    SAME_TITLE = "same-title"  # the same map name and author, under another file name


class Outcome(StrEnum):
    """What installing the file under its target name does."""

    NEW = "new"  # nothing like it is installed
    SAME = "same"  # the very same bytes are already installed under this name
    UPDATE = "update"  # overwrites the earlier version of this map
    CONFIRM = "confirm"  # a person has to decide: a likely duplicate or another map overwritten


@dataclass(frozen=True)
class Incoming:
    """The file to install: the name it would get on the server and what it contains.

    ``sha256``, ``name`` and ``author`` are ``None`` when the file could not be read.
    """

    file_name: str
    sha256: str | None = None
    name: str | None = None
    author: str | None = None


@dataclass(frozen=True)
class Candidate:
    """An installed map the incoming file is probably a version of."""

    item: dict[str, str]
    reasons: frozenset[Match]

    @property
    def file_name(self) -> str:
        return str(self.item.get("file_name", ""))

    @property
    def overwritten_foreign(self) -> bool:
        """Installing would overwrite this file although it holds a different map."""
        return Match.SAME_FILE in self.reasons and not (
            self.reasons & {Match.SAME_BYTES, Match.SAME_TITLE}
        )


def _text(value: str | None) -> str:
    return " ".join(strip_mindustry_colors(value or "").split()).casefold()


def title_key(name: str | None, author: str | None) -> tuple[str, str] | None:
    """The map's name and author, compared without colors, case and extra spaces.

    ``None`` for a map without a name: it cannot be recognised by its title.
    """
    title = _text(name)
    if not title:
        return None
    by = _text(author)
    return title, "" if by in _NO_AUTHOR else by


def find_candidates(maps: list[dict[str, str]], incoming: Incoming) -> list[Candidate]:
    """The installed maps the incoming file is probably a version of.

    The same bytes come first, then the same file name, then the same name and author.
    """
    sha = (incoming.sha256 or "").lower()
    target = incoming.file_name.casefold()
    title = title_key(incoming.name, incoming.author)
    found: list[Candidate] = []
    for item in maps:
        reasons: set[Match] = set()
        if sha and str(item.get("content_hash", "")).lower() == sha:
            reasons.add(Match.SAME_BYTES)
        if str(item.get("file_name", "")).casefold() == target:
            reasons.add(Match.SAME_FILE)
        if (
            title is not None
            and title_key(item.get("name"), item.get("author")) == title
        ):
            reasons.add(Match.SAME_TITLE)
        if reasons:
            found.append(Candidate(item, frozenset(reasons)))
    order = (Match.SAME_BYTES, Match.SAME_FILE, Match.SAME_TITLE)
    return sorted(
        found, key=lambda c: next(i for i, m in enumerate(order) if m in c.reasons)
    )


def outcome(candidates: list[Candidate], file_name: str) -> Outcome:
    """What installing under ``file_name`` does, given the candidates found for it.

    Only a file that replaces nothing, or that plainly replaces its own earlier version
    (the same name, and the same bytes or the same map), goes ahead without a question.
    """
    if not candidates:
        return Outcome.NEW
    target = file_name.casefold()
    same = [c for c in candidates if c.file_name.casefold() == target]
    others = [c for c in candidates if c.file_name.casefold() != target]
    if others or not same or same[0].overwritten_foreign:
        return Outcome.CONFIRM
    return Outcome.SAME if Match.SAME_BYTES in same[0].reasons else Outcome.UPDATE


def replacement_targets(candidates: list[Candidate], file_name: str) -> list[str]:
    """The installed files a person may choose to replace, besides installing as new."""
    target = file_name.casefold()
    seen: set[str] = set()
    names = []
    for candidate in candidates:
        name = candidate.file_name
        if name and name.casefold() != target and name.casefold() not in seen:
            seen.add(name.casefold())
            names.append(name)
    return names


def duplicate_groups(
    maps: list[dict[str, str]],
) -> list[tuple[Match, list[dict[str, str]]]]:
    """Groups of installed files that hold the same bytes, or the same name and author."""
    groups: list[tuple[Match, list[dict[str, str]]]] = []
    by_hash: dict[str, list[dict[str, str]]] = {}
    by_title: dict[tuple[str, str], list[dict[str, str]]] = {}
    for item in maps:
        sha = str(item.get("content_hash", "")).lower()
        if sha:
            by_hash.setdefault(sha, []).append(item)
        title = title_key(item.get("name"), item.get("author"))
        if title is not None:
            by_title.setdefault(title, []).append(item)
    hashed = set()
    for items in by_hash.values():
        if len(items) > 1:
            groups.append((Match.SAME_BYTES, items))
            hashed.add(frozenset(id(item) for item in items))
    for items in by_title.values():
        if len(items) > 1 and frozenset(id(item) for item in items) not in hashed:
            groups.append((Match.SAME_TITLE, items))
    return groups


def expectation(
    maps: list[dict[str, str]] | None, target: str
) -> tuple[str | None, bool]:
    """What the server must still hold for a write under ``target`` to go ahead: the hash
    of the file the reviewer saw there, or no file at all; nothing when the list is unknown.

    The server writes the exact name, so only the exact name counts here.
    """
    if maps is None:
        return None, False
    exact = next((item for item in maps if item.get("file_name") == target), None)
    if exact is None:
        return None, True
    return (exact.get("content_hash") or None), False
