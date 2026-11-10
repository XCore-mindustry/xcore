from __future__ import annotations

import functools
import io
import logging
import math
from collections.abc import Callable
from typing import TYPE_CHECKING, Any

import discord
from discord import Interaction

from .command_helpers import reply_locale
from .i18n import t
from .map_card import find_map, map_card
from .map_list import map_list_page_items, map_list_row, map_text
from .permissions import general_admin_role_ids, has_any_role, map_reviewer_role_ids
from .server_views import MapDetailsView, MapOption, MapsListView
from .services.map_images import (
    PLAIN,
    MapImageKind,
    MapRef,
    RendererBusy,
    RendererUnavailable,
    RenderStyle,
)
from .utils.mindustry_colors import strip_mindustry_colors

if TYPE_CHECKING:
    from .bot import XCoreDiscordBot


logger = logging.getLogger(__name__)


async def cmd_maps(
    bot: XCoreDiscordBot,
    interaction: Interaction,
    server: str,
    creator: discord.abc.User | None = None,
) -> None:
    locale = reply_locale(bot, interaction)
    await interaction.response.defer()
    try:
        maps = await bot.container.rpc.list_maps(
            server=server, timeout_ms=bot.settings.rpc_timeout_ms
        )
    except TimeoutError:
        await interaction.followup.send(t(locale, "maps-list-timeout"))
        return

    if creator is not None:
        maps = [
            item for item in maps if item.get("creator_discord_id") == str(creator.id)
        ]
    if not maps:
        await interaction.followup.send(
            t(
                locale,
                "maps-list-empty-creator" if creator else "maps-list-empty",
                server=server,
            )
        )
        return

    default_sort_mode = "reputation"
    # Paging only re-reads these: each sort is laid out once, and the menu's values are
    # positions in ``maps``, looked up by identity rather than by searching the list.
    positions = {id(item): index for index, item in enumerate(maps)}
    layouts: dict[str, list[list[dict[str, str]]]] = {}

    async def fetch_page(
        page: int, sort_mode: str
    ) -> tuple[discord.Embed, bool, list[MapOption]]:
        if sort_mode not in layouts:
            layouts[sort_mode] = map_list_page_items(maps, sort_mode, locale)
        pages = layouts[sort_mode]
        items = pages[page]
        chunk = [map_list_row(item, locale) for item in items]
        options = [map_option(positions[id(item)], item, locale) for item in items]
        embed = discord.Embed(
            title=t(locale, "maps-list-title", server=server),
            description="\n".join(chunk),
            color=discord.Color.green(),
        )
        footer = t(
            locale,
            "maps-list-footer",
            sort=t(locale, f"maps-list-sort-{sort_mode}"),
            page=page + 1,
            pages=len(pages),
            total=len(maps),
            shown=len(chunk),
        )
        if any(item.get("uploaded_by_discord_id") for item in maps):
            footer += " • " + t(locale, "maps-list-legend")
        embed.set_footer(text=footer)
        return embed, page + 1 < len(pages), options

    async def open_map(opened: Interaction, value: str) -> None:
        opener_locale = reply_locale(bot, opened)
        await opened.response.defer(ephemeral=True, thinking=True)
        index = int(value) if value.isdigit() else -1
        if not 0 <= index < len(maps):
            await opened.followup.send(
                t(opener_locale, "maps-list-open-gone"), ephemeral=True
            )
            return
        await send_map_card(
            bot, opened, server, maps[index], opener_locale, ephemeral=True
        )

    first_embed, has_next, first_options = await fetch_page(0, default_sort_mode)
    view = MapsListView(
        page=0,
        has_prev=False,
        has_next=has_next,
        sort_mode=default_sort_mode,
        fetch_page=fetch_page,
        locale=locale,
        options=first_options,
        open_map=open_map,
    )
    sent = await interaction.followup.send(embed=first_embed, view=view)
    view.bot_message = sent


async def cmd_map_details(
    bot: XCoreDiscordBot,
    interaction: Interaction,
    server: str,
    file_name: str,
) -> None:
    """The card of one map: its details, the thumbnail and, on request, the detailed render."""
    locale = reply_locale(bot, interaction)
    await interaction.response.defer()
    try:
        maps = await bot.container.rpc.list_maps(
            server=server, timeout_ms=bot.settings.rpc_timeout_ms
        )
    except TimeoutError:
        await interaction.followup.send(t(locale, "maps-list-timeout"))
        return
    item = find_map(maps, file_name)
    if item is None:
        await interaction.followup.send(
            t(
                locale,
                "maps-card-not-found",
                server=map_text(server, inline_code=True),
                file=map_text(file_name, 100, inline_code=True),
            ),
            allowed_mentions=discord.AllowedMentions.none(),
        )
        return

    await send_map_card(bot, interaction, server, item, locale)


def map_option(index: int, item: dict[str, str], locale: str) -> MapOption:
    """The map as an entry of the open menu; the value is its index in the listed maps."""
    unknown = t(locale, "maps-list-unknown")
    name = strip_mindustry_colors(item.get("name", "")).strip() or unknown
    author = strip_mindustry_colors(item.get("author", "")).strip() or unknown
    return name, str(index), f"{author} • {item.get('file_name', '')}"


async def _card_picture(
    bot: XCoreDiscordBot,
    card: Callable[..., discord.Embed],
    ref: MapRef,
    kind: MapImageKind,
    style: RenderStyle = PLAIN,
) -> tuple[discord.Embed, discord.File] | str:
    """The card with this picture of the map, or the message key of why there is none."""
    try:
        image = await bot.container.map_images.get(ref, kind, style)
    except TimeoutError:
        return RENDER_TIMEOUT
    except RendererBusy:
        return RENDER_BUSY
    except RendererUnavailable:
        return RENDER_UNAVAILABLE
    except Exception:
        logger.exception(
            "Could not draw %s of %s on %s", kind, ref.file_name, ref.server
        )
        image = None
    if image is None:
        return RENDER_FAILED
    name = image.attachment_name(ref, kind, style)
    file = discord.File(io.BytesIO(image.png), filename=name)
    return card(image_name=name, render_ms=image.render_ms), file


async def _review(
    bot: XCoreDiscordBot, content_hash: str
) -> tuple[str | None, str | None]:
    """The map forum post where this file was reviewed, if it came that way, and who fixed
    the map when the post fixed someone else's map."""
    if not content_hash or not bot.settings.cases_maps_enabled:
        return None, None
    try:
        case = await bot.container.cases.store.by_map_file(content_hash)
    except Exception:
        logger.warning(
            "Could not look up the review of %s", content_hash, exc_info=True
        )
        return None, None
    if case is None:
        return None, None
    fixer = (case.payload.get("fixer") or {}).get("discord_id")
    return case.discord.get("thread_id") or None, fixer or None


RENDER_TIMEOUT = "maps-card-render-timeout"
RENDER_BUSY = "maps-card-render-busy"
RENDER_FAILED = "maps-card-render-failed"
RENDER_UNAVAILABLE = "maps-card-render-unavailable"
# Nothing was drawn for the presser: these cost no wait. A timed-out render keeps it,
# since the renderer may still be drawing.
REFUNDED = frozenset((RENDER_BUSY, RENDER_FAILED, RENDER_UNAVAILABLE))


async def _card_review(
    bot: XCoreDiscordBot, item: dict[str, str], content_hash: str
) -> tuple[str | None, str | None]:
    """The review post of the map and who fixed it, for its card."""
    review_thread_id, fixed_by = await _review(bot, content_hash)
    # Servers at protocol 0.19.0 keep the review in the map's own attribution.
    review_thread_id = review_thread_id or item.get("thread_id") or None
    if item.get("fixed_by_discord_id"):
        fixed_by = None  # the credits line names them already
    return review_thread_id, fixed_by


async def send_map_card(
    bot: XCoreDiscordBot,
    interaction: Interaction,
    server: str,
    item: dict[str, str],
    locale: str,
    *,
    ephemeral: bool = False,
) -> None:
    """Sends the card of a listed map as a follow-up of a deferred interaction."""
    images = bot.container.map_images
    ref = MapRef(server, item.get("file_name", ""), item.get("content_hash", ""))

    review_thread_id, fixed_by = await _card_review(bot, item, ref.content_hash)
    card = functools.partial(
        map_card,
        server,
        item,
        locale,
        review_thread_id=review_thread_id,
        fixed_by=fixed_by,
    )

    async def picture(
        kind: MapImageKind, style: RenderStyle = PLAIN
    ) -> tuple[discord.Embed, discord.File] | str:
        return await _card_picture(bot, card, ref, kind, style)

    thumbnail = await picture(MapImageKind.THUMBNAIL)
    view: MapDetailsView | None = None
    kwargs: dict[str, Any] = {
        "allowed_mentions": discord.AllowedMentions.none(),
        "ephemeral": ephemeral,
    }
    cooldown = bot.container.render_cooldown

    settings = bot.settings
    unlimited = (*map_reviewer_role_ids(settings), *general_admin_role_ids(settings))

    async def render(
        presser: discord.User | discord.Member, style: RenderStyle
    ) -> tuple[discord.Embed, discord.File] | str:
        kind = MapImageKind.DETAILED
        # Only a new render costs the wait; one already drawn, or being drawn for someone
        # else, is shown without it.
        if images.cached(ref, kind, style) is not None or images.drawing(
            ref, kind, style
        ):
            drawn = await picture(kind, style)
            return t(locale, drawn) if isinstance(drawn, str) else drawn
        # Map reviewers and admins render without the wait.
        if has_any_role(presser, unlimited):
            drawn = await picture(kind, style)
            return t(locale, drawn) if isinstance(drawn, str) else drawn
        wait = cooldown.remaining(presser.id)
        if wait > 0:
            return t(locale, "maps-card-render-cooldown", seconds=math.ceil(wait))
        # Started before the render, so pressing again meanwhile waits too.
        cooldown.start(presser.id)
        drawn = await picture(kind, style)
        if isinstance(drawn, str) and drawn in REFUNDED:
            cooldown.cancel(presser.id)
        return t(locale, drawn) if isinstance(drawn, str) else drawn

    # No button while the renderer is down, unless the default render is still cached.
    if images.cached(ref, MapImageKind.DETAILED) is not None or await images.available(
        MapImageKind.DETAILED
    ):
        view = MapDetailsView(render=render, locale=locale)
        kwargs["view"] = view
    if isinstance(thumbnail, str):
        kwargs["embed"] = card()
    else:
        kwargs["embed"], kwargs["file"] = thumbnail
    sent = await interaction.followup.send(**kwargs)
    if view is not None:
        view.bot_message = sent
