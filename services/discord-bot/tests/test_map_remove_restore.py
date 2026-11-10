"""``/maps remove`` keeps a copy of the file; **Restore** uploads it back."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from types import SimpleNamespace
from typing import Any

from backends import GameServer, real_bot
from bot_fakes import make_settings
from test_cases_msav import msav
from xcore_protocol.generated.maps import (
    MapsFileRequestV1,
    MapsFileResponseV1,
    MapsListRequestV1,
    MapsListResponseV1,
    MapsRemoveRequestV1,
    MapsRemoveResponseV1,
    MapsRestoreRequestV1,
    MapsRestoreResponseV1,
    MapsTrashListRequestV1,
    MapsTrashListResponseV1,
)
from xcore_protocol.generated.shared import MapBlobRefV1ContentType

from xcore_discord_bot.map_admin import cmd_duplicates, cmd_remove_map, cmd_trash
from xcore_discord_bot.map_backups import COLLECTION, restore_map

LIST = MapsListRequestV1.MESSAGE_TYPE
REMOVE = MapsRemoveRequestV1.MESSAGE_TYPE
FILE = MapsFileRequestV1.MESSAGE_TYPE
RESTORE = MapsRestoreRequestV1.MESSAGE_TYPE
TRASH = MapsTrashListRequestV1.MESSAGE_TYPE
REVIEWER = 200

STAR_WARS = msav(meta={"name": "Star Wars", "author": "someone"})


def _entry(
    file_name: str, data: bytes, name: str, author: str, **extra: Any
) -> dict[str, Any]:
    return {
        "name": name,
        "fileName": file_name,
        "author": author,
        "contentHash": hashlib.sha256(data).hexdigest(),
        **extra,
    }


@dataclass
class _Message:
    """A message the bot sent; ``edit`` keeps the latest state."""

    content: str
    kwargs: dict[str, Any]
    id: int = 900
    channel: Any = field(default_factory=lambda: SimpleNamespace(id=55))
    jump_url: str = "https://discord.com/channels/1/55/900"
    edits: list[dict[str, Any]] = field(default_factory=list)

    async def edit(self, **kwargs: Any) -> None:
        self.edits.append(kwargs)


@dataclass
class _User:
    id: int = 7
    roles: list[Any] = field(default_factory=lambda: [SimpleNamespace(id=REVIEWER)])
    display_name: str = "Reviewer"
    name: str = "reviewer"


@dataclass
class _Interaction:
    """A slash command or a button press; records everything the bot answers."""

    user: Any = field(default_factory=_User)
    message: Any = None
    locale: Any = None
    sent: list[_Message] = field(default_factory=list)
    edits: list[str] = field(default_factory=list)
    deferred: bool = False

    @property
    def response(self) -> Any:
        outer = self

        class _Response:
            async def defer(self, **kwargs: Any) -> None:
                outer.deferred = True

            async def send_message(self, text: str, **kwargs: Any) -> None:
                outer.sent.append(_Message(text, kwargs))

            async def edit_message(self, *, content: str, **kwargs: Any) -> None:
                outer.edits.append(content)

        return _Response()

    @property
    def followup(self) -> Any:
        outer = self

        class _Followup:
            async def send(self, content: str = "", **kwargs: Any) -> _Message:
                message = _Message(content, kwargs, id=900 + len(outer.sent))
                outer.sent.append(message)
                return message

        return _Followup()

    async def edit_original_response(self, *, content: str, **kwargs: Any) -> None:
        self.edits.append(content)


async def _bot(maps: list[dict[str, Any]], files: dict[str, bytes]):
    """A bot whose ``mini-pvp`` lists ``maps``, sends ``files`` and removes what it is asked."""
    bot, db = await real_bot(
        settings=make_settings(
            rpc_timeout_ms=200, discord_map_reviewer_role_id=REVIEWER
        )
    )
    game = GameServer(bot)
    installed = list(maps)
    blobs = {}
    for name, data in files.items():
        blobs[name] = await bot.container.maps_bus.write_blob(
            data, MapBlobRefV1ContentType.APPLICATION_OCTET_STREAM
        )
    game.answers[LIST] = lambda payload: {
        "messageType": MapsListResponseV1.MESSAGE_TYPE,
        "messageVersion": 1,
        "server": payload["server"],
        "maps": installed,
    }

    def send_file(payload: dict[str, Any]) -> dict[str, Any]:
        blob = blobs.get(payload["fileName"])
        answer = {
            "messageType": MapsFileResponseV1.MESSAGE_TYPE,
            "messageVersion": 1,
            "server": payload["server"],
            "fileName": payload["fileName"],
            "status": "ok" if blob else "not_found",
        }
        if blob:
            answer["file"] = blob.to_payload()
            answer["contentHash"] = blob.sha256
        return answer

    def remove(payload: dict[str, Any]) -> dict[str, Any]:
        installed[:] = [m for m in installed if m["fileName"] != payload["fileName"]]
        return {
            "messageType": MapsRemoveResponseV1.MESSAGE_TYPE,
            "messageVersion": 1,
            "server": payload["server"],
            "result": f"Successfully removed map X ({payload['fileName']})",
        }

    game.answers[FILE] = send_file
    game.answers[REMOVE] = remove
    return bot, db, game, installed


async def _press(view: Any, label: str, interaction: _Interaction) -> None:
    button = next(item for item in view.children if item.label == label)
    await button.callback(interaction)


async def test_remove_shows_the_card_warns_about_the_same_map_and_keeps_a_copy() -> (
    None
):
    bot, db, game, installed = await _bot(
        [
            _entry("starwars.msav", STAR_WARS, "Star Wars", "someone"),
            _entry("star_wars_fixed.msav", b"fix", "Star Wars", "someone"),
        ],
        {"starwars.msav": STAR_WARS},
    )
    command = _Interaction()
    await cmd_remove_map(bot, command, "mini-pvp", "starwars.msav")

    (question,) = command.sent
    assert question.kwargs["ephemeral"] is True
    assert question.kwargs["embed"].title == "Star Wars"
    assert "Other installed files hold the same map" in question.content
    assert "`star_wars_fixed.msav`" in question.content
    assert "**Restore**" in question.content
    assert game.sent(REMOVE) == []

    pressed = _Interaction(user=command.user)
    await _press(question.kwargs["view"], "Remove", pressed)

    assert [m["fileName"] for m in installed] == ["star_wars_fixed.msav"]
    (public,) = pressed.sent
    assert "ephemeral" not in public.kwargs
    assert public.kwargs["file"].filename == "starwars.msav"
    assert "removed `starwars.msav`" in public.edits[-1]["content"]
    restore = public.edits[-1]["view"].children[0]
    (record,) = await db[COLLECTION].find({}).to_list()
    assert restore.custom_id == f"xcore:map-restore:{record['_id']}"
    assert record["sha256"] == hashlib.sha256(STAR_WARS).hexdigest()
    assert record["message_id"] == str(public.id)
    assert "removed `starwars.msav`" in pressed.edits[-1]


async def test_remove_without_a_copy_says_so_before_and_after() -> None:
    bot, db, _, installed = await _bot(
        [_entry("arena.msav", STAR_WARS, "Arena", "anuke")], {}
    )
    command = _Interaction()
    await cmd_remove_map(bot, command, "mini-pvp", "arena.msav")
    (question,) = command.sent
    assert "cannot be restored" in question.content

    pressed = _Interaction(user=command.user)
    await _press(question.kwargs["view"], "Remove", pressed)
    assert installed == []
    assert "No copy of the file was kept." in pressed.sent[-1].content
    assert await db[COLLECTION].find({}).to_list() == []


async def test_remove_of_an_unknown_map_asks_nothing() -> None:
    bot, _, game, _ = await _bot(
        [_entry("arena.msav", STAR_WARS, "Arena", "anuke")], {}
    )
    command = _Interaction()
    await cmd_remove_map(bot, command, "mini-pvp", "missing.msav")
    assert command.sent[-1].content == "There is no map `missing.msav` on `mini-pvp`."
    assert "view" not in command.sent[-1].kwargs
    assert game.sent(REMOVE) == []


async def test_cancel_removes_nothing() -> None:
    bot, _, game, installed = await _bot(
        [_entry("arena.msav", STAR_WARS, "Arena", "anuke")], {"arena.msav": STAR_WARS}
    )
    command = _Interaction()
    await cmd_remove_map(bot, command, "mini-pvp", "arena.msav")
    pressed = _Interaction(user=command.user)
    await _press(command.sent[0].kwargs["view"], "Cancel", pressed)
    assert pressed.edits == ["Map removal cancelled."]
    assert game.sent(REMOVE) == [] and len(installed) == 1


async def _removed(bot) -> tuple[_Message, str]:
    command = _Interaction()
    await cmd_remove_map(bot, command, "mini-pvp", "starwars.msav")
    pressed = _Interaction(user=command.user)
    await _press(command.sent[0].kwargs["view"], "Remove", pressed)
    public = pressed.sent[0]
    removal_id = public.edits[-1]["view"].children[0].removal_id
    # Discord hands the button press the message with a fresh link to the copy.
    public.attachments = [
        SimpleNamespace(
            filename="starwars.msav", url="https://cdn.example/starwars.msav"
        )
    ]
    return public, removal_id


async def test_restore_uploads_the_copy_under_its_name_with_its_creator() -> None:
    bot, db, game, _ = await _bot(
        [
            _entry(
                "starwars.msav",
                STAR_WARS,
                "Star Wars",
                "someone",
                discordUpload={
                    "uploadedBy": {"discordId": "1", "discordUsername": "Old reviewer"},
                    "uploadedAt": 1791806400000,
                    "creator": {"discordId": "42", "discordUsername": "RiTr"},
                },
            )
        ],
        {"starwars.msav": STAR_WARS},
    )
    public, removal_id = await _removed(bot)

    press = _Interaction(message=public)
    await restore_map(bot, press, removal_id)

    ((server, files, _),) = game.upload_calls
    assert server == "mini-pvp"
    assert files[0]["filename"] == "starwars.msav"
    assert files[0]["url"] == "https://cdn.example/starwars.msav"
    assert files[0]["discordUpload"]["creator"]["discordId"] == "42"
    assert files[0]["discordUpload"]["uploadedBy"]["discordId"] == "7"
    assert "✅ Installed" in press.sent[-1].content
    assert public.edits[-1]["view"].children[0].item.disabled is True
    (record,) = await db[COLLECTION].find({}).to_list()
    assert record["restored_by"]["discord_id"] == "7" and "restoring" not in record

    again = _Interaction(message=public)
    await restore_map(bot, again, removal_id)
    assert again.sent[-1].content == "This map has already been restored."
    assert len(game.upload_calls) == 1


async def test_restore_does_not_overwrite_a_file_that_took_the_name() -> None:
    bot, _, game, installed = await _bot(
        [_entry("starwars.msav", STAR_WARS, "Star Wars", "someone")],
        {"starwars.msav": STAR_WARS},
    )
    public, removal_id = await _removed(bot)
    installed.append(_entry("starwars.msav", b"new", "Other", "x"))

    press = _Interaction(message=public)
    await restore_map(bot, press, removal_id)
    assert "already has a file `starwars.msav`" in press.sent[-1].content
    assert game.upload_calls == []


async def test_only_map_reviewers_restore() -> None:
    bot, _, game, _ = await _bot(
        [_entry("starwars.msav", STAR_WARS, "Star Wars", "someone")],
        {"starwars.msav": STAR_WARS},
    )
    public, removal_id = await _removed(bot)
    press = _Interaction(user=_User(id=8, roles=[]), message=public)
    await restore_map(bot, press, removal_id)
    assert press.sent[-1].content == "Only map reviewers can restore maps."
    assert game.upload_calls == []


async def test_duplicates_lists_the_same_map_under_two_names() -> None:
    bot, _, _, _ = await _bot(
        [
            _entry("asteroid_-843.msav", b"v1", "asteroid C-843", "andrpearl"),
            _entry("asteroid_-843_1.msav", b"v2", "asteroid C-843", "andrpearl"),
            _entry("arena.msav", b"a", "Arena", "anuke"),
        ],
        {},
    )
    command = _Interaction()
    await cmd_duplicates(bot, command, "mini-pvp")
    embed = command.sent[-1].kwargs["embed"]
    assert "The same map name and author" in embed.description
    assert "`asteroid_-843.msav`" in embed.description
    assert "`asteroid_-843_1.msav`" in embed.description
    assert "arena" not in embed.description


# --- The server's own copy (protocol 0.19.0) ------------------------------------------

KEPT = "1791800000000-aaaaaaaa-starwars.msav"


def _kept_remove(installed: list[dict[str, Any]], status: str = "removed"):
    """A server at 0.19.0: removes only the file it was told about, and keeps a copy."""

    def remove(payload: dict[str, Any]) -> dict[str, Any]:
        answer = {
            "messageType": MapsRemoveResponseV1.MESSAGE_TYPE,
            "messageVersion": 1,
            "server": payload["server"],
            "result": "...",
            "status": status,
        }
        if status == "removed":
            installed[:] = [
                m for m in installed if m["fileName"] != payload["fileName"]
            ]
            answer["trashId"] = KEPT
            answer["contentHash"] = hashlib.sha256(STAR_WARS).hexdigest()
        return answer

    return remove


def _restored(status: str = "restored"):
    def restore(payload: dict[str, Any]) -> dict[str, Any]:
        return {
            "messageType": MapsRestoreResponseV1.MESSAGE_TYPE,
            "messageVersion": 1,
            "server": payload["server"],
            "trashId": payload["trashId"],
            "status": status,
            "fileName": payload.get("fileName") or "starwars.msav",
        }

    return restore


async def test_remove_names_the_file_it_saw_and_a_changed_file_stays() -> None:
    bot, _, game, installed = await _bot(
        [_entry("starwars.msav", STAR_WARS, "Star Wars", "someone")],
        {"starwars.msav": STAR_WARS},
    )
    game.answers[REMOVE] = _kept_remove(installed, status="conflict")
    command = _Interaction()
    await cmd_remove_map(bot, command, "mini-pvp", "starwars.msav")
    pressed = _Interaction(user=command.user)
    await _press(command.sent[0].kwargs["view"], "Remove", pressed)

    (sent,) = game.sent(REMOVE)
    assert sent["expectedContentHash"] == hashlib.sha256(STAR_WARS).hexdigest()
    assert len(installed) == 1
    assert "changed since you looked at it; nothing was removed" in pressed.edits[-1]
    public = pressed.sent[0]
    assert public.edits[-1]["attachments"] == []  # the copy is dropped, no Restore
    assert "view" not in public.edits[-1]


async def test_a_server_copy_alone_still_offers_restore_and_puts_it_back() -> None:
    # No copy could be fetched for Discord, but the server set the file aside.
    bot, db, game, installed = await _bot(
        [_entry("starwars.msav", STAR_WARS, "Star Wars", "someone")], {}
    )
    game.answers[REMOVE] = _kept_remove(installed)
    game.answers[RESTORE] = _restored()
    command = _Interaction()
    await cmd_remove_map(bot, command, "mini-pvp", "starwars.msav")
    pressed = _Interaction(user=command.user)
    await _press(command.sent[0].kwargs["view"], "Remove", pressed)

    public = pressed.sent[-1]
    public.attachments = []  # a message without a file
    assert "The server kept a copy of the file." in public.content
    removal_id = public.kwargs["view"].children[0].removal_id
    (record,) = await db[COLLECTION].find({}).to_list()
    assert record["trash_id"] == KEPT

    press = _Interaction(message=public)
    await restore_map(bot, press, removal_id)
    (sent,) = game.sent(RESTORE)
    assert sent["trashId"] == KEPT and sent["fileName"] == "starwars.msav"
    assert game.upload_calls == []
    assert "put back, with its attribution" in press.sent[-1].content
    (record,) = await db[COLLECTION].find({}).to_list()
    assert record["restored_by"]["discord_id"] == "7"


async def test_restore_falls_back_to_the_discord_copy_the_server_lost() -> None:
    bot, _, game, installed = await _bot(
        [_entry("starwars.msav", STAR_WARS, "Star Wars", "someone")],
        {"starwars.msav": STAR_WARS},
    )
    game.answers[REMOVE] = _kept_remove(installed)
    game.answers[RESTORE] = _restored("not_found")
    public, removal_id = await _removed(bot)

    press = _Interaction(message=public)
    await restore_map(bot, press, removal_id)
    assert len(game.sent(RESTORE)) == 1
    ((_, files, _),) = game.upload_calls
    assert files[0]["url"] == "https://cdn.example/starwars.msav"
    assert files[0]["expectAbsent"] is True
    assert "✅ Installed" in press.sent[-1].content


async def test_trash_lists_kept_files_and_puts_the_chosen_one_back() -> None:
    bot, _, game, _ = await _bot([], {})
    game.answers[TRASH] = lambda payload: {
        "messageType": MapsTrashListResponseV1.MESSAGE_TYPE,
        "messageVersion": 1,
        "server": payload["server"],
        "entries": [
            {
                "trashId": KEPT,
                "fileName": "starwars.msav",
                "contentHash": "a" * 64,
                "sizeBytes": 10,
                "trashedAt": 1791800000000,
                "reason": "replaced",
                "name": "[red]Star Wars",
            }
        ],
    }
    game.answers[RESTORE] = _restored()
    command = _Interaction()
    await cmd_trash(bot, command, "mini-pvp")
    (listing,) = command.sent
    assert "`starwars.msav` — Star Wars · replaced by an upload" in listing.content
    assert listing.kwargs["ephemeral"] is True

    view = listing.kwargs["view"]
    view.select._values = ["0"]  # what Discord fills in when an option is picked
    pressed = _Interaction(user=command.user)
    await view._chosen(pressed)
    (sent,) = game.sent(RESTORE)
    assert sent["trashId"] == KEPT and sent.get("fileName") is None
    assert "put back, with its attribution" in pressed.edits[-1]


async def test_trash_of_a_server_with_nothing_kept_says_so() -> None:
    bot, _, game, _ = await _bot([], {})
    game.answers[TRASH] = lambda payload: {
        "messageType": MapsTrashListResponseV1.MESSAGE_TYPE,
        "messageVersion": 1,
        "server": payload["server"],
        "entries": [],
    }
    command = _Interaction()
    await cmd_trash(bot, command, "mini-pvp")
    assert command.sent[-1].content == "`mini-pvp` has no files set aside."
