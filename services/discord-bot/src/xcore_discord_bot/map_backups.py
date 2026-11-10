"""Copies of removed maps, the button that puts one back, and the maps log.

``/maps remove`` used to delete the file from the server and keep nothing. Now the bot
first asks the server for the file (``maps.file``), posts it in the channel as the record of
the removal, and only then removes it. The record in ``map_removals`` remembers what was
removed and from where; the **Restore** button under the message uploads that same file back
under the same name. The button is a dynamic item, so it still works after a restart.
"""

from __future__ import annotations

import hashlib
import logging
import re
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, cast

import discord
from bson import ObjectId

from .bus.transport import RpcError
from .command_helpers import reply_locale
from .dto import Actor
from .i18n import t
from .map_card import find_map
from .map_list import map_text
from .permissions import has_any_role, map_reviewer_role_ids
from .retry import TRANSIENT_EXCEPTIONS
from .staff_actions import (
    MapUploadFile,
    MapUploadTimeout,
    StaffActionError,
    perform_map_upload,
)

if TYPE_CHECKING:
    from .bot import XCoreDiscordBot

logger = logging.getLogger(__name__)

COLLECTION = "map_removals"
RESTORE_PATTERN = r"xcore:map-restore:(?P<id>[0-9a-f]{24})"
# Errors of a read RPC that mean "no answer this time", not a bug.
RPC_FAILURES: tuple[type[BaseException], ...] = (
    TimeoutError,
    RpcError,
    ValueError,
    *TRANSIENT_EXCEPTIONS,
)


@dataclass(frozen=True)
class Backup:
    """The bytes of an installed map file, as the server sent them."""

    data: bytes
    sha256: str


async def fetch_backup(
    bot: XCoreDiscordBot, server: str, file_name: str
) -> Backup | None:
    """The installed file, or ``None`` when the server cannot send it."""
    try:
        published = await bot.container.rpc.map_file(
            server=server, file_name=file_name, timeout_ms=bot.settings.rpc_timeout_ms
        )
        if published.status.value != "ok" or published.file is None:
            logger.info("No copy of %s on %s: %s", file_name, server, published.status)
            return None
        data = await bot.container.rpc.read_blob(published.file)
    except Exception as error:  # noqa: BLE001 -- a missing copy is shown; removing anyway is the person's call
        logger.warning("Could not copy %s from %s: %r", file_name, server, error)
        return None
    return Backup(data, hashlib.sha256(data).hexdigest())


def _creator(item: dict[str, str] | None) -> dict[str, str] | None:
    if not item or not item.get("creator_discord_id"):
        return None
    return {
        "discord_id": item["creator_discord_id"],
        "name": item.get("creator_name", ""),
        "handle": item.get("creator_handle", ""),
    }


class MapRemovals:
    """One document per removal that kept a copy of the file."""

    def __init__(self, bot: XCoreDiscordBot) -> None:
        self._bot = bot

    @property
    def _collection(self) -> Any:
        return self._bot.store.database[COLLECTION]

    async def record(
        self,
        *,
        server: str,
        file_name: str,
        sha256: str,
        item: dict[str, str] | None,
        removed_by: discord.abc.User,
    ) -> str:
        removal_id = ObjectId()
        await self._collection.insert_one(
            {
                "_id": removal_id,
                "server": server,
                "file_name": file_name,
                "sha256": sha256,
                "name": (item or {}).get("name", ""),
                "author": (item or {}).get("author", ""),
                "creator": _creator(item),
                "removed_by": {
                    "discord_id": str(removed_by.id),
                    "name": getattr(removed_by, "display_name", "") or "",
                },
                "removed_at": self._bot.store.now_utc(),
            }
        )
        return str(removal_id)

    async def get(self, removal_id: str) -> dict[str, Any] | None:
        return await self._collection.find_one({"_id": ObjectId(removal_id)})

    async def claim_restore(self, removal_id: str, user: discord.abc.User) -> bool:
        """Marks the removal as being restored; ``False`` if it is or was already."""
        row = await self._collection.find_one_and_update(
            {
                "_id": ObjectId(removal_id),
                "restored_at": {"$exists": False},
                "restoring": {"$exists": False},
            },
            {
                "$set": {
                    "restoring": {
                        "discord_id": str(user.id),
                        "at": self._bot.store.now_utc(),
                    }
                }
            },
        )
        return row is not None

    async def finish_restore(
        self, removal_id: str, user: discord.abc.User | None, *, restored: bool
    ) -> None:
        update: dict[str, Any] = {"$unset": {"restoring": ""}}
        if restored and user is not None:
            update["$set"] = {
                "restored_at": self._bot.store.now_utc(),
                "restored_by": {
                    "discord_id": str(user.id),
                    "name": getattr(user, "display_name", "") or "",
                },
            }
        await self._collection.update_one({"_id": ObjectId(removal_id)}, update)

    async def set_trash(self, removal_id: str, trash_id: str) -> None:
        """Where the server kept its own copy of the removed file (protocol 0.19.0)."""
        await self._collection.update_one(
            {"_id": ObjectId(removal_id)}, {"$set": {"trash_id": trash_id}}
        )

    async def set_message(
        self, removal_id: str, channel_id: int, message_id: int
    ) -> None:
        await self._collection.update_one(
            {"_id": ObjectId(removal_id)},
            {"$set": {"channel_id": str(channel_id), "message_id": str(message_id)}},
        )


def restore_custom_id(removal_id: str) -> str:
    return f"xcore:map-restore:{removal_id}"


class MapRestoreButton(
    discord.ui.DynamicItem[discord.ui.Button], template=RESTORE_PATTERN
):
    """**Restore** under the record of a removal: uploads the kept copy back."""

    def __init__(
        self, removal_id: str, *, label: str = "Restore", disabled: bool = False
    ) -> None:
        super().__init__(
            discord.ui.Button(
                label=label,
                emoji="♻️",
                style=discord.ButtonStyle.secondary,
                custom_id=restore_custom_id(removal_id),
                disabled=disabled,
            )
        )
        self.removal_id = removal_id

    @classmethod
    async def from_custom_id(
        cls,
        interaction: discord.Interaction,
        item: discord.ui.Item,
        match: re.Match[str],
    ) -> MapRestoreButton:
        return cls(match["id"])

    async def callback(self, interaction: discord.Interaction) -> None:
        bot = cast("XCoreDiscordBot", interaction.client)
        await restore_map(bot, interaction, self.removal_id)


def restore_view(
    removal_id: str, locale: str, *, disabled: bool = False
) -> discord.ui.View:
    view = discord.ui.View(timeout=None)
    view.add_item(
        MapRestoreButton(
            removal_id, label=t(locale, "maps-restore-button"), disabled=disabled
        )
    )
    return view


def _quoted(file_name: str) -> str:
    return "`" + map_text(file_name, 100, inline_code=True) + "`"


async def _taken(bot: XCoreDiscordBot, locale: str, row: dict[str, Any]) -> str | None:
    """Why the file cannot go back now, or ``None`` when its name is free."""
    server, file_name = row["server"], row["file_name"]
    try:
        maps = await bot.container.rpc.list_maps(
            server=server, timeout_ms=bot.settings.rpc_timeout_ms
        )
    except RPC_FAILURES:
        return t(
            locale, "maps-restore-no-server", server=map_text(server, inline_code=True)
        )
    present = find_map(maps, file_name)
    if present is None:
        return None
    # Restoring would overwrite whatever took the name since; that is a new decision.
    same = str(present.get("content_hash", "")).lower() == row.get("sha256")
    return t(
        locale,
        "maps-restore-present-same" if same else "maps-restore-present",
        file=_quoted(file_name),
        server=map_text(server, inline_code=True),
    )


async def _from_server(
    bot: XCoreDiscordBot, locale: str, row: dict[str, Any]
) -> tuple[bool, str] | None:
    """Puts the server's own copy back, with its attribution; ``None`` when it has none."""
    if not row.get("trash_id"):
        return None
    try:
        answer = await bot.container.rpc.restore_map(
            server=row["server"],
            trash_id=row["trash_id"],
            file_name=row["file_name"],
            timeout_ms=bot.settings.rpc_timeout_ms,
        )
    except TimeoutError:
        return False, t(locale, "maps-upload-timeout")
    except RPC_FAILURES:
        return None
    status = answer.status.value
    if status in {"not_found", "failed"}:
        return None  # the Discord copy is the way left
    bot.container.map_catalog.forget(row["server"])
    bot.container.map_images.forget(row["server"])
    return status == "restored", t(
        locale,
        "maps-restore-result",
        file=_quoted(row["file_name"]),
        server=map_text(row["server"], inline_code=True),
        status=t(locale, "maps-restore-status-" + status),
    )


async def _from_discord(
    bot: XCoreDiscordBot,
    locale: str,
    row: dict[str, Any],
    attachment: Any,
    user: discord.abc.User,
) -> tuple[bool, str]:
    """Uploads the copy posted in Discord, with the creator the map had."""
    creator_row = row.get("creator") or None
    creator = (
        Actor(
            creator_row["discord_id"],
            creator_row.get("name") or "",
            creator_row.get("handle") or None,
        )
        if creator_row
        else None
    )
    uploader = Actor(
        str(user.id),
        getattr(user, "display_name", "") or user.name,
        getattr(user, "name", None),
    )
    file_name = row["file_name"]
    try:
        result = await perform_map_upload(
            bot,
            row["server"],
            [MapUploadFile(attachment.url, file_name, expect_absent=True)],
            uploader,
            creator,
        )
    except MapUploadTimeout:
        return False, t(locale, "maps-upload-timeout")
    except StaffActionError:
        return False, t(locale, "maps-upload-unconfirmed")
    status = result.results[0].status.value
    return status in {"installed", "metadata_failed"}, t(
        locale,
        "maps-restore-result",
        file=_quoted(file_name),
        server=map_text(row["server"], inline_code=True),
        status=t(locale, "maps-upload-label-" + status),
    )


async def restore_map(
    bot: XCoreDiscordBot, interaction: discord.Interaction, removal_id: str
) -> None:
    """**Restore**: the server's own copy first, else the copy posted in Discord."""
    locale = reply_locale(bot, interaction)
    if not has_any_role(interaction.user, map_reviewer_role_ids(bot.settings)):
        await interaction.response.send_message(
            t(locale, "maps-restore-denied"), ephemeral=True
        )
        return
    await interaction.response.defer(ephemeral=True, thinking=True)
    removals = MapRemovals(bot)
    row = await removals.get(removal_id)
    if row is not None and row.get("restored_at") is not None:
        await interaction.followup.send(
            t(locale, "maps-restore-done-already"), ephemeral=True
        )
        return
    message = interaction.message
    attachment = next(
        (
            a
            for a in (message.attachments if message else [])
            if row is not None and a.filename == row["file_name"]
        ),
        None,
    )
    if row is None or (attachment is None and not row.get("trash_id")):
        await interaction.followup.send(t(locale, "maps-restore-gone"), ephemeral=True)
        return
    problem = await _taken(bot, locale, row)
    if problem is not None:
        await interaction.followup.send(problem, ephemeral=True)
        return
    if not await removals.claim_restore(removal_id, interaction.user):
        await interaction.followup.send(t(locale, "maps-restore-busy"), ephemeral=True)
        return
    restored = False
    try:
        outcome = await _from_server(bot, locale, row)
        if outcome is None and attachment is not None:
            outcome = await _from_discord(
                bot, locale, row, attachment, interaction.user
            )
        restored, text = outcome or (False, t(locale, "maps-restore-gone"))
    finally:
        await removals.finish_restore(removal_id, interaction.user, restored=restored)
    await interaction.followup.send(text, ephemeral=True)
    if restored:
        await _restored(
            bot,
            locale,
            interaction,
            message,
            removal_id,
            row["file_name"],
            row["server"],
        )


async def _restored(
    bot, locale, interaction, message, removal_id, file_name, server
) -> None:
    if message is not None:
        try:
            await message.edit(view=restore_view(removal_id, locale, disabled=True))
        except discord.HTTPException:
            logger.warning("Could not disable the restore button of %s", removal_id)
    await post_maps_log(
        bot,
        t(
            bot.settings.cases_staff_locale,
            "maps-log-restored",
            user=f"<@{interaction.user.id}>",
            file=_quoted(file_name),
            server=map_text(server, inline_code=True),
        ),
    )


async def post_maps_log(
    bot: XCoreDiscordBot, content: str, *, file: discord.File | None = None
) -> discord.Message | None:
    """Writes one line to the maps log channel, if one is configured. Never raises."""
    channel_id = bot.settings.discord_maps_log_channel_id
    if not channel_id:
        return None
    try:
        channel = await bot.resolve_channel(channel_id, context="maps log")
        if channel is None:
            return None
        kwargs: dict[str, Any] = {"allowed_mentions": discord.AllowedMentions.none()}
        if file is not None:
            kwargs["file"] = file
        return await channel.send(content[:2000], **kwargs)
    except Exception:  # The log informs; the action it records already happened.
        logger.warning("Could not write to the maps log", exc_info=True)
        return None
