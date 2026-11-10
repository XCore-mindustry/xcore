"""``/maps upload``, ``/maps remove`` and ``/maps duplicates``: changing a server's maps.

An upload is first compared with the maps already on the server (``map_targets``). A file
that adds nothing new, or that replaces its own earlier version under the same name, is
uploaded at once; one that looks like another installed map, or would overwrite a different
map, waits for the reviewer to choose. ``replace:`` installs the file under the name of the
map it replaces, so its statistics and attribution stay with it and no second copy appears.

A removal shows the map's card, warns about other files with the same map, and keeps a copy
of the file with a **Restore** button (``map_backups``) before the server deletes it.
"""

from __future__ import annotations

import asyncio
import hashlib
import io
import logging
import secrets
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

import discord
from discord import Interaction

from .cases.msav import InvalidMsav, validate_msav
from .command_helpers import claim_mutation, reply_locale
from .i18n import t
from .map_backups import (
    RPC_FAILURES,
    Backup,
    MapRemovals,
    fetch_backup,
    post_maps_log,
    restore_view,
)
from .map_card import find_map, map_card
from .map_list import map_text
from .map_targets import (
    Candidate,
    Incoming,
    Match,
    Outcome,
    duplicate_groups,
    find_candidates,
    outcome,
    replacement_targets,
)
from .staff_actions import (
    Actor,
    InvalidMapUpload,
    MapUploadFailed,
    MapUploadFile,
    MapUploadTimeout,
    perform_map_upload,
    prepare_map_upload,
)
from .utils.mindustry_colors import strip_mindustry_colors

if TYPE_CHECKING:
    from .bot import XCoreDiscordBot

logger = logging.getLogger(__name__)

NO_MENTIONS = discord.AllowedMentions.none()
VIEW_TIMEOUT = 300
REMOVED_PREFIX = "Successfully removed"


# --- shared presentation --------------------------------------------------------------


def _code(value: str, limit: int = 100) -> str:
    return f"`{map_text(value, limit, inline_code=True)}`"


def describe(candidate: Candidate, locale: str) -> str:
    """One installed map as the reviewer sees it: file, map name, author and why it matched."""
    item = candidate.item
    unknown = t(locale, "maps-list-unknown")
    name = map_text(strip_mindustry_colors(item.get("name", "")) or unknown, 60)
    author = map_text(strip_mindustry_colors(item.get("author", "")) or unknown, 40)
    reasons = ", ".join(
        t(locale, "maps-match-" + match.value)
        for match in (Match.SAME_BYTES, Match.SAME_FILE, Match.SAME_TITLE)
        if match in candidate.reasons
    )
    return t(
        locale,
        "maps-candidate",
        file=_code(candidate.file_name),
        name=name,
        author=author,
        reasons=reasons,
    )


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


class _ChoiceButton(discord.ui.Button["ChoiceView"]):
    def __init__(self, key: str, label: str, style: discord.ButtonStyle) -> None:
        super().__init__(label=label[:80], style=style)
        self.key = key

    async def callback(self, interaction: Interaction) -> None:
        if self.view is not None:
            await self.view.chosen(interaction, self.key)


class ChoiceView(discord.ui.View):
    """Buttons only the person who ran the command can press; the first press wins."""

    def __init__(
        self,
        requester_id: int,
        locale: str,
        choices: Sequence[tuple[str, str, discord.ButtonStyle]],
        on_choice: Callable[[Interaction, str], Awaitable[None]],
    ) -> None:
        super().__init__(timeout=VIEW_TIMEOUT)
        self.requester_id = requester_id
        self.locale = locale
        self.on_choice = on_choice
        self.done = False
        self.message: Any = None
        for key, label, style in choices:
            self.add_item(_ChoiceButton(key, label, style))

    async def chosen(self, interaction: Interaction, key: str) -> None:
        if interaction.user.id != self.requester_id:
            await interaction.response.send_message(
                t(self.locale, "error-not-allowed"), ephemeral=True
            )
            return
        if self.done:
            await interaction.response.defer()
            return
        self.done = True
        self.stop()
        await self.on_choice(interaction, key)

    async def on_timeout(self) -> None:
        if self.done or self.message is None:
            return
        try:
            await self.message.edit(
                content=t(self.locale, "maps-choice-expired"), embed=None, view=None
            )
        except (discord.HTTPException, AttributeError):
            pass


# --- /maps upload ---------------------------------------------------------------------


@dataclass
class PlannedFile:
    source: str  # the attachment's name
    upload: MapUploadFile  # ``filename`` is the name it gets on the server
    incoming: Incoming
    candidates: list[Candidate]
    outcome: Outcome | None  # None while the server's list is unknown
    replaces: str | None = None  # the installed file a person chose to replace


async def read_incoming(bot: XCoreDiscordBot, attachment: Any, target: str) -> Incoming:
    """The file's hash, map name and author; only its name when it cannot be read."""
    size = getattr(attachment, "size", None)
    if isinstance(size, int) and size > bot.settings.maps_max_file_bytes:
        return Incoming(target)
    try:
        data = await attachment.read()
        info = await asyncio.to_thread(
            validate_msav,
            data,
            attachment.filename,
            max_file_bytes=bot.settings.maps_max_file_bytes,
        )
    except (discord.HTTPException, InvalidMsav, AttributeError, TypeError):
        return Incoming(target)
    name = info.meta.get("name") or info.meta.get("mapname")
    return Incoming(target, info.sha256, name, info.meta.get("author"))


def _plan_line(file: PlannedFile, locale: str) -> str:
    target = _code(file.upload.filename)
    if file.replaces is not None:
        return t(locale, "maps-plan-replace", source=_code(file.source), file=target)
    if file.outcome is None:
        return t(locale, "maps-plan-unchecked", file=target)
    if file.outcome != Outcome.CONFIRM:
        return t(locale, "maps-plan-" + file.outcome.value, file=target)
    key = (
        "maps-plan-overwrite"
        if any(
            c.overwritten_foreign
            for c in file.candidates
            if c.file_name.casefold() == file.upload.filename.casefold()
        )
        else "maps-plan-duplicate"
    )
    lines = [t(locale, key, file=target)]
    lines += ["  • " + describe(c, locale) for c in file.candidates[:5]]
    return "\n".join(lines)


def _result_text(
    locale: str, server: str, planned: list[PlannedFile], results: Sequence[Any]
) -> str:
    rows = [
        t(locale, "maps-upload-result-title", server=map_text(server, inline_code=True))
    ]
    for file, result in zip(planned, results, strict=False):
        status = result.status.value
        row = t(
            locale,
            "maps-upload-row",
            status=t(locale, "maps-upload-label-" + status),
            file=_code(result.fileName),
        )
        if status in {"installed", "metadata_failed"}:
            if file.source != file.upload.filename:
                row += " · " + t(
                    locale, "maps-upload-note-replaced", source=_code(file.source)
                )
            elif file.replaces is not None:
                row += " · " + t(locale, "maps-upload-note-update")
            elif file.outcome in {Outcome.UPDATE, Outcome.SAME, Outcome.NEW}:
                row += " · " + t(locale, "maps-upload-note-" + file.outcome.value)
        rows.append(row)
    if any(file.outcome is None for file in planned):
        rows.append(
            t(
                locale,
                "maps-upload-unchecked",
                server=map_text(server, inline_code=True),
            )
        )
    return "\n".join(rows)


def _identity(user: discord.abc.User) -> Actor:
    return Actor(
        str(user.id),
        getattr(user, "display_name", None) or user.name,
        handle=getattr(user, "name", None),
    )


async def cmd_upload_map(  # noqa: C901
    bot: XCoreDiscordBot,
    interaction: Interaction,
    server: str,
    attachments: list[discord.Attachment | None],
    creator: discord.abc.User | None = None,
    creators: list[discord.abc.User | None] | None = None,
    replace: str | None = None,
) -> None:
    locale = reply_locale(bot, interaction)
    replace = (replace or "").strip() or None
    uploader = _identity(interaction.user)
    sources = [
        (index, attachment)
        for index, attachment in enumerate(attachments)
        if attachment is not None and attachment.filename.lower().endswith(".msav")
    ]
    if replace is not None and len(sources) != 1:
        await interaction.response.send_message(
            t(locale, "maps-upload-replace-one"), ephemeral=True
        )
        return

    def own_creator(index: int) -> Actor | None:
        """The creator given for this attachment's position, overriding the default."""
        chosen = creators[index] if creators and index < len(creators) else None
        return _identity(chosen) if chosen is not None else None

    files = [
        MapUploadFile(
            attachment.url, replace or attachment.filename, creator=own_creator(index)
        )
        for index, attachment in sources
    ]
    creator_actor = _identity(creator) if creator is not None else None
    try:
        prepare_map_upload(server, files, uploader, creator_actor)
    except InvalidMapUpload as error:
        await interaction.response.send_message(
            t(locale, "maps-upload-invalid-" + error.code), ephemeral=True
        )
        return
    if not await claim_mutation(
        bot,
        interaction,
        operation="upload-map",
        scope=f"{server}:{len(files)}",
        locale=locale,
    ):
        return

    await interaction.response.send_message(
        t(
            locale,
            "maps-upload-checking",
            count=len(files),
            server=map_text(server, inline_code=True),
        ),
        allowed_mentions=NO_MENTIONS,
    )
    incomings = await asyncio.gather(
        *(
            read_incoming(bot, attachment, file.filename)
            for (_, attachment), file in zip(sources, files, strict=True)
        )
    )
    review = await _review_of(bot, interaction, incomings)
    if review is not None:
        await interaction.edit_original_response(
            content=t(locale, "maps-upload-under-review", number=review.number),
            allowed_mentions=NO_MENTIONS,
        )
        return
    maps = await list_maps_or_none(bot, server)
    if replace is not None and maps is not None:
        replaced = find_map(maps, replace)
        if replaced is None:
            await interaction.edit_original_response(
                content=t(
                    locale,
                    "maps-upload-replace-missing",
                    file=_code(replace),
                    server=map_text(server, inline_code=True),
                ),
                allowed_mentions=NO_MENTIONS,
            )
            return
        # The installed spelling: on the server's disk another case is another file.
        replace = replaced["file_name"]
        files = [MapUploadFile(f.url, replace, f.creator) for f in files]
    planned = []
    for (_, attachment), file, incoming in zip(sources, files, incomings, strict=True):
        candidates = find_candidates(maps, incoming) if maps is not None else []
        planned.append(
            PlannedFile(
                source=attachment.filename,
                upload=file,
                incoming=incoming,
                candidates=candidates,
                outcome=outcome(candidates, file.filename)
                if maps is not None
                else None,
                replaces=replace,
            )
        )

    async def finish(
        edit: Callable[..., Awaitable[Any]], chosen: list[PlannedFile]
    ) -> None:
        await _finish_upload(
            bot, edit, locale, server, chosen, uploader, creator_actor, interaction.user
        )

    if replace is not None or not any(p.outcome == Outcome.CONFIRM for p in planned):
        await finish(interaction.edit_original_response, planned)
        return

    choices: list[tuple[str, str, discord.ButtonStyle]] = []
    targets = (
        replacement_targets(planned[0].candidates, planned[0].upload.filename)
        if len(planned) == 1
        else []
    )
    for index, target in enumerate(targets[:3]):
        choices.append(
            (
                f"replace:{index}",
                t(locale, "maps-upload-button-replace", file=target),
                discord.ButtonStyle.primary,
            )
        )
    choices.append(
        ("as-is", t(locale, "maps-upload-button-as-is"), discord.ButtonStyle.secondary)
    )
    choices.append(
        ("cancel", t(locale, "maps-upload-button-cancel"), discord.ButtonStyle.danger)
    )

    async def on_choice(pressed: Interaction, key: str) -> None:
        if key == "cancel":
            await pressed.response.edit_message(
                content=t(locale, "maps-upload-cancelled"), view=None
            )
            return
        chosen = planned
        if key.startswith("replace:"):
            target = targets[int(key.removeprefix("replace:"))]
            file = planned[0]
            chosen = [
                PlannedFile(
                    source=file.source,
                    upload=MapUploadFile(file.upload.url, target, file.upload.creator),
                    incoming=file.incoming,
                    candidates=file.candidates,
                    outcome=file.outcome,
                    replaces=target,
                )
            ]
        await pressed.response.edit_message(
            content=t(
                locale,
                "maps-upload-uploading",
                count=len(chosen),
                server=map_text(server, inline_code=True),
            ),
            view=None,
        )
        await finish(pressed.edit_original_response, chosen)

    view = ChoiceView(interaction.user.id, locale, choices, on_choice)
    text = "\n".join(
        [
            t(
                locale,
                "maps-upload-confirm-title",
                server=map_text(server, inline_code=True),
            )
        ]
        + [_plan_line(p, locale) for p in planned]
    )
    await interaction.edit_original_response(
        content=text[:2000], view=view, allowed_mentions=NO_MENTIONS
    )
    view.message = await interaction.original_response()


async def _review_of(
    bot: XCoreDiscordBot, interaction: Interaction, incomings: Sequence[Incoming]
) -> Any:
    """The open review of one of these files, when the command runs in its map post.

    Uploading it by hand would install it past the review: the case would stay open and the
    author would get no creator, badge or answer.
    """
    channel = getattr(interaction, "channel", None)
    if (
        not bot.settings.cases_maps_enabled
        or getattr(channel, "parent_id", None) != bot.settings.discord_maps_forum_id
        or channel is None
    ):
        return None
    try:
        case = await bot.container.cases.store.by_thread(channel.id)
    except Exception:  # A failed lookup must not block the upload itself.
        logger.warning("Could not look up the review of %s", channel.id, exc_info=True)
        return None
    if case is None or not case.active or not case.payload.get("sha256"):
        return None
    hashes = {incoming.sha256 for incoming in incomings if incoming.sha256}
    return case if case.payload["sha256"] in hashes else None


async def _finish_upload(
    bot: XCoreDiscordBot,
    edit: Callable[..., Awaitable[Any]],
    locale: str,
    server: str,
    planned: list[PlannedFile],
    uploader: Actor,
    creator: Actor | None,
    user: discord.abc.User,
) -> None:
    try:
        response = await perform_map_upload(
            bot, server, [p.upload for p in planned], uploader, creator
        )
    except MapUploadTimeout:
        await edit(
            content=t(locale, "maps-upload-timeout"), allowed_mentions=NO_MENTIONS
        )
        return
    except MapUploadFailed:
        logger.exception("Map upload confirmation failed for %s", server)
        await edit(
            content=t(locale, "maps-upload-unconfirmed"), allowed_mentions=NO_MENTIONS
        )
        return
    await edit(
        content=_result_text(locale, server, planned, response.results)[:2000],
        allowed_mentions=NO_MENTIONS,
    )
    staff = bot.settings.cases_staff_locale
    await post_maps_log(
        bot,
        t(staff, "maps-log-upload", user=f"<@{user.id}>")
        + "\n"
        + _result_text(staff, server, planned, response.results),
    )


# --- /maps remove ---------------------------------------------------------------------


@dataclass(frozen=True)
class RemoveOutcome:
    text: str
    removed: bool


async def perform_remove_map(
    bot: XCoreDiscordBot,
    *,
    server: str,
    file_name: str,
    request_nonce: str,
    locale: str = "en",
) -> RemoveOutcome:
    claim_key = f"remove-map:{server}:{file_name}:{request_nonce}"
    if not await bot.bus.claim_idempotency(claim_key, ttl_seconds=600):
        return RemoveOutcome(t(locale, "maps-remove-duplicate"), False)
    try:
        result = await bot.container.rpc.remove_map(
            server=server,
            file_name=file_name,
            timeout_ms=bot.settings.rpc_timeout_ms,
        )
    except TimeoutError:
        return RemoveOutcome(t(locale, "maps-remove-timeout"), False)
    finally:
        bot.container.map_catalog.forget(server)
        bot.container.map_images.forget(server)
    removed = result.startswith(REMOVED_PREFIX)
    return RemoveOutcome(
        t(
            locale,
            "maps-remove-done" if removed else "maps-remove-failed",
            server=map_text(server, inline_code=True),
            file=_code(file_name),
            detail=map_text(result, 300),
        ),
        removed,
    )


def _siblings(maps: list[dict[str, str]], item: dict[str, str]) -> list[Candidate]:
    """Other files on the server with the same bytes, or the same map name and author."""
    incoming = Incoming(
        str(item.get("file_name", "")),
        str(item.get("content_hash", "")) or None,
        item.get("name"),
        item.get("author"),
    )
    return [c for c in find_candidates(maps, incoming) if c.item is not item]


async def cmd_remove_map(
    bot: XCoreDiscordBot,
    interaction: Interaction,
    server: str,
    file_name: str,
) -> None:
    locale = reply_locale(bot, interaction)
    normalized = file_name.strip()
    if not normalized:
        await interaction.response.send_message(
            t(locale, "maps-remove-empty"), ephemeral=True
        )
        return
    await interaction.response.defer(ephemeral=True, thinking=True)
    maps = await list_maps_or_none(bot, server)
    item = find_map(maps, normalized) if maps is not None else None
    if maps is not None and item is None:
        await interaction.followup.send(
            t(
                locale,
                "maps-card-not-found",
                server=map_text(server, inline_code=True),
                file=map_text(normalized, 100, inline_code=True),
            ),
            ephemeral=True,
            allowed_mentions=NO_MENTIONS,
        )
        return
    exact = item["file_name"] if item is not None else normalized
    backup = await fetch_backup(bot, server, exact)
    lines = [
        t(
            locale,
            "maps-remove-question",
            file=_code(exact),
            server=map_text(server, inline_code=True),
        )
    ]
    if maps is None:
        lines.append(t(locale, "maps-remove-unlisted"))
    siblings = _siblings(maps, item) if maps is not None and item is not None else []
    if siblings:
        lines.append(t(locale, "maps-remove-siblings"))
        lines += ["  • " + describe(other, locale) for other in siblings[:5]]
    lines.append(t(locale, "maps-remove-backup" if backup else "maps-remove-no-backup"))
    nonce = secrets.token_hex(6)

    async def on_choice(pressed: Interaction, key: str) -> None:
        if key == "cancel":
            await pressed.response.edit_message(
                content=t(locale, "maps-remove-cancelled"), embed=None, view=None
            )
            return
        await pressed.response.edit_message(
            content=t(locale, "maps-remove-removing", file=_code(exact)), view=None
        )
        text = await _remove_with_backup(
            bot, pressed, locale, server, exact, item, backup, nonce
        )
        await pressed.edit_original_response(content=text, embed=None, view=None)

    view = ChoiceView(
        interaction.user.id,
        locale,
        [
            (
                "remove",
                t(locale, "maps-remove-button-remove"),
                discord.ButtonStyle.danger,
            ),
            (
                "cancel",
                t(locale, "maps-remove-button-cancel"),
                discord.ButtonStyle.secondary,
            ),
        ],
        on_choice,
    )
    kwargs: dict[str, Any] = {
        "ephemeral": True,
        "view": view,
        "allowed_mentions": NO_MENTIONS,
    }
    if item is not None:
        kwargs["embed"] = map_card(server, item, locale)
    view.message = await interaction.followup.send("\n".join(lines)[:2000], **kwargs)


async def _remove_with_backup(
    bot: XCoreDiscordBot,
    pressed: Interaction,
    locale: str,
    server: str,
    file_name: str,
    item: dict[str, str] | None,
    backup: Backup | None,
    nonce: str,
) -> str:
    """Posts the copy in the channel first, then removes; the copy is dropped if nothing was removed."""
    staff = bot.settings.cases_staff_locale
    record_id: str | None = None
    public: Any = None
    if backup is not None:
        try:
            record_id = await MapRemovals(bot).record(
                server=server,
                file_name=file_name,
                sha256=backup.sha256,
                item=item,
                removed_by=pressed.user,
            )
            public = await pressed.followup.send(
                t(
                    locale,
                    "maps-remove-public-pending",
                    user=f"<@{pressed.user.id}>",
                    file=_code(file_name),
                    server=map_text(server, inline_code=True),
                ),
                file=discord.File(io.BytesIO(backup.data), filename=file_name),
                allowed_mentions=NO_MENTIONS,
                wait=True,
            )
        except Exception:  # No copy is kept; removing is still the person's call.
            logger.warning(
                "Could not keep a copy of %s from %s", file_name, server, exc_info=True
            )
            public = None
    result = await perform_remove_map(
        bot, server=server, file_name=file_name, request_nonce=nonce, locale=locale
    )
    public_text = t(
        locale,
        "maps-remove-public-done" if result.removed else "maps-remove-public-failed",
        user=f"<@{pressed.user.id}>",
        file=_code(file_name),
        server=map_text(server, inline_code=True),
    )
    try:
        if public is not None and result.removed and record_id is not None:
            await public.edit(content=public_text, view=restore_view(record_id, locale))
            await MapRemovals(bot).set_message(record_id, public.channel.id, public.id)
        elif public is not None:
            await public.edit(content=public_text, attachments=[])
        elif result.removed:
            await pressed.followup.send(
                public_text + "\n" + t(locale, "maps-remove-public-no-backup"),
                allowed_mentions=NO_MENTIONS,
            )
    except discord.HTTPException:
        logger.warning(
            "Could not update the record of removing %s", file_name, exc_info=True
        )
    if result.removed:
        await post_maps_log(
            bot,
            t(
                staff,
                "maps-log-removed",
                user=f"<@{pressed.user.id}>",
                file=_code(file_name),
                server=map_text(server, inline_code=True),
            )
            + (f" {public.jump_url}" if public is not None else ""),
        )
    return result.text


# --- /maps duplicates -----------------------------------------------------------------


async def cmd_duplicates(
    bot: XCoreDiscordBot, interaction: Interaction, server: str
) -> None:
    locale = reply_locale(bot, interaction)
    await interaction.response.defer(ephemeral=True, thinking=True)
    maps = await list_maps_or_none(bot, server)
    if maps is None:
        await interaction.followup.send(t(locale, "maps-list-timeout"), ephemeral=True)
        return
    groups = duplicate_groups(maps)
    if not groups:
        await interaction.followup.send(
            t(
                locale,
                "maps-duplicates-none",
                server=map_text(server, inline_code=True),
            ),
            ephemeral=True,
        )
        return
    lines: list[str] = []
    shown = 0
    for match, items in groups:
        block = [t(locale, "maps-duplicates-" + match.value)]
        block += [
            "  • " + describe(Candidate(item, frozenset({match})), locale)
            for item in items
        ]
        text = "\n".join(block)
        if sum(len(line) + 1 for line in lines) + len(text) > 3800:
            break
        lines.append(text)
        shown += 1
    if shown < len(groups):
        lines.append(t(locale, "maps-more", count=len(groups) - shown))
    embed = discord.Embed(
        title=t(locale, "maps-duplicates-title", server=server),
        description="\n\n".join(lines),
        color=discord.Color.orange(),
    )
    await interaction.followup.send(
        embed=embed, ephemeral=True, allowed_mentions=NO_MENTIONS
    )


# --- /maps rollback -------------------------------------------------------------------


class _SelectView(discord.ui.View):
    """One select only the person who ran the command can use."""

    def __init__(
        self,
        requester_id: int,
        locale: str,
        options: list[discord.SelectOption],
        on_choice: Callable[[Interaction, str], Awaitable[None]],
    ) -> None:
        super().__init__(timeout=VIEW_TIMEOUT)
        self.requester_id, self.locale, self.on_choice = requester_id, locale, on_choice
        self.select: discord.ui.Select[_SelectView] = discord.ui.Select(
            placeholder=t(locale, "cases-choice-placeholder"), options=options[:25]
        )
        self.select.callback = self._chosen  # type: ignore[method-assign]
        self.add_item(self.select)

    async def _chosen(self, interaction: Interaction) -> None:
        if interaction.user.id != self.requester_id:
            await interaction.response.send_message(
                t(self.locale, "error-not-allowed"), ephemeral=True
            )
            return
        self.stop()
        await self.on_choice(interaction, self.select.values[0])


def _accepted(cases: Sequence[Any]) -> list[Any]:
    return [
        case
        for case in cases
        if case.resolution is not None
        and case.resolution.code == "accepted"
        and case.resolution.server
        and case.resolution.file_name
    ]


async def cmd_rollback(bot: XCoreDiscordBot, interaction: Interaction) -> None:
    """Put an earlier accepted revision of this post's map back on its server."""
    locale = reply_locale(bot, interaction)
    await interaction.response.defer(ephemeral=True, thinking=True)
    thread = interaction.channel
    if (
        thread is None
        or getattr(thread, "parent_id", None) != bot.settings.discord_maps_forum_id
    ):
        await interaction.followup.send(
            t(locale, "maps-review-in-post"), ephemeral=True
        )
        return
    accepted = _accepted(await bot.container.cases.store.map_cases(thread.id))
    if len(accepted) < 2:
        await interaction.followup.send(
            t(locale, "maps-rollback-nothing"), ephemeral=True
        )
        return
    current = accepted[-1]
    server, file_name = current.resolution.server, current.resolution.file_name
    options = [
        discord.SelectOption(
            label=t(
                locale,
                "maps-rollback-option",
                revision=case.payload.get("revision") or 1,
                number=case.number,
            )[:100],
            value=str(case.number),
            description=(case.payload.get("file_name") or "")[:100] or None,
        )
        for case in reversed(accepted[:-1])
    ]

    async def on_choice(pressed: Interaction, value: str) -> None:
        await pressed.response.edit_message(
            content=t(locale, "maps-rollback-running", file=_code(file_name)), view=None
        )
        text = await _rollback(
            bot, pressed, thread, server, file_name, int(value), current.number
        )
        await pressed.edit_original_response(content=text, view=None)

    view = _SelectView(interaction.user.id, locale, options, on_choice)
    await interaction.followup.send(
        t(
            locale,
            "maps-rollback-choose",
            file=_code(file_name),
            server=map_text(server, inline_code=True),
            number=current.number,
        ),
        view=view,
        ephemeral=True,
        allowed_mentions=NO_MENTIONS,
    )


async def _rollback(
    bot: XCoreDiscordBot,
    pressed: Interaction,
    thread: Any,
    server: str,
    file_name: str,
    number: int,
    current: int,
) -> str:
    locale = reply_locale(bot, pressed)
    case = await bot.container.cases.store.get(number)
    if case is None:
        return t(locale, "maps-rollback-file-gone", number=number)
    try:
        message = await thread.fetch_message(int(case.payload["message_id"]))
        attachment = next(
            a for a in message.attachments if str(a.id) == case.payload["attachment_id"]
        )
        data = await attachment.read()
    except (discord.HTTPException, StopIteration, KeyError, ValueError):
        return t(locale, "maps-rollback-file-gone", number=number)
    if hashlib.sha256(data).hexdigest() != case.payload.get("sha256"):
        return t(locale, "maps-rollback-file-gone", number=number)
    # The map keeps the creator it has on the server now.
    maps = await list_maps_or_none(bot, server) or []
    item = find_map(maps, file_name) or {}
    creator = (
        Actor(
            item["creator_discord_id"],
            item.get("creator_name", ""),
            item.get("creator_handle") or None,
        )
        if item.get("creator_discord_id")
        else None
    )
    try:
        result = await perform_map_upload(
            bot,
            server,
            [MapUploadFile(attachment.url, file_name)],
            _identity(pressed.user),
            creator,
        )
    except MapUploadTimeout:
        return t(locale, "maps-upload-timeout")
    except MapUploadFailed:
        return t(locale, "maps-upload-unconfirmed")
    status = result.results[0].status.value
    if status in {"installed", "metadata_failed"}:
        note = t(
            bot.settings.cases_staff_locale,
            "maps-rollback-note",
            user=f"<@{pressed.user.id}>",
            revision=case.payload.get("revision") or 1,
            number=number,
            current=current,
            file=_code(file_name),
            server=map_text(server, inline_code=True),
        )
        try:
            await thread.send(note, allowed_mentions=NO_MENTIONS)
        except discord.HTTPException:
            logger.warning("Could not note the rollback in %s", thread.id)
        await post_maps_log(bot, note + f" <#{thread.id}>")
    return t(
        locale,
        "maps-restore-result",
        file=_code(file_name),
        server=map_text(server, inline_code=True),
        status=t(locale, "maps-upload-label-" + status),
    )
