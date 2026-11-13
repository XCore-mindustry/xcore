"""Text from the game (nicknames, map and server names, reasons) as Discord can show it.

The game keeps colour markup in what it stores: a nickname is saved as the player's coloured name,
an admin who bans in game is recorded under theirs. Shown as is, Discord prints the tags.
"""

from __future__ import annotations

import discord

from .mindustry_colors import strip_mindustry_colors


def plain(text: object, fallback: str = "") -> str:
    """Without colour tags, on one line, with no surrounding blanks; ``fallback`` if nothing is left.

    For places Discord does not read markdown in: modal titles, select options, autocomplete.
    """
    stripped = " ".join(strip_mindustry_colors(str(text or "")).split())
    return stripped or fallback


def clean(text: object, fallback: str = "") -> str:
    """Game text for ordinary message or embed text: no colour tags, no markdown of its own."""
    stripped = plain(text)
    return discord.utils.escape_markdown(stripped) if stripped else fallback


def code(text: object, fallback: str = "") -> str:
    """Game text for a code span, where markdown is not read and a backtick would end it."""
    stripped = plain(text)
    return stripped.replace("`", "'") if stripped else fallback
