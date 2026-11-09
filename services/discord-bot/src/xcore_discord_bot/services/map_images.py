"""Pictures of maps: the thumbnail in /maps and the detailed render in /map.

The bot never decodes a map itself. A :class:`MapImageSource` produces the PNG, and
:class:`MapImages` keeps recently shown pictures, so paging back and forth or two
people opening the same map do not ask for it twice. See ``docs/map-images.md`` in this
service for where each kind of picture is meant to come from.
"""

from __future__ import annotations

import logging
import time
from collections import OrderedDict
from dataclasses import dataclass
from enum import StrEnum
from typing import Any, Protocol

logger = logging.getLogger(__name__)

# Discord rejects attachments above this size on servers without boosts.
MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024
CACHE_ENTRIES = 64
CACHE_TTL_SECONDS = 15 * 60


class MapImageKind(StrEnum):
    # One pixel per tile, coloured by block: cheap, the game server draws it.
    THUMBNAIL = "thumbnail"
    # Block sprites, like the game itself: expensive, drawn away from the game servers.
    DETAILED = "detailed"


@dataclass(frozen=True, slots=True)
class MapRef:
    """One installed map. ``content_hash`` names the bytes, once the server reports it."""

    server: str
    file_name: str
    content_hash: str = ""


@dataclass(frozen=True, slots=True)
class MapImage:
    png: bytes
    width: int
    height: int

    def attachment_name(self, ref: MapRef, kind: MapImageKind) -> str:
        stem = ref.file_name.rsplit(".", 1)[0]
        safe = "".join(
            c if c.isascii() and (c.isalnum() or c in "-_") else "_" for c in stem
        )
        return f"{safe[:60] or 'map'}-{kind.value}.png"


class MapImageSource(Protocol):
    """Produces one picture of one map, or ``None`` when it cannot.

    A source raises :class:`TimeoutError` when whoever draws the picture did not answer;
    every other failure is ``None`` and is logged by the source.
    """

    def supports(self, kind: MapImageKind) -> bool: ...

    async def fetch(self, ref: MapRef, kind: MapImageKind) -> MapImage | None: ...


class NoMapImages:
    """The source until servers and the renderer can draw: every map is shown without one."""

    def supports(self, kind: MapImageKind) -> bool:
        return False

    async def fetch(self, ref: MapRef, kind: MapImageKind) -> MapImage | None:
        return None


class MapImages:
    """The pictures the bot shows, from one source, with a short-lived cache.

    Entries are keyed by content hash when the reference has one, so a replaced file
    under the same name is drawn again; without one, by server and file name for at most
    :data:`CACHE_TTL_SECONDS`.
    """

    def __init__(self, source: MapImageSource | None = None) -> None:
        self._source: MapImageSource = source or NoMapImages()
        self._cache: OrderedDict[tuple[str, ...], tuple[float, MapImage]] = (
            OrderedDict()
        )

    def supports(self, kind: MapImageKind) -> bool:
        return self._source.supports(kind)

    async def get(self, ref: MapRef, kind: MapImageKind) -> MapImage | None:
        if not self._source.supports(kind):
            return None
        key = self._key(ref, kind)
        now = time.monotonic()
        cached = self._cache.get(key)
        if cached is not None and now - cached[0] < CACHE_TTL_SECONDS:
            self._cache.move_to_end(key)
            return cached[1]
        image = await self._source.fetch(ref, kind)
        if image is None:
            return None
        if len(image.png) > MAX_ATTACHMENT_BYTES:
            logger.warning(
                "%s image of %s on %s is %d bytes, above the attachment limit",
                kind.value,
                ref.file_name,
                ref.server,
                len(image.png),
            )
            return None
        self._cache[key] = (now, image)
        self._cache.move_to_end(key)
        while len(self._cache) > CACHE_ENTRIES:
            self._cache.popitem(last=False)
        return image

    def forget(self, server: str) -> None:
        """Drops the server's pictures cached by name, e.g. after an upload or removal."""
        for key in [k for k in self._cache if k[0] == "name" and k[1] == server]:
            del self._cache[key]

    @staticmethod
    def _key(ref: MapRef, kind: MapImageKind) -> tuple[str, ...]:
        if ref.content_hash:
            return ("hash", ref.content_hash, kind.value)
        return ("name", ref.server, ref.file_name, kind.value)


class ThumbnailRpc(Protocol):
    """What :class:`ServerThumbnails` needs from the RPC client."""

    async def preview_map(
        self, *, server: str, file_name: str, max_side: int | None, timeout_ms: int
    ) -> Any: ...

    async def read_blob(self, ref: Any) -> bytes: ...


class ServerThumbnails:
    """Thumbnails from the game server that has the map (``maps.preview``).

    The server draws one pixel per tile, scaled to :data:`THUMBNAIL_SIDE`, and leaves the PNG
    in Redis; this reads and checks it. A server that does not answer raises
    :class:`TimeoutError`; a map it does not have, a failed draw or a missing blob is ``None``.
    """

    THUMBNAIL_SIDE = 512

    def __init__(self, rpc: ThumbnailRpc, timeout_ms: int) -> None:
        self._rpc = rpc
        self._timeout_ms = timeout_ms

    def supports(self, kind: MapImageKind) -> bool:
        return kind is MapImageKind.THUMBNAIL

    async def fetch(self, ref: MapRef, kind: MapImageKind) -> MapImage | None:
        if kind is not MapImageKind.THUMBNAIL:
            return None
        try:
            response = await self._rpc.preview_map(
                server=ref.server,
                file_name=ref.file_name,
                max_side=self.THUMBNAIL_SIDE,
                timeout_ms=self._timeout_ms,
            )
        except TimeoutError:
            raise
        except Exception:
            logger.warning(
                "Thumbnail of %s on %s failed", ref.file_name, ref.server, exc_info=True
            )
            return None
        if response.status.value != "ok" or response.image is None:
            return None
        image = response.image
        try:
            png = await self._rpc.read_blob(image)
        except Exception:
            logger.warning("Thumbnail blob %s unavailable", image.key, exc_info=True)
            return None
        return MapImage(png=png, width=image.width or 0, height=image.height or 0)
