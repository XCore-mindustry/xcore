from __future__ import annotations

import asyncio
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
from xcore_discord_bot.bus.maps import RENDERER_PRESENCE_KEY, BlobUnavailable
from xcore_discord_bot.handlers_maps import cmd_map_details, cmd_maps
from xcore_discord_bot.services.map_images import (
    MapImageKind,
    MapImages,
    MapRef,
    RenderCooldown,
    RendererBusy,
    RendererImages,
    RendererUnavailable,
    RenderStyle,
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
    await bot.bus.redis.set(RENDERER_PRESENCE_KEY, '{"version": "1-test"}')
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
    assert image.render_ms == 420
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

    drawn = await view._render(7, RenderStyle())
    again = await view._render(
        7, RenderStyle()
    )  # already drawn: no wait, no second render
    bot.container.map_images.clear()
    waited = await view._render(7, RenderStyle())
    other = await view._render(
        8, RenderStyle()
    )  # anyone may press; the wait is per presser

    assert not isinstance(drawn, str) and not isinstance(again, str)
    assert not isinstance(other, str)
    assert len(renderer.sent(RENDER_REQUEST)) == 2
    assert isinstance(waited, str) and waited.startswith(
        "You can start a new render in"
    )


@pytest.mark.parametrize("render", [{"status": "busy"}, {"status": "failed"}])
async def test_render_that_draws_nothing_costs_no_wait(render: dict[str, Any]) -> None:
    bot, renderer = await _render_bot(render)
    card = _Interaction()
    await cmd_map_details(bot, card, "mini-pvp", "arena.msav")
    view = card.followup.messages[0]["view"]

    first = await view._render(7, RenderStyle())
    second = await view._render(7, RenderStyle())

    assert isinstance(first, str) and isinstance(second, str)
    assert not second.startswith("You can start a new render in")
    assert len(renderer.sent(RENDER_REQUEST)) == 2


async def test_timed_out_render_keeps_the_wait(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(RendererImages, "RENDER_TIMEOUT_MS", 50)
    bot, _ = await _render_bot(None)
    card = _Interaction()
    await cmd_map_details(bot, card, "mini-pvp", "arena.msav")
    view = card.followup.messages[0]["view"]

    await view._render(7, RenderStyle())
    waited = await view._render(7, RenderStyle())

    assert isinstance(waited, str) and waited.startswith(
        "You can start a new render in"
    )


async def test_cache_is_bounded_by_bytes() -> None:
    from xcore_discord_bot.services import map_images

    class _Source:
        def supports(self, kind: MapImageKind) -> bool:
            return True

        async def available(self, kind: MapImageKind) -> bool:
            return True

        async def fetch(self, ref: MapRef, kind: MapImageKind, style: Any) -> Any:
            return map_images.MapImage(png=b"x" * 4 * 1024 * 1024, width=1, height=1)

    images = map_images.MapImages(_Source())
    for index in range(20):
        await images.get(MapRef("s", f"{index}.msav"), MapImageKind.DETAILED)

    assert images._bytes <= map_images.CACHE_BYTES
    assert images.cached(MapRef("s", "19.msav"), MapImageKind.DETAILED) is not None
    assert images.cached(MapRef("s", "0.msav"), MapImageKind.DETAILED) is None


async def test_without_the_renderer_nothing_is_asked_and_no_button_is_offered() -> None:
    bot, renderer = await _render_bot({"status": "ok", "image": _image()})
    await bot.bus.redis.delete(RENDERER_PRESENCE_KEY)
    source = RendererImages(bot.container.rpc, timeout_ms=50)

    with pytest.raises(RendererUnavailable):
        await source.fetch(MapRef("mini-pvp", "arena.msav"), MapImageKind.DETAILED)
    card = _Interaction()
    await cmd_map_details(bot, card, "mini-pvp", "arena.msav")

    assert "view" not in card.followup.messages[0]
    assert renderer.sent(RENDER_REQUEST) == []


async def test_a_file_the_bot_holds_is_published_and_rendered() -> None:
    bot, renderer = await _render_bot(
        {"status": "ok", "image": _image(), "scale": 8, "renderMs": 420}
    )
    source = RendererImages(bot.container.rpc, timeout_ms=50)
    images = MapImages(source, files=source)

    first, second = await asyncio.gather(
        images.render_file(MapRef("review", "arena.msav"), FILE),
        images.render_file(MapRef("review", "arena.msav"), FILE),
    )

    assert first is not None and first.png == PNG and second == first
    # Both asked at once: one render.
    [request] = renderer.sent(RENDER_REQUEST)
    assert request["contentHash"] == FILE_SHA
    assert request["file"]["key"] == f"xcore:blob:{FILE_SHA}"
    assert await bot.bus.redis.get(f"xcore:blob:{FILE_SHA}") is not None
    assert images.cached(MapRef("x", "y", FILE_SHA), MapImageKind.DETAILED) == first


async def test_a_render_already_running_for_someone_else_costs_no_wait() -> None:
    bot, renderer = await _render_bot(
        {"status": "ok", "image": _image(), "scale": 8, "renderMs": 420}
    )
    card = _Interaction()
    await cmd_map_details(bot, card, "mini-pvp", "arena.msav")
    view = card.followup.messages[0]["view"]
    bot.container.render_cooldown.start(8)  # 8 just rendered another map

    first, second = await asyncio.gather(
        view._render(7, RenderStyle()), view._render(8, RenderStyle())
    )

    assert not isinstance(first, str) and not isinstance(second, str)
    assert len(renderer.sent(RENDER_REQUEST)) == 1


@dataclass
class _ButtonResponse:
    edits: list[dict[str, Any]] = field(default_factory=list)

    async def edit_message(self, **kwargs: Any) -> None:
        self.edits.append(kwargs)


@dataclass
class _Press:
    """A button press on the card: Discord's interaction for a component."""

    user: Any = field(default_factory=lambda: SimpleNamespace(id=9))
    response: _ButtonResponse = field(default_factory=_ButtonResponse)
    followup: _Followup = field(default_factory=_Followup)
    edits: list[dict[str, Any]] = field(default_factory=list)

    async def edit_original_response(self, **kwargs: Any) -> None:
        self.edits.append(kwargs)


async def test_the_button_renders_then_the_menu_switches_layers() -> None:
    bot, renderer = await _render_bot(
        {"status": "ok", "image": _image(), "scale": 8, "renderMs": 420}
    )
    card = _Interaction()
    await cmd_map_details(bot, card, "mini-pvp", "arena.msav")
    view = card.followup.messages[0]["view"]
    [button] = view.children
    assert button.label == "Detailed render"

    first = _Press()
    await button.callback(first)  # type: ignore[arg-type]
    assert first.edits[-1]["view"] is view
    assert first.edits[-1]["attachments"][0].filename == "arena-detailed.png"
    [menu] = view.children
    assert isinstance(menu, discord.ui.Select)
    assert [o.value for o in menu.options if o.default] == ["buildings"]
    assert len(menu.options) == 5 and menu.max_values == 5

    second = _Press(user=SimpleNamespace(id=10))
    menu._values = ["buildings", "darkness", "grid", "spawns"]
    await menu.callback(second)  # type: ignore[arg-type]
    assert second.edits[-1]["attachments"][0].filename == "arena-detailed-bdgs.png"
    [menu] = view.children
    assert {o.value for o in menu.options if o.default} == {
        "buildings",
        "darkness",
        "grid",
        "spawns",
    }

    third = _Press(user=SimpleNamespace(id=10))
    menu._values = ["buildings"]
    await menu.callback(third)  # back to the cached render: no wait, no request
    assert third.edits[-1]["attachments"][0].filename == "arena-detailed.png"
    requests = renderer.sent(RENDER_REQUEST)
    assert [
        {k: r[k] for k in ("buildings", "darkness", "ores", "grid", "spawns")}
        for r in requests
    ] == [
        {
            "buildings": True,
            "darkness": False,
            "ores": False,
            "grid": False,
            "spawns": False,
        },
        {
            "buildings": True,
            "darkness": True,
            "ores": False,
            "grid": True,
            "spawns": True,
        },
    ]


def test_render_style_names() -> None:
    assert RenderStyle().key() == ""
    assert RenderStyle.of(["ores"]).key() == "o"
    assert RenderStyle.of([]) == RenderStyle(buildings=False)
