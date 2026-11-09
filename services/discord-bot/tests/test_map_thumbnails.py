from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from types import SimpleNamespace
from typing import Any

import discord
import pytest
from backends import GameServer, real_bot
from bot_fakes import make_settings
from xcore_protocol.generated.maps import (
    MapsFileRequestV1,
    MapsFileResponseV1,
    MapsListRequestV1,
    MapsListResponseV1,
    MapsPreviewRequestV1,
    MapsPreviewResponseV1,
    MapsRenderRequestV1,
    MapsRenderResponseV1,
)
from xcore_protocol.generated.shared import MapBlobRefV1

from xcore_discord_bot.bot import XCoreDiscordBot
from xcore_discord_bot.bus.maps import BlobUnavailable
from xcore_discord_bot.handlers_maps import cmd_map_details, cmd_maps
from xcore_discord_bot.services.map_images import (
    MapImageKind,
    MapRef,
    RenderCooldown,
    RendererBusy,
    RendererImages,
    ServerThumbnails,
)

PNG = b"\x89PNG\r\n\x1a\n" + bytes(range(200))
SHA = hashlib.sha256(PNG).hexdigest()
FILE_HASH = "cd" * 32
LIST = MapsListRequestV1.MESSAGE_TYPE
PREVIEW = MapsPreviewRequestV1.MESSAGE_TYPE


def _image(**changes: Any) -> dict[str, Any]:
    ref = {
        "key": f"xcore:blob:{SHA}",
        "sha256": SHA,
        "sizeBytes": len(PNG),
        "contentType": "image/png",
        "width": 510,
        "height": 510,
        "expiresAt": 1_900_000_000_000,
    }
    ref.update(changes)
    return ref


@dataclass
class _Response:
    deferred: list[dict[str, Any]] = field(default_factory=list)

    async def defer(self, **kwargs: Any) -> None:
        self.deferred.append(kwargs)


@dataclass
class _Followup:
    messages: list[dict[str, Any]] = field(default_factory=list)

    async def send(self, content: str | None = None, **kwargs: Any) -> dict[str, Any]:
        message = {"content": content, **kwargs}
        self.messages.append(message)
        return message


@dataclass
class _Interaction:
    response: _Response = field(default_factory=_Response)
    followup: _Followup = field(default_factory=_Followup)
    user: Any = field(default_factory=lambda: SimpleNamespace(id=7))
    locale: discord.Locale | None = None


async def _bot(preview: dict[str, Any] | None) -> tuple[XCoreDiscordBot, GameServer]:
    """``mini-pvp`` lists one map and answers previews with ``preview`` (silence if None)."""
    bot, _ = await real_bot(settings=make_settings(rpc_timeout_ms=50))
    game = GameServer(bot)
    game.answers[LIST] = lambda payload: {
        "messageType": MapsListResponseV1.MESSAGE_TYPE,
        "messageVersion": 1,
        "server": payload["server"],
        "maps": [
            {
                "name": "Arena",
                "fileName": "arena.msav",
                "author": "Alice",
                "contentHash": FILE_HASH,
            }
        ],
    }
    game.answers[PREVIEW] = lambda payload: (
        None
        if preview is None
        else {
            "messageType": MapsPreviewResponseV1.MESSAGE_TYPE,
            "messageVersion": 1,
            "server": payload["server"],
            "fileName": payload["fileName"],
            **preview,
        }
    )
    return bot, game


async def test_read_blob_checks_size_and_hash() -> None:
    bot, _ = await _bot(None)
    maps = bot.container.maps_bus
    await bot.bus.redis.set(f"xcore:blob:{SHA}", PNG)

    assert await maps.read_blob(MapBlobRefV1.from_payload(_image())) == PNG
    with pytest.raises(BlobUnavailable):
        await maps.read_blob(MapBlobRefV1.from_payload(_image(sizeBytes=len(PNG) + 1)))
    with pytest.raises(BlobUnavailable):
        await maps.read_blob(MapBlobRefV1.from_payload(_image(sha256="00" * 32)))
    with pytest.raises(BlobUnavailable):
        await maps.read_blob(
            MapBlobRefV1.from_payload(_image(key="xcore:blob:" + "ee" * 32))
        )


async def test_server_thumbnail_is_read_from_its_blob() -> None:
    bot, game = await _bot(
        {"status": "ok", "contentHash": FILE_HASH, "image": _image()}
    )
    await bot.bus.redis.set(f"xcore:blob:{SHA}", PNG)
    source = ServerThumbnails(bot.container.rpc, timeout_ms=50)

    image = await source.fetch(MapRef("mini-pvp", "arena.msav"), MapImageKind.THUMBNAIL)

    assert image is not None and image.png == PNG and image.width == 510
    [request] = game.sent(PREVIEW)
    assert request["maxSide"] == ServerThumbnails.THUMBNAIL_SIDE
    assert not source.supports(MapImageKind.DETAILED)


@pytest.mark.parametrize(
    "answer",
    [
        {"status": "not_found"},
        {"status": "failed"},
        # The server published it, but the blob expired before the bot read it.
        {"status": "ok", "contentHash": FILE_HASH, "image": _image()},
    ],
)
async def test_server_thumbnail_without_a_picture_is_none(
    answer: dict[str, Any],
) -> None:
    bot, _ = await _bot(answer)
    source = ServerThumbnails(bot.container.rpc, timeout_ms=50)

    assert (
        await source.fetch(MapRef("mini-pvp", "arena.msav"), MapImageKind.THUMBNAIL)
        is None
    )


async def test_silent_server_times_out() -> None:
    bot, _ = await _bot(None)
    source = ServerThumbnails(bot.container.rpc, timeout_ms=50)

    with pytest.raises(TimeoutError):
        await source.fetch(MapRef("mini-pvp", "arena.msav"), MapImageKind.THUMBNAIL)


async def test_map_card_shows_the_server_thumbnail() -> None:
    bot, _ = await _bot({"status": "ok", "contentHash": FILE_HASH, "image": _image()})
    await bot.bus.redis.set(f"xcore:blob:{SHA}", PNG)
    interaction = _Interaction()

    await cmd_map_details(bot, interaction, "mini-pvp", "arena.msav")

    [message] = interaction.followup.messages
    assert message["file"].fp.read() == PNG
    assert message["embed"].image.url == f"attachment://{message['file'].filename}"
    assert message["ephemeral"] is False


async def test_list_menu_opens_the_card_for_the_person_only() -> None:
    bot, _ = await _bot({"status": "ok", "contentHash": FILE_HASH, "image": _image()})
    await bot.bus.redis.set(f"xcore:blob:{SHA}", PNG)
    listing = _Interaction()
    await cmd_maps(bot, listing, "mini-pvp")
    view = listing.followup.messages[0]["view"]
    [menu] = [i for i in view.children if isinstance(i, discord.ui.Select)]

    opener = _Interaction(user=SimpleNamespace(id=8))
    menu._values = ["0"]  # what Discord sets before calling back
    await menu.callback(opener)  # type: ignore[arg-type]

    assert opener.response.deferred == [{"ephemeral": True, "thinking": True}]
    [card] = opener.followup.messages
    assert card["ephemeral"] is True
    assert card["embed"].title == "Arena"
    assert card["file"].fp.read() == PNG


FILE = b"MSAV-bytes" * 100
FILE_SHA = hashlib.sha256(FILE).hexdigest()
FILE_REQUEST = MapsFileRequestV1.MESSAGE_TYPE
RENDER_REQUEST = MapsRenderRequestV1.MESSAGE_TYPE


def _file_ref() -> dict[str, Any]:
    return {
        "key": f"xcore:blob:{FILE_SHA}",
        "sha256": FILE_SHA,
        "sizeBytes": len(FILE),
        "contentType": "application/octet-stream",
        "expiresAt": 1_900_000_000_000,
    }


async def _render_bot(
    render: dict[str, Any] | None,
) -> tuple[XCoreDiscordBot, GameServer]:
    """``mini-pvp`` publishes the map file; the renderer answers with ``render``."""
    bot, game = await _bot(
        {"status": "ok", "contentHash": FILE_HASH, "image": _image()}
    )
    await bot.bus.redis.set(f"xcore:blob:{SHA}", PNG)
    game.answers[FILE_REQUEST] = lambda payload: {
        "messageType": MapsFileResponseV1.MESSAGE_TYPE,
        "messageVersion": 1,
        "server": payload["server"],
        "fileName": payload["fileName"],
        "status": "ok",
        "contentHash": FILE_SHA,
        "file": _file_ref(),
    }
    renderer = GameServer(bot, name="map-renderer")
    renderer.answers[RENDER_REQUEST] = lambda payload: (
        None
        if render is None
        else {
            "messageType": MapsRenderResponseV1.MESSAGE_TYPE,
            "messageVersion": 1,
            "contentHash": payload["contentHash"],
            **render,
        }
    )
    return bot, renderer


async def test_detailed_render_goes_through_the_map_file_and_the_renderer() -> None:
    bot, renderer = await _render_bot(
        {"status": "ok", "image": _image(), "scale": 8, "renderMs": 420}
    )
    source = RendererImages(bot.container.rpc, timeout_ms=50)

    image = await source.fetch(MapRef("mini-pvp", "arena.msav"), MapImageKind.DETAILED)

    assert image is not None and image.png == PNG
    [request] = renderer.sent(RENDER_REQUEST)
    assert request["contentHash"] == FILE_SHA
    assert request["file"]["key"] == f"xcore:blob:{FILE_SHA}"
    assert not source.supports(MapImageKind.THUMBNAIL)


async def test_busy_renderer_is_reported() -> None:
    bot, _ = await _render_bot({"status": "busy", "reason": "two renders running"})
    source = RendererImages(bot.container.rpc, timeout_ms=50)

    with pytest.raises(RendererBusy):
        await source.fetch(MapRef("mini-pvp", "arena.msav"), MapImageKind.DETAILED)


@pytest.mark.parametrize(
    "status", ["invalid_map", "too_large", "download_failed", "failed"]
)
async def test_failed_render_has_no_picture(status: str) -> None:
    bot, _ = await _render_bot({"status": status})
    source = RendererImages(bot.container.rpc, timeout_ms=50)

    assert (
        await source.fetch(MapRef("mini-pvp", "arena.msav"), MapImageKind.DETAILED)
        is None
    )


def test_cooldown_is_thirty_seconds_per_person() -> None:
    now = [100.0]
    cooldown = RenderCooldown(clock=lambda: now[0])

    assert cooldown.remaining(1) == 0
    cooldown.start(1)
    now[0] += 10
    assert cooldown.remaining(1) == 20
    assert cooldown.remaining(2) == 0
    now[0] += 20
    assert cooldown.remaining(1) == 0


async def test_render_button_waits_between_new_renders_but_not_for_cached_ones() -> (
    None
):
    bot, renderer = await _render_bot(
        {"status": "ok", "image": _image(), "scale": 8, "renderMs": 420}
    )
    first = _Interaction()
    await cmd_map_details(bot, first, "mini-pvp", "arena.msav")
    view = first.followup.messages[0]["view"]

    drawn = await view._render()
    again = await view._render()  # already drawn: no wait, no second render
    bot.container.map_images.forget("mini-pvp")
    bot.container.map_images._cache.clear()
    waited = await view._render()

    assert not isinstance(drawn, str) and not isinstance(again, str)
    assert len(renderer.sent(RENDER_REQUEST)) == 1
    assert isinstance(waited, str) and waited.startswith(
        "You can start a new render in"
    )
