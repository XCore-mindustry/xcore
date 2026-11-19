"""The Hexed rank thresholds, read from the storage contract's hexed-ranks.v1.json.

The plugin bundles the same file into its jar, so the two cannot disagree on a rank. The wheel
carries a copy of the file (see pyproject.toml); a checkout reads it from protocol/storage.
"""

from __future__ import annotations

import json
from importlib import resources
from pathlib import Path

FILE = "hexed-ranks.v1.json"


def _load() -> tuple[int, ...]:
    bundled = resources.files(__package__).joinpath(FILE)
    if bundled.is_file():
        text = bundled.read_text(encoding="utf-8")
    else:
        text = (
            Path(__file__).resolve().parents[4] / "protocol" / "storage" / FILE
        ).read_text(encoding="utf-8")
    return tuple(int(points) for points in json.loads(text)["points"])


# The points each rank needs; players.hexed_rank is an index into this.
POINTS: tuple[int, ...] = _load()


def rank_for(points: int) -> int:
    """The highest rank the points reach; below the first threshold, the first rank, as in the plugin."""
    return max(
        (rank for rank, needed in enumerate(POINTS) if points >= needed), default=0
    )
