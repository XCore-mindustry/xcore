"""The card of one map, as /map shows it."""

from __future__ import annotations

import discord

from .i18n import DEFAULT, t
from .map_list import map_contributor, map_text
from .presentation import format_size
from .utils.mindustry_colors import strip_mindustry_colors


def _int(item: dict[str, str], key: str) -> int | None:
    value = str(item.get(key, "")).strip()
    return int(value) if value.lstrip("-").isdigit() else None


def _float(item: dict[str, str], key: str) -> float | None:
    try:
        return float(str(item.get(key, "")).strip())
    except ValueError:
        return None


def find_map(maps: list[dict[str, str]], file_name: str) -> dict[str, str] | None:
    """The entry for ``file_name``; the name is matched exactly first, then ignoring case."""
    wanted = file_name.strip()
    for item in maps:
        if item.get("file_name") == wanted:
            return item
    folded = wanted.casefold()
    for item in maps:
        if str(item.get("file_name", "")).casefold() == folded:
            return item
    return None


def _rating(item: dict[str, str], locale: str) -> str:
    like, dislike = _int(item, "like"), _int(item, "dislike")
    reputation = _int(item, "reputation")
    if like is None and dislike is None and reputation is None:
        return ""
    rating = f"👍 {like or 0} • 👎 {dislike or 0}"
    if reputation is not None:
        rating += " • " + t(locale, "maps-list-reputation", reputation=reputation)
    return rating


def _activity(item: dict[str, str], locale: str) -> str:
    popularity, interest = _float(item, "popularity"), _float(item, "interest")
    if popularity is None and interest is None:
        return ""
    return t(
        locale,
        "maps-card-activity-value",
        popularity=popularity or 0.0,
        interest=interest or 0.0,
    )


def _credits(item: dict[str, str]) -> str:
    """Who added the map through Discord, and when; empty for maps added otherwise."""
    if not item.get("uploaded_by_discord_id"):
        return ""
    credits = []
    if item.get("creator_discord_id"):
        credits.append(f"🎨 {map_contributor(item, 'creator')}")
    credits.append(f"⬆ {map_contributor(item, 'uploaded_by')}")
    uploaded_at = item.get("uploaded_at", "")
    if uploaded_at.isascii() and uploaded_at.isdigit():
        credits.append(f"<t:{int(uploaded_at) // 1000}:d>")
    return " • ".join(credits)


def _id(value: str | None) -> bool:
    return bool(value) and value.isascii() and value.isdigit()  # type: ignore[union-attr]


def _review_fields(
    embed: discord.Embed, locale: str, thread_id: str | None, fixed_by: str | None
) -> None:
    """Who fixed the map, when a post fixed someone else's map, and where it was reviewed."""
    if _id(fixed_by):
        embed.add_field(name=t(locale, "maps-card-fixed-by"), value=f"<@{fixed_by}>")
    if _id(thread_id):
        embed.add_field(name=t(locale, "maps-card-review"), value=f"<#{thread_id}>")


def map_card(
    server: str,
    item: dict[str, str],
    locale: str = DEFAULT,
    *,
    image_name: str | None = None,
    review_thread_id: str | None = None,
    render_ms: int | None = None,
    fixed_by: str | None = None,
) -> discord.Embed:
    """The card of one map.

    ``image_name`` is the attachment drawn under the fields; ``review_thread_id`` the map
    forum post where this file was reviewed; ``render_ms`` how long the renderer drew it.
    """
    unknown = t(locale, "maps-list-unknown")
    name = map_text(strip_mindustry_colors(item.get("name", "")) or unknown, 200)
    author = map_text(
        strip_mindustry_colors(item.get("author", "")) or unknown, inline_code=True
    )
    embed = discord.Embed(title=name, color=discord.Color.green())
    embed.add_field(name=t(locale, "maps-card-author"), value=f"`{author}`")

    width, height = _int(item, "width"), _int(item, "height")
    if width is not None and height is not None:
        embed.add_field(name=t(locale, "maps-card-size"), value=f"{width}×{height}")
    mode = str(item.get("game_mode", "")).strip()
    if mode:
        embed.add_field(name=t(locale, "maps-card-mode"), value=map_text(mode, 40))

    for key, value in (
        ("maps-card-rating", _rating(item, locale)),
        ("maps-card-activity", _activity(item, locale)),
    ):
        if value:
            embed.add_field(name=t(locale, key), value=value)
    credits = _credits(item)
    if credits:
        embed.add_field(
            name=t(locale, "maps-card-discord"), value=credits, inline=False
        )

    _review_fields(embed, locale, review_thread_id, fixed_by)

    file_line = f"`{map_text(item.get('file_name', ''), 100, inline_code=True)}`"
    size = _int(item, "file_size_bytes")
    if size is not None and size >= 0:
        file_line += f" • {format_size(size)}"
    embed.add_field(name=t(locale, "maps-card-file"), value=file_line, inline=False)

    if image_name:
        embed.set_image(url=f"attachment://{image_name}")
    footer = t(locale, "maps-card-footer", server=server)
    if render_ms is not None:
        footer += " • " + t(locale, "maps-render-time", seconds=render_ms / 1000)
    embed.set_footer(text=footer)
    return embed
