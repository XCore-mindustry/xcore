"""Game text (coloured nicknames, admin names, map names) where the bot shows it in Discord."""

from __future__ import annotations

from dataclasses import replace
from datetime import timedelta
from types import SimpleNamespace
from typing import Any

from test_maps_autocomplete import _autocomplete_bot, _Interaction
from test_player_replies_i18n import _season
from test_staff_actions import ACTOR, PLAYER, _action_bot

from xcore_discord_bot.cases.cards import safe_text
from xcore_discord_bot.cogs.autocomplete import autocomplete_map_file
from xcore_discord_bot.moderation_logs import format_admin_value
from xcore_discord_bot.moderation_modals import _build_modal_title
from xcore_discord_bot.presentation import build_moderation_reversal_embed
from xcore_discord_bot.rating_store import PodiumEntry, RankedStanding
from xcore_discord_bot.season_embeds import build_season_top_embed, podium_line
from xcore_discord_bot.staff_actions import perform_ban
from xcore_discord_bot.utils.game_text import clean, code, plain
from xcore_discord_bot.utils.mindustry_colors import strip_mindustry_colors_fully

# How the game stores a nickname: the player's coloured name.
COLOURED = "[scarlet]Ta[stat]r_get[]"


def test_plain_drops_colours_and_folds_lines() -> None:
    assert plain(COLOURED) == "Tar_get"
    assert plain(" [red]a\n  b ") == "a b"
    assert plain("[red][]", "Unknown") == "Unknown"
    assert plain(None, "Unknown") == "Unknown"


def test_clean_escapes_markdown_and_code_keeps_the_span_closed() -> None:
    assert clean(COLOURED) == "Tar\\_get"
    assert code("[red]a`b") == "a'b"
    assert clean("", "Unknown") == code("", "Unknown") == "Unknown"


async def test_a_ban_reply_names_the_player_without_colours_but_stores_them() -> None:
    bot, db, _game, _channel = await _action_bot()
    result = await perform_ban(
        bot,
        player=replace(PLAYER, nickname=COLOURED),
        duration=timedelta(days=1),
        reason="griefing",
        actor=ACTOR,
        request_id="request-1",
    )
    assert result.message.startswith("Banned `Tar_get` until")
    # The game reads the ban back, so it keeps the name as the game wrote it.
    assert db["bans"].docs[0]["name"] == COLOURED


def test_an_admin_who_acted_in_game_is_shown_without_colours() -> None:
    assert format_admin_value(admin_name="[accent]Admin", admin_discord_id=None) == (
        "Admin"
    )
    assert format_admin_value(admin_name="[red]Admin", admin_discord_id="5") == (
        "Admin (<@5>)"
    )


def test_reversal_embed_and_modal_title_show_the_plain_name() -> None:
    embed = build_moderation_reversal_embed(
        action_label="Unbanned",
        subject_name=COLOURED,
        player_id=42,
        previous_actor_label="Admin who banned",
        previous_actor_value="Admin",
        reason="[red]griefing",
        expire_value=None,
        actor_label="Unbanned by",
        actor_name="Moderator",
        format_expire_date=lambda _: "Never",
    )
    assert embed.title == "Unbanned Tar\\_get"
    assert {field.name: field.value for field in embed.fields}["Reason"] == "griefing"
    assert _build_modal_title("Ban", replace(PLAYER, nickname=COLOURED)) == (
        "Ban Tar_get"
    )


def test_season_names_are_shown_without_colours() -> None:
    entry = PodiumEntry(
        place=1,
        uuid="u",
        pid=7,
        nickname=COLOURED,
        rating=1500,
        league="gold",
        matches=4,
        wins=3,
        discord_id=None,
        discord_username=None,
    )
    assert "**Tar\\_get**" in podium_line(entry)
    standing = RankedStanding(
        rank=1, uuid="u", nickname=COLOURED, pid=7, rating=1500, matches=4, wins=3
    )
    embed = build_season_top_embed(_season(), [standing], participants=1)
    assert "**Tar\\_get**" in str(embed.description)


async def test_map_autocomplete_shows_the_map_name_without_colours() -> None:
    interaction: Any = _Interaction(
        client=await _autocomplete_bot(
            maps=[{"name": "[accent]Glacier[]", "file_name": "glacier.msav"}]
        ),
        namespace=SimpleNamespace(server="survival"),
    )
    choices = await autocomplete_map_file(interaction, "gla")
    assert [choice.name for choice in choices] == ["Glacier (glacier.msav)"]


def test_masking_sees_the_name_without_any_tag() -> None:
    # One strip turns [[red]red] into [red]; the IP must be masked on what is finally shown,
    # and a strip of the result (format_admin_value cleans its name) must not rebuild it.
    masked = safe_text("192.168.[[red]red]1.1")
    assert "192.168" not in masked
    assert "192.168" not in format_admin_value(admin_name=masked, admin_discord_id=None)
    # A secret split by a tag is masked too.
    assert safe_text("uuid-[red]42xyz", secrets=("uuid-42xyz",)) == "…"


def test_full_strip_stops_when_no_tag_is_left() -> None:
    assert strip_mindustry_colors_fully("a[[red]red]b") == "ab"
    assert strip_mindustry_colors_fully("[[Tag]]") == "[[Tag]]"
