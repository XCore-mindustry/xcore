from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from types import SimpleNamespace
from typing import Any
from unittest.mock import AsyncMock, create_autospec

import discord
from backends import maps_remembered, published, real_bot, remember_maps
from bot_fakes import make_settings
from test_cases_msav import msav
from xcore_protocol.generated.maps import MapsUploadResponseV1

from xcore_discord_bot.map_admin import cmd_upload_map

CHAT = "xcore:cmd:discord-message:mini-pvp"


@dataclass
class _Role:
    id: int


@dataclass
class _Member:
    roles: list[_Role]
    display_name: str = "tester"
    id: int = 123456789012345678


@dataclass
class _Attachment:
    filename: str
    url: str
    data: bytes = b""

    @property
    def size(self) -> int:
        return len(self.data)

    async def read(self) -> bytes:
        return self.data


@dataclass
class _InteractionResponse:
    replies: list[str] = field(default_factory=list)
    ephemeral_replies: list[str] = field(default_factory=list)

    async def send_message(
        self, text: str, *, ephemeral: bool = False, **kwargs
    ) -> None:
        if ephemeral:
            self.ephemeral_replies.append(text)
        else:
            self.replies.append(text)


@dataclass
class _Interaction:
    id: int
    user: Any
    response: _InteractionResponse = field(default_factory=_InteractionResponse)
    views: list[Any] = field(default_factory=list)

    async def edit_original_response(self, *, content: str, **kwargs) -> None:
        self.response.replies.append(content)
        if kwargs.get("view") is not None:
            self.views.append(kwargs["view"])

    async def original_response(self) -> Any:
        return None

    @property
    def replies(self) -> list[str]:
        return self.response.replies

    @property
    def ephemeral_replies(self) -> list[str]:
        return self.response.ephemeral_replies


def _installed(*, server: str, files: list[dict[str, object]], timeout_ms: int):
    return MapsUploadResponseV1.from_payload(
        {
            "messageType": "maps.upload.response",
            "messageVersion": 1,
            "server": server,
            "results": [
                {"fileName": item["filename"], "status": "installed"} for item in files
            ],
        }
    )


async def _bot(**answer: Any):
    """A real bot whose game server answers the upload RPC with ``answer``."""
    bot, _ = await real_bot(settings=make_settings(discord_map_reviewer_role_id=200))
    maps_bus = bot.container.maps_bus
    maps_bus.rpc_upload_maps = create_autospec(
        maps_bus.rpc_upload_maps, **(answer or {"side_effect": _installed})
    )
    # The server has no maps yet unless a test lists some.
    maps_bus.rpc_maps_list = create_autospec(maps_bus.rpc_maps_list, return_value=[])
    return bot


def _uploads(bot) -> list[tuple[str, list[dict[str, Any]]]]:
    return [
        (call.kwargs["server"], call.kwargs["files"])
        for call in bot.container.maps_bus.rpc_upload_maps.await_args_list
    ]


async def test_upload_map_requires_valid_msav_files() -> None:
    bot = await _bot()

    interaction = _Interaction(
        id=10,
        user=_Member(roles=[_Role(100)]),
    )

    attachments = [
        _Attachment(filename="one.txt", url="https://example/one.txt"),
        None,
        None,
    ]
    await cmd_upload_map(bot, interaction, "mini-pvp", attachments)

    assert interaction.ephemeral_replies
    assert "No valid .msav files attached." in interaction.ephemeral_replies[0]
    assert _uploads(bot) == []


async def test_upload_map_publishes_msav_files() -> None:
    bot = await _bot()

    interaction = _Interaction(
        id=20,
        user=_Member(roles=[_Role(200)]),
    )

    attachments = [
        _Attachment(filename="one.msav", url="https://example/one.msav"),
        _Attachment(filename="ignored.txt", url="https://example/ignored.txt"),
        _Attachment(filename="two.MSAV", url="https://example/two.MSAV"),
    ]
    await cmd_upload_map(bot, interaction, "mini-pvp", attachments)

    assert len(_uploads(bot)) == 1
    server, files = _uploads(bot)[0]
    assert server == "mini-pvp"
    assert [item["filename"] for item in files] == ["one.msav", "two.MSAV"]
    assert any("Checking 2 map" in reply for reply in interaction.replies)
    assert "✅ Installed; attribution saved" in interaction.replies[-1]

    for item in files:
        assert item["discordUpload"]["uploadedBy"] == {
            "discordId": "123456789012345678",
            "discordUsername": "tester",
        }
        assert item["discordUpload"]["uploadedAt"] > 0
        assert "creator" not in item["discordUpload"]


async def test_upload_map_records_selected_creator_for_all_files() -> None:
    bot = await _bot()
    interaction = _Interaction(id=30, user=_Member(roles=[_Role(200)]))
    creator = _Member(roles=[], display_name="Mapper", id=234567890123456789)
    await cmd_upload_map(
        bot,
        interaction,
        "mini-pvp",
        [
            _Attachment(filename="one.msav", url="https://example/one.msav"),
            _Attachment(filename="two.msav", url="https://example/two.msav"),
        ],
        creator=creator,
    )
    _, files = _uploads(bot)[0]
    assert all(
        item["discordUpload"]["creator"]
        == {"discordId": str(creator.id), "discordUsername": "Mapper"}
        for item in files
    )
    assert "✅ Installed; attribution saved" in interaction.replies[-1]


async def test_upload_creator_overrides_follow_original_attachment_positions() -> None:
    bot = await _bot()
    interaction = _Interaction(id=40, user=_Member(roles=[_Role(200)]))
    default = _Member([], "Default", 111)
    override = _Member([], "Override", 222)
    await cmd_upload_map(
        bot,
        interaction,
        "mini-pvp",
        [
            _Attachment("one.msav", "https://example/one"),
            _Attachment("skip.txt", "https://example/skip"),
            _Attachment("three.msav", "https://example/three"),
        ],
        creator=default,
        creators=[None, None, override],
    )
    files = _uploads(bot)[0][1]
    assert [item["discordUpload"]["creator"]["discordId"] for item in files] == [
        "111",
        "222",
    ]


async def test_upload_timeout_reports_uncertainty_without_claiming_failure_or_retrying() -> (
    None
):
    bot = await _bot(side_effect=TimeoutError)
    interaction = _Interaction(id=50, user=_Member(roles=[_Role(200)]))
    await cmd_upload_map(
        bot,
        interaction,
        "mini-pvp",
        [_Attachment("arena.msav", "https://example/arena")],
    )
    assert "Some files may already be installed" in interaction.replies[-1]
    assert len(_uploads(bot)) == 1


async def test_upload_formats_partial_result_and_disables_mentions() -> None:
    bot = await _bot(
        return_value=MapsUploadResponseV1.from_payload(
            {
                "messageType": "maps.upload.response",
                "messageVersion": 1,
                "server": "mini-pvp",
                "results": [
                    {"fileName": "ok.msav", "status": "installed"},
                    {"fileName": "bad.msav", "status": "invalid_map"},
                ],
            }
        ),
    )
    await remember_maps(bot)
    interaction = _Interaction(id=60, user=_Member(roles=[_Role(200)]))
    interaction.edit_original_response = AsyncMock()
    await cmd_upload_map(
        bot,
        interaction,
        "mini-pvp",
        [
            _Attachment("ok.msav", "https://example/ok"),
            _Attachment("bad.msav", "https://example/bad"),
        ],
    )
    call = interaction.edit_original_response.call_args.kwargs
    assert "✅ Installed" in call["content"] and "❌ Invalid map" in call["content"]
    assert not call["allowed_mentions"].everyone and not call["allowed_mentions"].users
    assert not await maps_remembered(bot)


async def test_malformed_response_invalidates_cached_map_list_and_finishes_message() -> (
    None
):
    bot = await _bot(side_effect=TypeError("results must be a list"))
    await remember_maps(bot)
    interaction = _Interaction(id=70, user=_Member(roles=[_Role(200)]))
    await cmd_upload_map(
        bot,
        interaction,
        "mini-pvp",
        [_Attachment("arena.msav", "https://example/arena")],
    )
    assert "Could not confirm" in interaction.replies[-1]
    assert not await maps_remembered(bot)


async def test_invalid_upload_is_rejected_before_rpc_or_uploading_message() -> None:
    bot = await _bot()
    interaction = _Interaction(id=80, user=_Member(roles=[_Role(200)]))
    await cmd_upload_map(
        bot,
        interaction,
        "mini-pvp",
        [_Attachment("../arena.msav", "https://example/arena")],
    )
    assert "nothing was sent" in interaction.ephemeral_replies[-1]
    assert _uploads(bot) == []
    assert not interaction.replies


async def test_attachment_only_discord_message_is_not_published_as_empty_chat() -> None:
    bot, _ = await real_bot()
    # Channel 1 is the bridge channel of mini-pvp.
    bot.container.servers.update_server("mini-pvp", 1, 0, 10, "v1")
    author = SimpleNamespace(bot=False, display_name="tester")

    def message(id: int, content: str) -> Any:
        return SimpleNamespace(
            id=id, author=author, channel=SimpleNamespace(id=1), content=content
        )

    await bot.on_message(message(1, ""))
    assert await published(bot.bus, CHAT) == []

    await bot.on_message(message(2, "hello"))
    assert [event["message"] for event in await published(bot.bus, CHAT)] == ["hello"]


async def test_repeated_upload_interaction_sends_the_maps_once() -> None:
    bot = await _bot()
    attachments: list[Any] = [_Attachment("arena.msav", "https://example/arena")]

    first = _Interaction(id=90, user=_Member(roles=[_Role(200)]))
    await cmd_upload_map(bot, first, "mini-pvp", attachments)
    repeat = _Interaction(id=90, user=_Member(roles=[_Role(200)]))
    await cmd_upload_map(bot, repeat, "mini-pvp", attachments)

    assert len(_uploads(bot)) == 1
    assert repeat.ephemeral_replies


# --- Comparing with the maps already installed -------------------------------------------


ASTEROID = msav(meta={"name": "asteroid C-843", "author": "andrpearl"})
ASTEROID_V2 = msav(
    meta={"name": "asteroid C-843", "author": "andrpearl"}, tail=b"fixed landscape"
)


def _installed_map(
    file_name: str, data: bytes, name: str, author: str
) -> dict[str, str]:
    return {
        "file_name": file_name,
        "name": name,
        "author": author,
        "content_hash": hashlib.sha256(data).hexdigest(),
    }


def _list(bot, *maps: dict[str, str]) -> None:
    bot.container.maps_bus.rpc_maps_list.return_value = list(maps)


@dataclass
class _Pressed:
    """A press of one of the plan's buttons."""

    user: Any
    edits: list[str] = field(default_factory=list)

    @property
    def response(self) -> Any:
        pressed = self

        class _Response:
            async def edit_message(self, *, content: str, **kwargs) -> None:
                pressed.edits.append(content)

            async def send_message(self, text: str, **kwargs) -> None:
                pressed.edits.append(text)

            async def defer(self) -> None:
                pass

        return _Response()

    async def edit_original_response(self, *, content: str, **kwargs) -> None:
        self.edits.append(content)


async def _press(view: Any, label: str, user: Any) -> _Pressed:
    pressed = _Pressed(user)
    button = next(item for item in view.children if item.label == label)
    await button.callback(pressed)
    return pressed


async def test_a_new_version_under_the_installed_name_is_uploaded_as_an_update() -> (
    None
):
    bot = await _bot()
    _list(bot, _installed_map("asteroid.msav", ASTEROID, "asteroid C-843", "andrpearl"))
    interaction = _Interaction(id=100, user=_Member(roles=[_Role(200)]))
    await cmd_upload_map(
        bot,
        interaction,
        "mini-pvp",
        [_Attachment("asteroid.msav", "https://example/a", ASTEROID_V2)],
    )
    assert [files[0]["filename"] for _, files in _uploads(bot)] == ["asteroid.msav"]
    assert "updated the installed version" in interaction.replies[-1]


async def test_a_renamed_new_version_waits_and_replaces_the_installed_map() -> None:
    # Discord renamed the author's new file; installing it as is would add a second map.
    bot = await _bot()
    _list(bot, _installed_map("asteroid.msav", ASTEROID, "asteroid C-843", "andrpearl"))
    reviewer = _Member(roles=[_Role(200)])
    interaction = _Interaction(id=110, user=reviewer)
    await cmd_upload_map(
        bot,
        interaction,
        "mini-pvp",
        [_Attachment("asteroid_1.msav", "https://example/a1", ASTEROID_V2)],
    )
    assert _uploads(bot) == []
    plan = interaction.replies[-1]
    assert "looks like a map that is already installed" in plan
    assert "`asteroid.msav`" in plan and "same map name and author" in plan

    pressed = await _press(interaction.views[-1], "Replace asteroid.msav", reviewer)
    ((_, files),) = _uploads(bot)
    assert files[0]["filename"] == "asteroid.msav"
    assert files[0]["url"] == "https://example/a1"
    assert "from `asteroid_1.msav`" in pressed.edits[-1]


async def test_another_reviewer_cannot_answer_the_plan() -> None:
    bot = await _bot()
    _list(bot, _installed_map("asteroid.msav", ASTEROID, "asteroid C-843", "andrpearl"))
    interaction = _Interaction(id=120, user=_Member(roles=[_Role(200)]))
    await cmd_upload_map(
        bot,
        interaction,
        "mini-pvp",
        [_Attachment("asteroid_1.msav", "https://example/a1", ASTEROID_V2)],
    )
    other = _Member(roles=[_Role(200)], id=999)
    pressed = await _press(interaction.views[-1], "Upload as is", other)
    assert pressed.edits == ["You do not have permission to do this."]
    assert _uploads(bot) == []


async def test_overwriting_a_different_map_waits_and_cancel_sends_nothing() -> None:
    bot = await _bot()
    _list(bot, _installed_map("starwars.msav", b"other bytes", "Star Wars", "someone"))
    reviewer = _Member(roles=[_Role(200)])
    interaction = _Interaction(id=130, user=reviewer)
    await cmd_upload_map(
        bot,
        interaction,
        "mini-pvp",
        [_Attachment("starwars.msav", "https://example/s", ASTEROID)],
    )
    assert "overwrites a different map" in interaction.replies[-1]
    pressed = await _press(interaction.views[-1], "Cancel", reviewer)
    assert pressed.edits == ["Upload cancelled; nothing was sent to the server."]
    assert _uploads(bot) == []


async def test_replace_installs_the_file_under_the_replaced_name() -> None:
    bot = await _bot()
    _list(bot, _installed_map("asteroid.msav", ASTEROID, "asteroid C-843", "andrpearl"))
    interaction = _Interaction(id=140, user=_Member(roles=[_Role(200)]))
    await cmd_upload_map(
        bot,
        interaction,
        "mini-pvp",
        [_Attachment("asteroid C-843 (1).msav", "https://example/a1", ASTEROID_V2)],
        replace="asteroid.msav",
    )
    ((_, files),) = _uploads(bot)
    assert files[0]["filename"] == "asteroid.msav"
    assert (
        "✅ Installed; attribution saved — `asteroid.msav`" in interaction.replies[-1]
    )


async def test_replace_of_a_map_that_is_not_installed_sends_nothing() -> None:
    bot = await _bot()
    interaction = _Interaction(id=150, user=_Member(roles=[_Role(200)]))
    await cmd_upload_map(
        bot,
        interaction,
        "mini-pvp",
        [_Attachment("arena.msav", "https://example/a", ASTEROID)],
        replace="missing.msav",
    )
    assert "There is no map `missing.msav`" in interaction.replies[-1]
    assert _uploads(bot) == []


async def test_replace_needs_exactly_one_file() -> None:
    bot = await _bot()
    interaction = _Interaction(id=160, user=_Member(roles=[_Role(200)]))
    await cmd_upload_map(
        bot,
        interaction,
        "mini-pvp",
        [
            _Attachment("a.msav", "https://example/a"),
            _Attachment("b.msav", "https://example/b"),
        ],
        replace="a.msav",
    )
    assert interaction.ephemeral_replies == [
        "`replace` works with exactly one .msav file."
    ]
    assert _uploads(bot) == []


async def test_a_server_that_does_not_list_its_maps_still_gets_the_upload() -> None:
    bot = await _bot()
    bot.container.maps_bus.rpc_maps_list.side_effect = TimeoutError
    interaction = _Interaction(id=170, user=_Member(roles=[_Role(200)]))
    await cmd_upload_map(
        bot,
        interaction,
        "mini-pvp",
        [_Attachment("arena.msav", "https://example/a", ASTEROID)],
    )
    assert len(_uploads(bot)) == 1
    assert "duplicates were not checked" in interaction.replies[-1]


async def test_upload_replies_in_the_users_language() -> None:
    bot = await _bot()
    interaction = _Interaction(id=180, user=_Member(roles=[_Role(200)]))
    interaction.locale = discord.Locale.russian  # type: ignore[attr-defined]
    await cmd_upload_map(
        bot,
        interaction,
        "mini-pvp",
        [_Attachment("arena.msav", "https://example/a", ASTEROID)],
    )
    assert "✅ Установлена, атрибуция сохранена" in interaction.replies[-1]


async def test_replace_uses_the_installed_spelling_of_the_name() -> None:
    bot = await _bot()
    _list(bot, _installed_map("Asteroid.msav", ASTEROID, "asteroid C-843", "andrpearl"))
    interaction = _Interaction(id=190, user=_Member(roles=[_Role(200)]))
    await cmd_upload_map(
        bot,
        interaction,
        "mini-pvp",
        [_Attachment("new.msav", "https://example/n", ASTEROID_V2)],
        replace="asteroid.msav",
    )
    ((_, files),) = _uploads(bot)
    assert files[0]["filename"] == "Asteroid.msav"
