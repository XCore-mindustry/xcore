"""Later revisions of a map post, sending a map back for changes, and fixes of someone
else's map (map-updates-design-spec.md, stage 1)."""

from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock

from cases_badges_fakes import last_view
from cases_fakes import Interaction
from cases_maps_fakes import (
    Attachment,
    authored_message,
    inspect_payload,
    map_core,
    reviewer,
)
from test_cases_msav import msav

from xcore_discord_bot.cases.flows import maps
from xcore_discord_bot.cases.model import CaseStatus
from xcore_discord_bot.map_admin import cmd_rollback, cmd_upload_map

AUTHOR = "42"


def installed(file_name="arena.msav", *, name="Arena", sha="0" * 64, creator=None):
    item = {"file_name": file_name, "name": name, "author": "", "content_hash": sha}
    if creator is not None:
        item.update(creator_discord_id=creator, creator_name=f"Creator {creator}")
    return item


async def first_review():
    env = await map_core()
    service, _, _, _, _, _, thread, _, _ = env
    await maps.on_thread_create(service, thread)
    return env, (await service.store.map_cases(thread.id))[-1]


async def post_file(env, attachment):
    """The author posts ``attachment``; returns the post's newest review."""
    service, _, bot, _, _, author, thread, _, _ = env
    service.transport.rpc_inspect_map.side_effect = lambda **kw: inspect_payload(
        attachment
    )
    await maps.on_message(service, authored_message(bot, thread, author, attachment))
    return (await service.store.map_cases(thread.id))[-1]


async def accept(service, case, bot, **values):
    interaction = Interaction(bot, user=reviewer())
    await service.dispatch_action(
        case.number,
        "accept",
        reviewer(),
        "en",
        {"server": "mini-pvp", **values},
        interaction=interaction,
    )
    return interaction


def card(thread, case):
    return next(
        m for m in thread.messages if m.id == int(case.discord["card_message_id"])
    )


def labels(message):
    return [getattr(item, "item", item).label for item in message.view.children]


async def test_a_new_file_after_acceptance_opens_the_next_revision():
    env, first = await first_review()
    service, _, bot, _, _, _, thread, _, _ = env
    await accept(service, first, bot)
    first = await service.get(first.number)
    assert first.status == CaseStatus.RESOLVED

    v2 = Attachment(3000, msav(meta={"name": "Arena"}, tail=b"fixed"), "arena (1).msav")
    second = await post_file(env, v2)

    assert second.number != first.number and second.active
    assert second.payload["revision"] == 2
    assert second.payload["previous"]["case"] == first.number
    assert second.payload["installed"] == {
        "server": "mini-pvp",
        "file_name": "arena.msav",
        "case": first.number,
        "revision": 1,
        "sha256": first.payload["sha256"],
    }
    assert [tag.name for tag in thread.applied_tags] == ["PvP", "Waiting"]
    message = card(thread, second)
    fields = {f.name: f.value for f in message.embeds[0].fields}
    assert fields["Revision"].startswith("Revision 2 · the previous one: case #")
    assert fields["Installed now"] == "arena.msav · mini-pvp"
    assert "File size" in fields["Changes since the previous revision"]
    assert labels(message)[0] == "Update"
    # The first card keeps its own result; only the newest one drives the tags.
    await service.project(first.number)
    assert [tag.name for tag in thread.applied_tags] == ["PvP", "Waiting"]


async def test_an_update_goes_to_the_installed_file_without_a_question_or_a_badge(
    monkeypatch,
):
    env, first = await first_review()
    service, _, bot, bus, _, _, thread, _, _ = env
    await accept(service, first, bot)
    grant = AsyncMock()
    monkeypatch.setattr(maps, "grant_map_maker", grant)
    v2 = Attachment(3000, msav(meta={"name": "Arena"}, tail=b"fixed"), "arena (1).msav")
    second = await post_file(env, v2)
    bot.bus.rpc_maps_list.return_value = [installed(sha=first.payload["sha256"])]

    servers = Interaction(bot, user=reviewer())
    await service.dispatch_action(
        second.number, "accept", reviewer(), "en", interaction=servers
    )
    option = last_view(servers).select.options[0]
    assert (option.value, option.label) == ("mini-pvp", "mini-pvp — update arena.msav")

    await accept(service, second, bot)
    final = await service.get(second.number)
    assert final.resolution.code == "accepted"
    assert final.resolution.file_name == "arena.msav"
    assert bus.upload_calls[-1][1][0]["filename"] == "arena.msav"
    assert bus.upload_calls[-1][1][0]["url"] == v2.url
    grant.assert_not_awaited()
    assert not final.payload.get("pending_badge")
    assert any("The update of your map" in m.content for m in thread.messages)


async def test_a_rejected_post_stays_open_and_takes_the_next_file():
    env, first = await first_review()
    service, _, _, _, _, _, thread, _, _ = env
    await service.reject(first.number, reviewer(), reason_code="broken")
    assert not thread.locked
    second = await post_file(env, Attachment(3000, msav(meta={"name": "Arena v2"})))
    assert second.payload["revision"] == 2
    assert second.payload["previous"]["status"] == "rejected"
    assert "installed" not in second.payload
    assert labels(card(thread, second))[0] == "Accept"


async def test_request_changes_keeps_the_review_open_and_a_new_file_clears_it():
    env, case = await first_review()
    service, _, bot, bus, _, _, thread, _, _ = env
    interaction = Interaction(bot, user=reviewer())
    await service.dispatch_action(
        case.number,
        "changes",
        reviewer(),
        "en",
        {"reason_text": "The cores are too close"},
        interaction=interaction,
    )
    current = await service.get(case.number)
    assert current.actionable and current.resolution is None
    assert current.payload["changes_requested"]["reason"] == "The cores are too close"
    note = thread.messages[-1]
    assert note.content.startswith(f"<@{AUTHOR}>, the reviewer asks for changes")
    fields = {f.name: f.value for f in card(thread, current).embeds[0].fields}
    assert fields["Changes requested"] == "The cores are too close"
    assert not thread.locked and not bus.upload_calls

    refreshed = await post_file(env, Attachment(3000, msav(meta={"name": "Arena"})))
    assert refreshed.number == case.number
    assert "changes_requested" not in refreshed.payload


async def test_a_fix_of_someone_elses_map_keeps_its_creator_and_records_the_fixer(
    monkeypatch,
):
    env, case = await first_review()
    service, _, bot, bus, _, _, thread, _, _ = env
    grant = AsyncMock()
    monkeypatch.setattr(maps, "grant_map_maker", grant)
    bot.bus.rpc_maps_list.return_value = [
        installed("starwars.msav", name="Arena", creator="999")
    ]
    interaction = await accept(service, case, bot)
    await last_view(interaction).select.callback(
        _chosen(last_view(interaction), "replace:starwars.msav", bot)
    )
    final = await service.get(case.number)
    assert final.resolution.file_name == "starwars.msav"
    upload = bus.upload_calls[-1][1][0]["discordUpload"]
    assert upload["creator"]["discordId"] == "999"
    assert final.payload["fixer"]["discord_id"] == AUTHOR
    grant.assert_not_awaited()
    fields = {f.name: f.value for f in card(thread, final).embeds[0].fields}
    assert fields["Fixed by"] == f"<@{AUTHOR}>"
    assert any("Your fix of" in m.content for m in thread.messages)


async def test_a_replaced_map_without_a_creator_asks_who_made_it():
    env, case = await first_review()
    service, _, bot, bus, _, _, _, _, _ = env
    bot.bus.rpc_maps_list.return_value = [installed("starwars.msav", name="Arena")]
    interaction = await accept(service, case, bot, target="replace:starwars.msav")
    view = last_view(interaction)
    assert [o.value for o in view.select.options] == ["author", "unknown"]
    assert not bus.upload_calls
    await view.select.callback(_chosen(view, "unknown", bot))
    final = await service.get(case.number)
    assert "creator" not in bus.upload_calls[-1][1][0]["discordUpload"]
    assert final.payload["fixer"]["discord_id"] == AUTHOR


def _chosen(view, value, bot):
    view.select._values = [value]
    return Interaction(bot, user=reviewer())


async def test_review_can_bind_an_old_post_to_its_installed_map():
    env = await map_core()
    service, _, bot, _, _, _, thread, _, _ = env
    case = await maps.intake(
        service,
        thread,
        manual=True,
        installed={"server": "mini-pvp", "file_name": "old_arena.msav", "sha256": None},
    )
    bot.bus.rpc_maps_list.return_value = [installed("old_arena.msav", name="Something")]
    assert labels(card(thread, case))[0] == "Update"
    interaction = await accept(service, case, bot)
    # The reviewer vouched for the file, which holds no creator: who made the map is asked.
    view = last_view(interaction)
    await view.select.callback(_chosen(view, "author", bot))
    final = await service.get(case.number)
    assert final.resolution.file_name == "old_arena.msav"
    assert final.payload.get("fixer") is None


async def test_manual_upload_of_a_file_under_review_points_at_the_review():
    env, case = await first_review()
    _, _, bot, bus, _, _, thread, _, attachment = env
    bot.settings = bot.settings.model_copy(update={"cases_maps_enabled": True})
    replies = []
    interaction = SimpleNamespace(
        id=900,
        user=SimpleNamespace(id=101, display_name="Staff", name="staff"),
        channel=thread,
        locale=None,
        response=SimpleNamespace(send_message=AsyncMock()),
        edit_original_response=AsyncMock(
            side_effect=lambda **kw: replies.append(kw["content"])
        ),
    )
    upload = SimpleNamespace(
        filename="copy.msav",
        url="https://example/copy",
        size=len(attachment.data),
        read=AsyncMock(return_value=attachment.data),
    )
    await cmd_upload_map(bot, interaction, "mini-pvp", [upload])
    assert replies == [
        (
            f"This file is under review in case #{case.number} in this post. Use the "
            "buttons on its card, so the review is closed and the author credited."
        )
    ]
    assert not bus.upload_calls


async def test_rollback_puts_an_earlier_revision_back_under_the_installed_name():
    env, first = await first_review()
    service, _, bot, bus, _, _, thread, _, attachment = env
    await accept(service, first, bot)
    v2 = Attachment(
        3000, msav(meta={"name": "Arena"}, tail=b"broken"), "arena (1).msav"
    )
    second = await post_file(env, v2)
    first = await service.get(first.number)
    bot.bus.rpc_maps_list.return_value = [installed(sha=first.payload["sha256"])]
    await accept(service, second, bot)
    assert len(bus.upload_calls) == 2

    sent = []
    interaction = SimpleNamespace(
        user=reviewer(),
        channel=thread,
        locale=None,
        response=SimpleNamespace(defer=AsyncMock()),
        followup=SimpleNamespace(
            send=AsyncMock(side_effect=lambda *a, **kw: sent.append((a, kw)))
        ),
    )
    await cmd_rollback(bot, interaction)
    view = sent[-1][1]["view"]
    assert [o.value for o in view.select.options] == [str(first.number)]

    edits = []
    pressed = SimpleNamespace(
        user=reviewer(),
        locale=None,
        response=SimpleNamespace(
            edit_message=AsyncMock(side_effect=lambda **kw: edits.append(kw["content"]))
        ),
        edit_original_response=AsyncMock(
            side_effect=lambda **kw: edits.append(kw["content"])
        ),
    )
    view.select._values = [str(first.number)]
    await view.select.callback(pressed)
    ((_, files, _),) = bus.upload_calls[2:]
    assert files[0]["filename"] == "arena.msav" and files[0]["url"] == attachment.url
    assert "✅ Installed" in edits[-1]
    assert thread.messages[-1].content.startswith("↩️")


async def test_the_unique_thread_index_of_earlier_releases_is_replaced():
    from xcore_discord_bot.cases.store import THREAD_INDEX

    env = await map_core()
    service = env[0]
    cases = service.store.database["cases"]
    # As an earlier release left it: one case per thread, open or closed.
    cases.indexes = [("discord.thread_id", {"unique": True, "sparse": True})]
    await service.store.ensure_indexes()
    info = await cases.index_information()
    assert "discord.thread_id_1" not in info
    assert info[THREAD_INDEX]["partialFilterExpression"] == {
        "active": True,
        "discord.thread_id": {"$exists": True},
    }
    # Starting again changes nothing.
    await service.store.ensure_indexes()
    assert list(await cases.index_information()) == list(info)
