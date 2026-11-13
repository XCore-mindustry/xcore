from __future__ import annotations

from xcore_discord_bot.utils.mindustry_colors import (
    _parse_color_markup,
    strip_mindustry_colors,
)


def test_strip_mindustry_named_colors() -> None:
    assert (
        strip_mindustry_colors("[scarlet]Attacker[] [white]Player") == "Attacker Player"
    )
    assert strip_mindustry_colors("[blue]Blue[red]Red") == "BlueRed"


def test_strip_mindustry_hex_colors() -> None:
    assert strip_mindustry_colors("[#ff0000]Red Text[]") == "Red Text"
    assert strip_mindustry_colors("[#12345678]Hex Alpha[]") == "Hex Alpha"


def test_strip_mindustry_nested_and_brackets() -> None:
    assert strip_mindustry_colors("[[Tag]]") == "[[Tag]]"
    assert strip_mindustry_colors("Normal [unclosed") == "Normal [unclosed"


def test_parse_color_markup() -> None:
    text = "[scarlet]"
    assert _parse_color_markup(text, 1, len(text)) == 7
    text = "[#ff0000]"
    assert _parse_color_markup(text, 1, len(text)) == 7
    text = "[notacolor]"
    assert _parse_color_markup(text, 1, len(text)) == -1


def test_strip_mindustry_own_colors() -> None:
    # Mindustry registers these beside Arc's; a nickname can use any of them.
    for name in ("accent", "unlaunched", "highlight", "stat", "negstat"):
        assert strip_mindustry_colors(f"[{name}]Name[]") == "Name"


def test_strip_matches_the_client_case_for_case() -> None:
    # Arc registers RED and LIGHT_GRAY, and red and lightgray; nothing else is a colour.
    assert strip_mindustry_colors("[RED]a[LIGHT_GRAY]b[lightgray]c[red]d") == "abcd"
    assert strip_mindustry_colors("[Red]a") == "[Red]a"
    assert strip_mindustry_colors("[light_gray]a") == "[light_gray]a"
    assert strip_mindustry_colors("[STAT]a") == "[STAT]a"
