"""``/staff activity``: what each staff member did in the last N days.

Sources, all keyed by Discord id: closed cases (``cases.events``), sanctions
(``moderation_audit``) and minutes on the game servers (``staff_activity``, written by the
plugin once a minute). Nothing is stored; the report is built on each call.
"""

from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta
from typing import Any

from pymongo.asynchronous.database import AsyncDatabase

# Case events that close a case; a reopened case closed again counts again.
CLOSING_EVENTS = ("action", "rejected")
# Case events done by staff, for "last action". Openers and the bot are not staff work.
STAFF_EVENTS = (*CLOSING_EVENTS, "taken", "released", "reopened", "subject_set")
SANCTIONS = ("BAN", "MUTE", "KICK")
PARDONS = ("UNBAN", "UNMUTE")
# Fewer active minutes than this, with no counted action, is inactive.
INACTIVE_MINUTES = 30


@dataclass
class Counts:
    cases: int = 0
    bans: int = 0
    bans_discord: int = 0
    mutes: int = 0
    kicks: int = 0
    pardons: int = 0
    minutes: int = 0
    staff_minutes: int = 0

    @property
    def actions(self) -> int:
        return self.cases + self.bans + self.mutes + self.kicks + self.pardons

    def add(self, other: Counts) -> None:
        for name in self.__dataclass_fields__:
            setattr(self, name, getattr(self, name) + getattr(other, name))


@dataclass
class Person:
    discord_id: str
    total: Counts = field(default_factory=Counts)
    days: dict[str, Counts] = field(default_factory=dict)
    last_action: datetime | None = None
    last_seen: date | None = None

    def day(self, day: str) -> Counts:
        return self.days.setdefault(day, Counts())

    @property
    def inactive(self) -> bool:
        return self.total.actions == 0 and self.total.minutes < INACTIVE_MINUTES


@dataclass
class Report:
    start: datetime
    days: int
    people: dict[str, Person]
    roster: frozenset[str]
    unattributed: int

    def person(self, discord_id: str) -> Person:
        return self.people.get(discord_id) or Person(discord_id)

    @property
    def outside_roster(self) -> list[Person]:
        return [
            p
            for p in self.people.values()
            if p.discord_id not in self.roster and p.total.actions > 0
        ]


def window_start(now: datetime, days: int) -> datetime:
    """Midnight UTC of the first day: today counts as one of the ``days``."""
    today = now.astimezone(UTC).date()
    first = today - timedelta(days=days - 1)
    return datetime(first.year, first.month, first.day, tzinfo=UTC)


def _day(value: datetime) -> str:
    return value.astimezone(UTC).date().isoformat()


async def load(
    database: AsyncDatabase[Any], roster: Iterable[str], days: int, now: datetime
) -> Report:
    start = window_start(now, days)
    people: dict[str, Person] = {
        discord_id: Person(discord_id) for discord_id in roster
    }

    def person(discord_id: str) -> Person:
        return people.setdefault(discord_id, Person(discord_id))

    await _load_cases(database, start, person)
    unattributed = await _load_audit(database, start, person)
    await _load_minutes(database, start, person)
    await _load_last_seen(database, people, list(people))
    for p in people.values():
        for counts in p.days.values():
            p.total.add(counts)
    return Report(start, days, people, frozenset(roster), unattributed)


async def _load_cases(
    database: AsyncDatabase[Any], start: datetime, person: Callable[[str], Person]
) -> None:
    cases = await database["cases"].aggregate(
        [
            {"$match": {"events.at": {"$gte": start}}},
            {"$unwind": "$events"},
            {
                "$match": {
                    "events.type": {"$in": list(CLOSING_EVENTS)},
                    "events.at": {"$gte": start},
                    "events.actor.discord_id": {"$type": "string", "$ne": ""},
                }
            },
            {
                "$group": {
                    "_id": {
                        "id": "$events.actor.discord_id",
                        "day": {
                            "$dateToString": {
                                "format": "%Y-%m-%d",
                                "date": "$events.at",
                            }
                        },
                    },
                    "n": {"$sum": 1},
                }
            },
        ]
    )
    async for row in cases:
        person(row["_id"]["id"]).day(row["_id"]["day"]).cases += row["n"]


async def _load_audit(
    database: AsyncDatabase[Any], start: datetime, person: Callable[[str], Person]
) -> int:
    """Counts sanctions and pardons; returns the admin rows that have no Discord id."""
    unattributed = 0
    audit = await database["moderation_audit"].aggregate(
        [
            {
                "$match": {
                    "created_at_epoch_ms": {"$gte": int(start.timestamp() * 1000)},
                    "action": {"$in": [*SANCTIONS, *PARDONS]},
                }
            },
            {
                "$group": {
                    "_id": {
                        "id": "$actor.discord_id",
                        "type": "$actor.type",
                        "action": "$action",
                        "discord": {"$eq": ["$origin.channel", "DISCORD"]},
                        "day": {
                            "$dateToString": {
                                "format": "%Y-%m-%d",
                                "date": {"$toDate": "$created_at_epoch_ms"},
                            }
                        },
                    },
                    "n": {"$sum": 1},
                }
            },
        ]
    )
    async for row in audit:
        key, n = row["_id"], row["n"]
        if not key.get("id"):
            # Console and system rows have no Discord id by design; only admins should.
            if key.get("type") == "PLAYER_ADMIN":
                unattributed += n
            continue
        _count(
            person(key["id"]).day(key["day"]),
            key["action"],
            n,
            bool(key.get("discord")),
        )
    return unattributed


def _count(counts: Counts, action: str, n: int, discord: bool) -> None:
    match action:
        case "BAN":
            counts.bans += n
            counts.bans_discord += n if discord else 0
        case "MUTE":
            counts.mutes += n
        case "KICK":
            counts.kicks += n
        case _:
            counts.pardons += n


async def _load_minutes(
    database: AsyncDatabase[Any], start: datetime, person: Callable[[str], Person]
) -> None:
    async for doc in database["staff_activity"].find({"day": {"$gte": _day(start)}}):
        counts = person(doc["discord_id"]).day(doc["day"])
        counts.minutes += int(doc.get("minutes", 0))
        counts.staff_minutes += int(doc.get("staff_minutes", 0))


async def _load_last_seen(
    database: AsyncDatabase[Any], people: dict[str, Person], ids: list[str]
) -> None:
    """Last action and last day on a server, over all time, not only the window."""
    audit = await database["moderation_audit"].aggregate(
        [
            {"$match": {"actor.discord_id": {"$in": ids}}},
            {
                "$group": {
                    "_id": "$actor.discord_id",
                    "last": {"$max": "$created_at_epoch_ms"},
                }
            },
        ]
    )
    async for row in audit:
        _touch(people[row["_id"]], datetime.fromtimestamp(row["last"] / 1000, UTC))

    cases = await database["cases"].aggregate(
        [
            {"$match": {"events.actor.discord_id": {"$in": ids}}},
            {"$unwind": "$events"},
            {
                "$match": {
                    "events.actor.discord_id": {"$in": ids},
                    "events.type": {"$in": list(STAFF_EVENTS)},
                }
            },
            {
                "$group": {
                    "_id": "$events.actor.discord_id",
                    "last": {"$max": "$events.at"},
                }
            },
        ]
    )
    async for row in cases:
        _touch(people[row["_id"]], row["last"])

    seen = await database["staff_activity"].aggregate(
        [
            {"$match": {"discord_id": {"$in": ids}}},
            {"$group": {"_id": "$discord_id", "last": {"$max": "$day"}}},
        ]
    )
    async for row in seen:
        people[row["_id"]].last_seen = date.fromisoformat(row["last"])


def _touch(person: Person, at: datetime) -> None:
    at = at if at.tzinfo else at.replace(tzinfo=UTC)
    if person.last_action is None or at > person.last_action:
        person.last_action = at


def _ago(value: date | None, today: date) -> str:
    if value is None:
        return "never"
    days = (today - value).days
    return "today" if days <= 0 else f"{days}d ago"


def _hours(minutes: int) -> str:
    return f"{minutes // 60}h {minutes % 60:02d}m" if minutes >= 60 else f"{minutes}m"


def counts_line(counts: Counts) -> str:
    bans = f"bans {counts.bans}" + (
        f" ({counts.bans_discord} via Discord)" if counts.bans_discord else ""
    )
    return (
        f"cases {counts.cases} · {bans} · mutes {counts.mutes} · kicks {counts.kicks}"
        f" · pardons {counts.pardons} · {_hours(counts.minutes)}"
        f" (staff {_hours(counts.staff_minutes)})"
    )


def person_line(
    person: Person, today: date, names: Mapping[str, str] | None = None
) -> str:
    """``names``: display names by Discord id. A client shows an embed mention as raw
    ``<@id>`` until it has that user cached, so the name keeps the line readable."""
    last_action = person.last_action.date() if person.last_action else None
    name = (names or {}).get(person.discord_id)
    who = f"<@{person.discord_id}>" + (f" ({name})" if name else "")
    return (
        f"{who} {counts_line(person.total)}"
        f" · action {_ago(last_action, today)} · seen {_ago(person.last_seen, today)}"
    )


LEGEND = (
    "No totals: a report closed with a ban counts as a closed case and as a ban. "
    "A report closed automatically after an in-game ban counts only as the ban. "
    f"Inactive: no counted action and under {INACTIVE_MINUTES} active minutes. "
    "People hired inside the window can show as inactive. Days are UTC."
)


def summary_lines(
    report: Report, today: date, names: Mapping[str, str] | None = None
) -> list[str]:
    roster = [report.person(i) for i in report.roster]
    inactive = sorted(
        (p for p in roster if p.inactive),
        key=lambda p: p.last_action or datetime.min.replace(tzinfo=UTC),
    )
    active = sorted(
        (p for p in roster if not p.inactive),
        key=lambda p: (
            p.total.cases,
            p.total.bans + p.total.mutes + p.total.kicks,
            p.total.minutes,
        ),
        reverse=True,
    )
    lines: list[str] = []
    if inactive:
        lines.append(f"**Inactive ({len(inactive)})**")
        lines += [person_line(p, today, names) for p in inactive]
    lines.append(f"**Active ({len(active)})**")
    lines += [person_line(p, today, names) for p in active] or ["nobody"]
    if outside := report.outside_roster:
        lines.append(
            f"**Acted in the window, not in the staff roles ({len(outside)})**"
        )
        lines += [person_line(p, today, names) for p in outside]
    if report.unattributed:
        lines.append(
            f"In-game actions by admins without a linked Discord: {report.unattributed}"
        )
    return lines


def member_lines(
    report: Report,
    discord_id: str,
    today: date,
    names: Mapping[str, str] | None = None,
) -> list[str]:
    person = report.person(discord_id)
    lines = [person_line(person, today, names)]
    for day in sorted(person.days, reverse=True):
        lines.append(f"`{day}` {counts_line(person.days[day])}")
    if len(lines) == 1:
        lines.append("Nothing in this window.")
    return lines


def pages(lines: list[str], limit: int = 3500) -> list[str]:
    """Embed descriptions of at most ``limit`` characters, split between lines."""
    result: list[str] = []
    current = ""
    for line in lines:
        if current and len(current) + len(line) + 1 > limit:
            result.append(current)
            current = ""
        current = f"{current}\n{line}" if current else line
    return [*result, current] if current else result or [""]
