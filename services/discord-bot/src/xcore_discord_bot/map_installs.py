"""One map post on several servers: where it is installed, and installing a revision.

Which servers hold a post's map is read from the servers themselves: an upload from a
review records the post's thread in the map's attribution (``thread_id`` in ``maps.list``).
Each server is written on its own, with its own expectation, audit record and answer, so a
server that fails leaves the others installed and is retried with ``/maps install``.
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

import discord

from .map_backups import RPC_FAILURES
from .map_targets import expectation
from .staff_actions import (
    Actor,
    MapReview,
    MapUploadFailed,
    MapUploadFile,
    MapUploadTimeout,
    perform_map_upload,
)

if TYPE_CHECKING:
    from .bot import XCoreDiscordBot

logger = logging.getLogger(__name__)

# What a write of a revision ends with, besides the server's own upload statuses.
FILE_GONE = "file_gone"
TIMEOUT = "timeout"
UNCONFIRMED = "unconfirmed"
WRITTEN = frozenset({"installed", "metadata_failed"})


async def list_maps_or_none(
    bot: XCoreDiscordBot, server: str
) -> list[dict[str, str]] | None:
    """The server's maps as it lists them now, or ``None`` when it does not answer."""
    try:
        return await bot.container.rpc.list_maps(
            server=server, timeout_ms=bot.settings.rpc_timeout_ms
        )
    except RPC_FAILURES as error:
        logger.info("Could not list the maps of %s: %r", server, error)
        return None


def live_servers(bot: XCoreDiscordBot) -> list[str]:
    return sorted(s.name for s in bot.container.servers.get_all_servers())


@dataclass(frozen=True)
class Installed:
    """The file on ``server`` that came from the post."""

    server: str
    item: dict[str, str]

    @property
    def file_name(self) -> str:
        return self.item["file_name"]


def post_file(
    maps: Sequence[dict[str, str]] | None, thread_id: Any
) -> dict[str, str] | None:
    """The file in ``maps`` that was uploaded from the post ``thread_id``."""
    wanted = str(thread_id)
    return next(
        (item for item in maps or () if str(item.get("thread_id") or "") == wanted),
        None,
    )


async def post_installs(
    bot: XCoreDiscordBot, thread_id: Any, servers: Iterable[str]
) -> list[Installed]:
    """The servers among ``servers`` that hold a file from the post, asked together.

    A server that does not answer is left out: nothing can be written there now anyway.
    """
    names = list(servers)
    lists = await asyncio.gather(*(list_maps_or_none(bot, name) for name in names))
    return [
        Installed(name, item)
        for name, maps in zip(names, lists, strict=True)
        if (item := post_file(maps, thread_id)) is not None
    ]


def creator_of(item: dict[str, str] | None) -> Actor | None:
    """The creator a server records for an installed map."""
    if not item or not item.get("creator_discord_id"):
        return None
    return Actor(
        item["creator_discord_id"],
        item.get("creator_name", ""),
        item.get("creator_handle") or None,
    )


def fixer_of(case: Any) -> Actor | None:
    fixer = case.payload.get("fixer") or None
    if not fixer:
        return None
    return Actor(fixer["discord_id"], fixer.get("name", ""), fixer.get("handle"))


def default_creator(case: Any, *items: dict[str, str] | None) -> Actor | None:
    """Who the map's creator is on a server that gets it: the creator another server
    already records, else the post's author, unless the post fixed someone else's map."""
    for item in items:
        if (creator := creator_of(item)) is not None:
            return creator
    if fixer_of(case) is not None:
        return None
    return Actor(
        case.opener.discord_id,
        case.opener.discord_name or "",
        case.payload.get("creator_handle"),
    )


def review_of(case: Any, thread_id: Any) -> MapReview:
    return MapReview(
        case.number,
        str(thread_id) if thread_id else None,
        int(case.payload.get("revision") or 1),
        fixer_of(case),
    )


async def revision_file(thread: Any, case: Any) -> Any | None:
    """The attachment the case reviewed, if it is still in the post with the same bytes."""
    try:
        message = await thread.fetch_message(int(case.payload["message_id"]))
        attachment = next(
            a for a in message.attachments if str(a.id) == case.payload["attachment_id"]
        )
        data = await attachment.read()
    except (discord.HTTPException, StopIteration, KeyError, ValueError):
        return None
    if hashlib.sha256(data).hexdigest() != case.payload.get("sha256"):
        return None
    return attachment


async def write_revision(
    bot: XCoreDiscordBot,
    *,
    case: Any,
    attachment: Any,
    thread_id: Any,
    server: str,
    file_name: str,
    maps: list[dict[str, str]] | None,
    uploader: Actor,
    creator: Actor | None,
) -> str:
    """Writes the revision to one server under ``file_name``; the upload status, or
    ``TIMEOUT``/``UNCONFIRMED`` when the server's answer is unknown.

    The server must still hold what ``maps`` showed under that name, so a file that
    changed meanwhile answers ``conflict`` and stays as it is.
    """
    expected, absent = expectation(maps, file_name)
    try:
        result = await perform_map_upload(
            bot,
            server,
            [
                MapUploadFile(
                    attachment.url,
                    file_name,
                    expected_hash=expected,
                    expect_absent=absent,
                )
            ],
            uploader,
            creator,
            review=review_of(case, thread_id),
        )
    except MapUploadTimeout:
        return TIMEOUT
    except MapUploadFailed:
        logger.exception("Map upload to %s was not confirmed", server)
        return UNCONFIRMED
    return result.results[0].status.value


async def update_installs(
    bot: XCoreDiscordBot,
    *,
    case: Any,
    thread: Any,
    installs: Sequence[Installed],
    uploader: Actor,
) -> list[tuple[str, str]]:
    """Writes the case's revision over the post's file on each of ``installs``.

    Every server keeps its own creator; each is written separately and answers on its
    own, so the result lists ``(server, status)`` for every one, written or not.
    """
    attachment = await revision_file(thread, case)
    if attachment is None:
        return [(install.server, FILE_GONE) for install in installs]
    results = []
    for install in installs:
        maps = await list_maps_or_none(bot, install.server)
        current = post_file(maps, thread.id) if maps is not None else install.item
        if current is None:  # Removed meanwhile: there is nothing to update any more.
            results.append((install.server, "conflict"))
            continue
        results.append(
            (
                install.server,
                await write_revision(
                    bot,
                    case=case,
                    attachment=attachment,
                    thread_id=thread.id,
                    server=install.server,
                    file_name=current["file_name"],
                    maps=maps,
                    uploader=uploader,
                    creator=default_creator(case, current),
                ),
            )
        )
    return results
