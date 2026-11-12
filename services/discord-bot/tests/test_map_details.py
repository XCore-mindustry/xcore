from __future__ import annotations

from dataclasses import dataclass, field
from types import SimpleNamespace
from typing import Any

import discord
import pytest
from backends import GameServer, real_bot
from bot_fakes import make_settings
from xcore_protocol.generated.maps import MapsListRequestV1, MapsListResponseV1

from xcore_discord_bot.bot import XCoreDiscordBot
from xcore_discord_bot.cogs.maps import MapsCog
from xcore_discord_bot.handlers_maps import cmd_map_details
from xcore_discord_bot.map_card import find_map, map_card
from xcore_discord_bot.server_views import MapDetailsView
from xcore_discord_bot.services.map_images import (
    MapImage,
    MapImageKind,
    MapImages,
    MapRef,
)

LIST = MapsListRequestV1.MESSAGE_TYPE
ARENA = {
    "name": "[accent]Arena",
    "fileName": "Arena.msav",
    "author": "Alice",
    "width": 120,
    "height": 80,
    "fileSizeBytes": 2048,
    "like": 5,
    "dislike": 2,
    "reputation": 3,
    "popularity": 7.5,
    "interest": 1.25,
    "gameMode": "pvp",
}


@dataclass
class _Response:
    deferred: bool = False

    async def defer(self) -> None:
        self.deferred = True


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
    user: Any = field(default_factory=lambda: SimpleNamespace(id=1))
    locale: discord.Locale | None = None


class _Source:
    """Draws every kind it is given, counting the requests."""

    def __init__(self, *kinds: MapImageKind) -> None:
        self.kinds = set(kinds)
        self.calls: list[tuple[MapRef, MapImageKind]] = []

    def supports(self, kind: MapImageKind) -> bool:
        return kind in self.kinds

    async def available(self, kind: MapImageKind) -> bool:
        return self.supports(kind)

    async def fetch(
        self, ref: MapRef, kind: MapImageKind, style: object = None
    ) -> MapImage | None:
        self.calls.append((ref, kind))
        return MapImage(png=b"\x89PNG" + kind.value.encode(), width=4, height=4)


async def _bot(maps: list[dict[str, Any]]) -> XCoreDiscordBot:
    bot, _ = await real_bot(settings=make_settings(rpc_timeout_ms=50))
    game = GameServer(bot)
    game.answers[LIST] = lambda payload: {
        "messageType": MapsListResponseV1.MESSAGE_TYPE,
        "messageVersion": 1,
        "server": payload["server"],
        "maps": maps,
    }
    return bot


def test_commands_are_maps_group_and_map() -> None:
    cog = MapsCog.__new__(MapsCog)
    names = {command.qualified_name for command in cog.walk_app_commands()}
    assert names >= {
        "map",
        "maps",
        "maps list",
        "maps upload",
        "maps remove",
        "maps review",
    }
    assert [p.display_name for p in MapsCog.cmd_map.parameters] == ["server", "map"]


def test_find_map_prefers_exact_file_name() -> None:
    maps = [{"file_name": "arena.msav"}, {"file_name": "Arena.msav"}]
    assert find_map(maps, "Arena.msav") is maps[1]
    assert find_map(maps, "ARENA.MSAV") is maps[0]
    assert find_map(maps, "other.msav") is None


def test_map_card_shows_details_without_colors() -> None:
    item = {
        "name": "[accent]Arena",
        "file_name": "Arena.msav",
        "author": "Alice",
        "width": "120",
        "height": "80",
        "file_size_bytes": "2048",
        "like": "5",
        "dislike": "2",
        "reputation": "3",
        "game_mode": "pvp",
    }
    embed = map_card("mini-pvp", item, image_name="arena-thumbnail.png")
    assert embed.title == "Arena"
    fields = {f.name: f.value for f in embed.fields}
    assert fields["Author"] == "`Alice`"
    assert fields["Size"] == "120×80"
    assert fields["Mode"] == "pvp"
    assert fields["Rating"] == "👍 5 • 👎 2 • rep 3"
    assert fields["File"] == "`Arena.msav` • 2.0 KB"
    assert embed.image.url == "attachment://arena-thumbnail.png"
    assert embed.footer.text == "Server: mini-pvp"

    rendered = map_card(
        "mini-pvp", item, image_name="arena-detailed.png", render_ms=420
    )
    assert rendered.footer.text == "Server: mini-pvp • Rendered in 0.42 s"


async def test_map_details_without_images_sends_card_only() -> None:
    bot = await _bot([ARENA])
    bot.container.map_images = MapImages()  # no source draws anything
    interaction = _Interaction()

    await cmd_map_details(bot, interaction, "mini-pvp", "Arena.msav")

    assert interaction.response.deferred
    [message] = interaction.followup.messages
    assert message["embed"].title == "Arena"
    assert "file" not in message and "view" not in message


async def test_map_details_reports_unknown_map() -> None:
    bot = await _bot([ARENA])
    interaction = _Interaction()

    await cmd_map_details(bot, interaction, "mini-pvp", "nope.msav")

    [message] = interaction.followup.messages
    assert message["content"] == "There is no map `nope.msav` on `mini-pvp`."


async def test_map_details_attaches_thumbnail_and_offers_render() -> None:
    bot = await _bot([ARENA])
    source = _Source(MapImageKind.THUMBNAIL, MapImageKind.DETAILED)
    bot.container.map_images = MapImages(source)
    interaction = _Interaction()

    await cmd_map_details(bot, interaction, "mini-pvp", "arena.msav")

    [message] = interaction.followup.messages
    assert message["file"].filename == "Arena-thumbnail.png"
    assert message["embed"].image.url == "attachment://Arena-thumbnail.png"
    assert isinstance(message["view"], MapDetailsView)
    assert source.calls == [(MapRef("mini-pvp", "Arena.msav"), MapImageKind.THUMBNAIL)]


async def test_map_images_cache_by_name_until_forgotten() -> None:
    source = _Source(MapImageKind.THUMBNAIL)
    images = MapImages(source)
    ref = MapRef("mini-pvp", "a.msav")

    first = await images.get(ref, MapImageKind.THUMBNAIL)
    assert await images.get(ref, MapImageKind.THUMBNAIL) is first
    assert await images.get(ref, MapImageKind.DETAILED) is None
    assert len(source.calls) == 1

    images.forget("mini-pvp")
    await images.get(ref, MapImageKind.THUMBNAIL)
    assert len(source.calls) == 2


async def test_map_images_cache_by_hash_survives_forget() -> None:
    source = _Source(MapImageKind.THUMBNAIL)
    images = MapImages(source)
    ref = MapRef("mini-pvp", "a.msav", content_hash="abc")

    await images.get(ref, MapImageKind.THUMBNAIL)
    images.forget("mini-pvp")
    # The same bytes on another server are the same picture.
    await images.get(MapRef("hexed", "b.msav", "abc"), MapImageKind.THUMBNAIL)
    assert len(source.calls) == 1


async def test_map_card_links_the_review_post_of_its_file(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    bot = await _bot([{**ARENA, "contentHash": "ab" * 32}])
    bot.settings = bot.settings.model_copy(update={"cases_maps_enabled": True})
    bot.container.map_images = MapImages()
    asked: list[str] = []

    async def by_map_file(sha256: str) -> Any:
        asked.append(sha256)
        return SimpleNamespace(discord={"thread_id": "123456"}, payload={})

    monkeypatch.setattr(bot.container.cases.store, "by_map_file", by_map_file)
    interaction = _Interaction()

    await cmd_map_details(bot, interaction, "mini-pvp", "Arena.msav")

    [message] = interaction.followup.messages
    fields = {f.name: f.value for f in message["embed"].fields}
    assert fields["Review"] == "<#123456>"
    assert asked == ["ab" * 32]


async def test_map_card_names_who_fixed_someone_elses_map(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    bot = await _bot([{**ARENA, "contentHash": "ab" * 32}])
    bot.settings = bot.settings.model_copy(update={"cases_maps_enabled": True})
    bot.container.map_images = MapImages()

    async def by_map_file(sha256: str) -> Any:
        return SimpleNamespace(
            discord={"thread_id": "123456"},
            payload={"fixer": {"discord_id": "777", "name": "RiTr"}},
        )

    monkeypatch.setattr(bot.container.cases.store, "by_map_file", by_map_file)
    interaction = _Interaction()
    await cmd_map_details(bot, interaction, "mini-pvp", "Arena.msav")
    [message] = interaction.followup.messages
    fields = {f.name: f.value for f in message["embed"].fields}
    assert fields["Fixed by"] == "<@777>"


def test_map_card_has_no_review_field_without_a_post() -> None:
    names = [f.name for f in map_card("mini-pvp", {"name": "Arena"}).fields]
    assert "Review" not in names
