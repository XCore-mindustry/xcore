"""``/maps freeze``: "leave this server's maps alone for now".

A frozen server still takes changes: the freeze is a note with a reason that every change
of the server's maps shows first (an accepted review, ``/maps upload``, ``install``,
``rollback`` and ``remove``), so nobody overwrites a map during an event or a test by
accident. One document per frozen server in ``map_freezes``.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime
from typing import TYPE_CHECKING, Any

import discord

from .i18n import t
from .map_list import map_text

if TYPE_CHECKING:
    from .bot import XCoreDiscordBot

logger = logging.getLogger(__name__)

COLLECTION = "map_freezes"
REASON_LIMIT = 300


@dataclass(frozen=True)
class Freeze:
    server: str
    reason: str
    by_id: str
    by_name: str
    at: datetime | None


class MapFreezes:
    def __init__(self, bot: XCoreDiscordBot) -> None:
        self._bot = bot

    @property
    def _collection(self) -> Any:
        return self._bot.store.database[COLLECTION]

    async def get(self, server: str) -> Freeze | None:
        doc = await self._collection.find_one({"_id": server})
        return _freeze(doc) if doc else None

    async def all(self) -> list[Freeze]:
        docs = await self._collection.find({}).to_list(length=None)
        return sorted((_freeze(doc) for doc in docs), key=lambda f: f.server)

    async def freeze(self, server: str, reason: str, user: discord.abc.User) -> None:
        await self._collection.replace_one(
            {"_id": server},
            {
                "_id": server,
                "reason": reason.strip()[:REASON_LIMIT],
                "by": {
                    "discord_id": str(user.id),
                    "name": getattr(user, "display_name", "") or "",
                },
                "at": self._bot.store.now_utc(),
            },
            upsert=True,
        )

    async def unfreeze(self, server: str) -> bool:
        result = await self._collection.delete_one({"_id": server})
        return bool(getattr(result, "deleted_count", 0))


def _freeze(doc: dict[str, Any]) -> Freeze:
    by = doc.get("by") or {}
    return Freeze(
        server=doc["_id"],
        reason=doc.get("reason", ""),
        by_id=str(by.get("discord_id", "")),
        by_name=by.get("name", ""),
        at=doc.get("at"),
    )


async def frozen(bot: XCoreDiscordBot, server: str) -> Freeze | None:
    """The server's freeze; ``None`` also when it cannot be read, so a broken store never
    blocks a change."""
    try:
        return await MapFreezes(bot).get(server)
    except (
        Exception
    ):  # The note is advisory; a lookup failure must not stop the change.
        logger.warning("Could not read the freeze of %s", server, exc_info=True)
        return None


def frozen_line(locale: str, freeze: Freeze) -> str:
    """The warning a change of a frozen server's maps starts with."""
    return t(
        locale,
        "maps-frozen-warning",
        server=map_text(freeze.server, inline_code=True),
        user=f"<@{freeze.by_id}>" if freeze.by_id else "?",
        reason=map_text(freeze.reason, REASON_LIMIT)
        or t(locale, "maps-frozen-no-reason"),
    )
