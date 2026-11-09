"""Pictures of maps: the thumbnail in /maps and the detailed render in /map.

The bot never decodes a map itself. A :class:`MapImageSource` produces the PNG, and
:class:`MapImages` keeps recently shown pictures, so paging back and forth or two
people opening the same map do not ask for it twice. See ``docs/map-images.md`` in this
service for where each kind of picture is meant to come from.
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import time
from collections import OrderedDict
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from enum import StrEnum
from typing import Any, Protocol

from xcore_protocol.generated.shared import MapBlobRefV1ContentType

logger = logging.getLogger(__name__)

# Discord rejects attachments above this size on servers without boosts.
MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024
CACHE_ENTRIES = 64
# Detailed renders run to megabytes, so the cache is bounded by size as well as by count.
CACHE_BYTES = 64 * 1024 * 1024
CACHE_TTL_SECONDS = 15 * 60


class MapImageKind(StrEnum):
    # One pixel per tile, coloured by block: cheap, the game server draws it.
    THUMBNAIL = "thumbnail"
    # Block sprites, like the game itself: expensive, drawn away from the game servers.
    # Without the game's darkness over static walls and outside the map, by default.
    DETAILED = "detailed"
    # The same with the darkness, as the game shows it.
    DETAILED_DARK = "detailed-dark"

    @property
    def detailed(self) -> bool:
        return self is not MapImageKind.THUMBNAIL


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

    async def available(self, kind: MapImageKind) -> bool:
        """Whether asking now is worth it: the source supports the kind and is running."""
        ...

    async def fetch(self, ref: MapRef, kind: MapImageKind) -> MapImage | None: ...


class FileRenderer(Protocol):
    """Draws a map file the bot holds itself, such as one submitted for review."""

    async def render_file(self, data: bytes, sha256: str) -> MapImage | None: ...


class NoMapImages:
    """The source until servers and the renderer can draw: every map is shown without one."""

    def supports(self, kind: MapImageKind) -> bool:
        return False

    async def available(self, kind: MapImageKind) -> bool:
        return False

    async def fetch(self, ref: MapRef, kind: MapImageKind) -> MapImage | None:
        return None


class MapImages:
    """The pictures the bot shows, from one source, with a short-lived cache.

    Entries are keyed by content hash when the reference has one, so a replaced file
    under the same name is drawn again; without one, by server and file name for at most
    :data:`CACHE_TTL_SECONDS`. Two people asking for the same picture at once share one
    request to the source.
    """

    def __init__(
        self,
        source: MapImageSource | None = None,
        *,
        files: FileRenderer | None = None,
    ) -> None:
        self._source: MapImageSource = source or NoMapImages()
        self._files = files
        self._cache: OrderedDict[tuple[str, ...], tuple[float, MapImage]] = (
            OrderedDict()
        )
        self._bytes = 0
        self._inflight: dict[tuple[str, ...], asyncio.Task[MapImage | None]] = {}

    def supports(self, kind: MapImageKind) -> bool:
        return self._source.supports(kind)

    async def available(self, kind: MapImageKind) -> bool:
        return self._source.supports(kind) and await self._source.available(kind)

    async def get(self, ref: MapRef, kind: MapImageKind) -> MapImage | None:
        if not self._source.supports(kind):
            return None
        return await self._load(
            self._key(ref, kind), ref, kind, lambda: self._source.fetch(ref, kind)
        )

    async def render_file(self, ref: MapRef, data: bytes) -> MapImage | None:
        """The detailed render of a file the bot holds, cached under its content hash.

        ``ref`` names the file for logs and attachments; its hash is the file's own.
        """
        if self._files is None:
            return None
        sha = hashlib.sha256(data).hexdigest()
        ref = MapRef(ref.server, ref.file_name, sha)
        files = self._files
        return await self._load(
            self._key(ref, MapImageKind.DETAILED),
            ref,
            MapImageKind.DETAILED,
            lambda: files.render_file(data, sha),
        )

    async def _load(
        self,
        key: tuple[str, ...],
        ref: MapRef,
        kind: MapImageKind,
        produce: Callable[[], Awaitable[MapImage | None]],
    ) -> MapImage | None:
        cached = self.cached_key(key)
        if cached is not None:
            self._cache.move_to_end(key)
            return cached
        task = self._inflight.get(key)
        if task is None:
            task = asyncio.ensure_future(self._produce(key, ref, kind, produce))
            self._inflight[key] = task
            task.add_done_callback(lambda done: self._settled(key, done))
        # One waiter giving up (its interaction expired) must not cancel the others.
        return await asyncio.shield(task)

    async def _produce(
        self,
        key: tuple[str, ...],
        ref: MapRef,
        kind: MapImageKind,
        produce: Callable[[], Awaitable[MapImage | None]],
    ) -> MapImage | None:
        image = await produce()
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
        self._drop(key)
        self._cache[key] = (time.monotonic(), image)
        self._bytes += len(image.png)
        while len(self._cache) > CACHE_ENTRIES or self._bytes > CACHE_BYTES:
            self._drop(next(iter(self._cache)))
        return image

    def _settled(
        self, key: tuple[str, ...], task: asyncio.Task[MapImage | None]
    ) -> None:
        self._inflight.pop(key, None)
        # Retrieved here, so a failure every waiter gave up on is not reported as unhandled.
        if not task.cancelled():
            task.exception()

    def drawing(self, ref: MapRef, kind: MapImageKind) -> bool:
        """Whether this picture is being fetched right now for someone else."""
        return self._key(ref, kind) in self._inflight

    def _drop(self, key: tuple[str, ...]) -> None:
        entry = self._cache.pop(key, None)
        if entry is not None:
            self._bytes -= len(entry[1].png)

    def cached(self, ref: MapRef, kind: MapImageKind) -> MapImage | None:
        """The picture if it is in the cache and still fresh; never asks the source."""
        return self.cached_key(self._key(ref, kind))

    def cached_key(self, key: tuple[str, ...]) -> MapImage | None:
        cached = self._cache.get(key)
        if cached is None:
            return None
        if time.monotonic() - cached[0] >= CACHE_TTL_SECONDS:
            self._drop(key)
            return None
        return cached[1]

    def clear(self) -> None:
        self._cache.clear()
        self._bytes = 0

    def forget(self, server: str) -> None:
        """Drops the server's pictures cached by name, e.g. after an upload or removal."""
        for key in [k for k in self._cache if k[0] == "name" and k[1] == server]:
            self._drop(key)

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

    async def available(self, kind: MapImageKind) -> bool:
        return self.supports(kind)

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


class RendererBusy(RuntimeError):
    """The map renderer is drawing as many maps as it takes; try again shortly."""


class RendererUnavailable(RuntimeError):
    """The map renderer is not running (it has not announced itself lately)."""


class RenderRpc(Protocol):
    """What :class:`RendererImages` needs from the RPC client."""

    async def renderer_online(self) -> bool: ...

    async def map_file(
        self, *, server: str, file_name: str, timeout_ms: int
    ) -> Any: ...

    async def publish_blob(self, data: bytes, content_type: Any) -> Any: ...

    async def render_map(
        self, *, content_hash: str, file: Any, darkness: bool, timeout_ms: int
    ) -> Any: ...

    async def read_blob(self, ref: Any) -> bytes: ...


class RendererImages:
    """Detailed renders from the map renderer service.

    For an installed map the game server publishes the file (``maps.file``); a file the bot
    holds, it publishes itself. The renderer draws it (``maps.render``) and leaves the PNG in
    Redis. A renderer that has not announced itself raises :class:`RendererUnavailable`
    before anything is asked; either side not answering raises :class:`TimeoutError`; a
    busy renderer raises :class:`RendererBusy`; anything else that leaves no picture is
    ``None``.
    """

    RENDER_TIMEOUT_MS = 60_000

    def __init__(self, rpc: RenderRpc, timeout_ms: int) -> None:
        self._rpc = rpc
        self._timeout_ms = timeout_ms

    def supports(self, kind: MapImageKind) -> bool:
        return kind.detailed

    async def available(self, kind: MapImageKind) -> bool:
        if not self.supports(kind):
            return False
        try:
            return await self._rpc.renderer_online()
        except Exception:
            logger.warning("Could not check the map renderer", exc_info=True)
            return False

    async def fetch(self, ref: MapRef, kind: MapImageKind) -> MapImage | None:
        if not kind.detailed:
            return None
        await self._require_renderer()
        published = await self._rpc.map_file(
            server=ref.server, file_name=ref.file_name, timeout_ms=self._timeout_ms
        )
        if published.status.value != "ok" or published.file is None:
            logger.info(
                "No file of %s on %s: %s", ref.file_name, ref.server, published.status
            )
            return None
        return await self._render(
            published.contentHash or published.file.sha256,
            published.file,
            ref.file_name,
            darkness=kind is MapImageKind.DETAILED_DARK,
        )

    async def render_file(self, data: bytes, sha256: str) -> MapImage | None:
        await self._require_renderer()
        try:
            file = await self._rpc.publish_blob(
                data, MapBlobRefV1ContentType.APPLICATION_OCTET_STREAM
            )
        except Exception:
            logger.warning("Could not publish map file %s", sha256[:12], exc_info=True)
            return None
        return await self._render(sha256, file, sha256[:12], darkness=False)

    async def _require_renderer(self) -> None:
        if not await self.available(MapImageKind.DETAILED):
            raise RendererUnavailable("the map renderer is not running")

    async def _render(
        self, content_hash: str, file: Any, name: str, *, darkness: bool
    ) -> MapImage | None:
        render = await self._rpc.render_map(
            content_hash=content_hash,
            file=file,
            darkness=darkness,
            timeout_ms=self.RENDER_TIMEOUT_MS,
        )
        if render.status.value == "busy":
            raise RendererBusy(render.reason or "busy")
        if render.status.value != "ok" or render.image is None:
            logger.info("Render of %s: %s %s", name, render.status, render.reason or "")
            return None
        try:
            png = await self._rpc.read_blob(render.image)
        except Exception:
            logger.warning(
                "Render blob %s unavailable", render.image.key, exc_info=True
            )
            return None
        return MapImage(
            png=png, width=render.image.width or 0, height=render.image.height or 0
        )


class RoutedImages:
    """One source per kind of picture."""

    def __init__(self, *sources: MapImageSource) -> None:
        self._sources = sources

    def _for(self, kind: MapImageKind) -> MapImageSource | None:
        return next((s for s in self._sources if s.supports(kind)), None)

    def supports(self, kind: MapImageKind) -> bool:
        return self._for(kind) is not None

    async def available(self, kind: MapImageKind) -> bool:
        source = self._for(kind)
        return source is not None and await source.available(kind)

    async def fetch(self, ref: MapRef, kind: MapImageKind) -> MapImage | None:
        source = self._for(kind)
        return None if source is None else await source.fetch(ref, kind)


class RenderCooldown:
    """At most one new detailed render per person every :data:`SECONDS`."""

    SECONDS = 30.0

    def __init__(self, clock: Any = time.monotonic) -> None:
        self._clock = clock
        self._started: dict[int, float] = {}

    def remaining(self, user_id: int) -> float:
        started = self._started.get(user_id)
        if started is None:
            return 0.0
        return max(0.0, self.SECONDS - (self._clock() - started))

    def cancel(self, user_id: int) -> None:
        """Gives back the wait of a render that drew nothing."""
        self._started.pop(user_id, None)

    def start(self, user_id: int) -> None:
        now = self._clock()
        self._started[user_id] = now
        # Forget people whose wait is long over, so the map stays small.
        if len(self._started) > 1024:
            self._started = {
                u: t for u, t in self._started.items() if now - t < self.SECONDS
            }
