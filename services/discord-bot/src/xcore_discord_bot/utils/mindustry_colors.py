from __future__ import annotations

# The names Arc's colour markup knows, exactly as the client looks them up (case-sensitive):
# Arc registers each of its colours as an upper-case constant and again in lower case without
# underscores (``LIGHT_GRAY`` and ``lightgray``), and Mindustry adds a few of its own in lower case.
_ARC_COLORS = (
    "CLEAR",
    "BLACK",
    "WHITE",
    "LIGHT_GRAY",
    "GRAY",
    "DARK_GRAY",
    "LIGHT_GREY",
    "GREY",
    "DARK_GREY",
    "BLUE",
    "NAVY",
    "ROYAL",
    "SLATE",
    "SKY",
    "CYAN",
    "TEAL",
    "GREEN",
    "ACID",
    "LIME",
    "FOREST",
    "OLIVE",
    "YELLOW",
    "GOLD",
    "GOLDENROD",
    "ORANGE",
    "BROWN",
    "TAN",
    "BRICK",
    "RED",
    "SCARLET",
    "CRIMSON",
    "CORAL",
    "SALMON",
    "PINK",
    "MAGENTA",
    "PURPLE",
    "VIOLET",
    "MAROON",
)
_MINDUSTRY_COLORS = ("accent", "unlaunched", "highlight", "stat", "negstat")

MINDUSTRY_COLOR_NAMES = frozenset(
    (
        *_ARC_COLORS,
        *(name.lower().replace("_", "") for name in _ARC_COLORS),
        *_MINDUSTRY_COLORS,
    )
)


def strip_mindustry_colors(text: str) -> str:
    """Text as the client shows it without colour tags, like Arc's ``Strings.stripColors``."""
    out: list[str] = []
    i = 0
    n = len(text)

    while i < n:
        c = text[i]
        if c != "[":
            out.append(c)
            i += 1
            continue

        parsed_len = _parse_color_markup(text, i + 1, n)
        if parsed_len >= 0:
            i += parsed_len + 2
            continue

        out.append(c)
        i += 1

    return "".join(out)


def strip_mindustry_colors_fully(text: str) -> str:
    """Strips until no tag is left: ``[[red]red]`` comes out of one pass as ``[red]``.

    For text that is masked after stripping, so that no later strip can join a hidden value
    back together. Shows less than the client would for an escaped bracket.
    """
    while (stripped := strip_mindustry_colors(text)) != text:
        text = stripped
    return text


def _parse_color_markup(text: str, start: int, end: int) -> int:  # noqa: C901
    if start >= end:
        return -1

    ch0 = text[start]
    if ch0 == "#":
        i = start + 1
        while i < end:
            ch = text[i]
            if ch == "]":
                if i < start + 2 or i > start + 9:
                    return -1
                return i - start
            if not (ch.isdigit() or "a" <= ch <= "f" or "A" <= ch <= "F"):
                return -1
            i += 1
        return -1

    if ch0 == "[":
        return -2

    if ch0 == "]":
        return 0

    i = start + 1
    while i < end:
        if text[i] == "]":
            return i - start if text[start:i] in MINDUSTRY_COLOR_NAMES else -1
        i += 1

    return -1
