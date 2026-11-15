from __future__ import annotations

import os
from datetime import UTC, date, datetime
from uuid import uuid4

import pytest
from pymongo import AsyncMongoClient

from xcore_discord_bot import staff_activity
from xcore_discord_bot.staff_activity import Counts, Person, Report

NOW = datetime(2026, 11, 15, 12, 0, tzinfo=UTC)


def test_window_includes_today_and_starts_at_utc_midnight() -> None:
    assert staff_activity.window_start(NOW, 1) == datetime(2026, 11, 15, tzinfo=UTC)
    assert staff_activity.window_start(NOW, 14) == datetime(2026, 11, 2, tzinfo=UTC)


def _person(discord_id: str, **counts: int) -> Person:
    return Person(discord_id, total=Counts(**counts))


def test_summary_puts_inactive_first_and_lists_people_outside_the_roster() -> None:
    report = Report(
        start=NOW,
        days=14,
        people={
            "1": _person("1", cases=3),
            "2": _person("2", minutes=10),
            "3": _person("3", bans=1),
            "9": _person("9", mutes=2),
        },
        roster=frozenset({"1", "2", "3", "4"}),
        unattributed=2,
    )

    lines = staff_activity.summary_lines(report, NOW.date())

    assert lines[0] == "**Inactive (2)**"
    assert {lines[1][:5], lines[2][:5]} == {"<@2> ", "<@4> "}
    assert lines[3] == "**Active (2)**"
    assert lines[4].startswith("<@1> cases 3")
    assert lines[5].startswith("<@3> ")
    assert lines[6].startswith("**Acted in the window, not in the staff roles (1)**")
    assert lines[7].startswith("<@9> ")
    assert lines[8].endswith(": 2")


def test_member_view_lists_days_newest_first() -> None:
    person = Person(
        "1", days={"2026-11-10": Counts(bans=1), "2026-11-12": Counts(cases=2)}
    )
    report = Report(NOW, 14, {"1": person}, frozenset({"1"}), 0)

    lines = staff_activity.member_lines(report, "1", NOW.date())

    assert lines[0].startswith("<@1> ")
    assert lines[1].startswith("`2026-11-12` cases 2")
    assert lines[2].startswith("`2026-11-10` cases 0 · bans 1")
    assert staff_activity.member_lines(report, "2", NOW.date())[1] == (
        "Nothing in this window."
    )


def test_pages_split_between_lines() -> None:
    lines = ["a" * 30] * 10
    pages = staff_activity.pages(lines, limit=100)
    assert all(len(page) <= 100 for page in pages)
    assert "\n".join(pages).split("\n") == lines


@pytest.mark.skipif(not os.getenv("XCORE_TEST_MONGO_URL"), reason="No test MongoDB")
async def test_load_counts_each_source_by_discord_id() -> None:
    client: AsyncMongoClient = AsyncMongoClient(
        os.environ["XCORE_TEST_MONGO_URL"], serverSelectionTimeoutMS=5000
    )
    database = client[f"xcore_staff_activity_test_{uuid4().hex}"]
    day = datetime(2026, 11, 14, 10, 0, tzinfo=UTC)
    old = datetime(2026, 10, 1, tzinfo=UTC)
    ms = int(day.timestamp() * 1000)

    def event(kind: str, actor: str | None, at: datetime) -> dict:
        return {"type": kind, "at": at, "actor": {"discord_id": actor}}

    def audit(action: str, who: str | None, **extra: object) -> dict:
        row = {
            "action": action,
            "created_at_epoch_ms": ms,
            "actor": {"type": "PLAYER_ADMIN", "discord_id": who},
            "origin": {"channel": "IN_GAME"},
        }
        row.update(extra)
        return row

    try:
        await database["cases"].insert_many(
            [
                {
                    "_id": 1,
                    "events": [event("taken", "1", day), event("action", "1", day)],
                },
                {
                    "_id": 2,
                    "events": [
                        event("rejected", "1", day),
                        event("reopened", "2", day),
                    ],
                },
                # Closed by the bot after an in-game ban: nobody gets the case.
                {"_id": 3, "events": [event("action", None, day)]},
                {"_id": 4, "events": [event("action", "1", old)]},
            ]
        )
        await database["moderation_audit"].insert_many(
            [
                audit("BAN", "1"),
                audit("BAN", "1", origin={"channel": "DISCORD"}),
                audit("MUTE", "2"),
                audit("KICK", "2"),
                audit("UNBAN", "2"),
                audit("NOTE", "2"),
                audit("MERGE", "2"),
                audit("BAN", None),
                audit("BAN", None, actor={"type": "SERVER_CONSOLE"}),
                audit("BAN", "5"),
            ]
        )
        await database["staff_activity"].insert_many(
            [
                {
                    "_id": "1:2026-11-14",
                    "discord_id": "1",
                    "day": "2026-11-14",
                    "minutes": 90,
                    "staff_minutes": 60,
                },
                {
                    "_id": "2:2026-10-01",
                    "discord_id": "2",
                    "day": "2026-10-01",
                    "minutes": 5,
                    "staff_minutes": 0,
                },
            ]
        )

        report = await staff_activity.load(database, ["1", "2", "3"], 14, NOW)

        one = report.person("1").total
        assert (
            one.cases,
            one.bans,
            one.bans_discord,
            one.minutes,
            one.staff_minutes,
        ) == (2, 2, 1, 90, 60)
        two = report.person("2")
        assert (
            two.total.cases,
            two.total.mutes,
            two.total.kicks,
            two.total.pardons,
        ) == (0, 1, 1, 1)
        assert two.total.minutes == 0
        assert two.last_seen == date(2026, 10, 1)
        assert report.person("1").last_action == day
        assert report.person("3").inactive
        assert report.unattributed == 1
        assert [p.discord_id for p in report.outside_roster] == ["5"]
        assert set(report.person("1").days) == {"2026-11-14"}
    finally:
        await client.drop_database(database.name)
        await client.close()
