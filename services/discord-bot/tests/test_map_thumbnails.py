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
    MapsListRequestV1,
    MapsListResponseV1,
    MapsPreviewRequestV1,
    MapsPreviewResponseV1,
)
from xcore_protocol.generated.shared import MapBlobRefV1

from xcore_discord_bot.bot import XCoreDiscordBot
from xcore_discord_bot.bus.maps import BlobUnavailable
from xcore_discord_bot.handlers_maps import cmd_map_details, cmd_maps
from xcore_discord_bot.services.map_images import (
    MapImageKind,
    MapRef,
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
