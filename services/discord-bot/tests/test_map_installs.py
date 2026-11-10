"""One map post on several servers, and frozen servers (map-updates-design-spec.md,
stage 2 item 4 and decision 5)."""

from __future__ import annotations

from types import SimpleNamespace

from cases_badges_fakes import last_view
from cases_fakes import NOW, Interaction
from cases_maps_fakes import Attachment
from test_cases_maps_revisions import accept, first_review, post_file
from test_cases_msav import msav

from xcore_discord_bot.cases.model import CaseStatus
from xcore_discord_bot.map_admin import cmd_freeze, cmd_install, cmd_unfreeze
from xcore_discord_bot.map_freezes import MapFreezes


def item(file_name, sha, thread=None, *, name="Arena", creator=None):
    entry = {"file_name": file_name, "name": name, "author": "", "content_hash": sha}
    if thread is not None:
        entry["thread_id"] = str(thread.id)
    if creator is not None:
        entry.update(creator_discord_id=creator, creator_name=f"Creator {creator}")
    return entry


def lists(bot, by_server):
    """Each server lists its own maps."""
    bot.bus.rpc_maps_list.side_effect = lambda *, server, timeout_ms: list(
        by_server.get(server, [])
    )


def with_store(bot, db):
    bot.store = SimpleNamespace(database=db, now_utc=lambda: NOW)


def reviewer_interaction(bot, thread):
    from cases_maps_fakes import reviewer

    interaction = Interaction(bot, user=reviewer())
    interaction.channel = thread
    return interaction


async def accepted_on_two_servers():
    """Revision 1 accepted on mini-pvp and copied to mini-td; revision 2 posted."""
    env, first = await first_review()
    service, db, bot, _, _, _, thread, _, _ = env
    with_store(bot, db)
    await accept(service, first, bot)
    first = await service.get(first.number)
    bot.container.servers.update_server("mini-td", 1, 0, 20, "v1", mode="pvp")
    sha = first.payload["sha256"]
    maps = {
        "mini-pvp": [item("arena.msav", sha, thread)],
        "mini-td": [item("arena-td.msav", sha, thread, creator="77")],
    }
    lists(bot, maps)
    v2 = Attachment(3000, msav(meta={"name": "Arena"}, tail=b"fixed"), "arena (1).msav")
    second = await post_file(env, v2)
    return env, first, second, maps, v2


async def test_accepting_an_update_offers_the_other_servers_and_updates_each():
    env, first, second, _, v2 = await accepted_on_two_servers()
    service, _, bot, bus, _, _, thread, _, _ = env

    asked = await accept(service, second, bot)
    view = last_view(asked)
    assert [o.value for o in view.select.options] == ["all", "only"]
    assert view.select.options[0].label == "Also update mini-td"
    assert len(bus.upload_calls) == 1  # nothing is written before the choice

    await accept(service, second, bot, others="all")
    final = await service.get(second.number)
    assert final.status == CaseStatus.RESOLVED
    assert [call[0] for call in bus.upload_calls[1:]] == ["mini-pvp", "mini-td"]
    (sent,) = bus.upload_calls[-1][1]
    # The other server keeps its file name and its creator, and must still hold the
    # revision it had.
    assert sent["filename"] == "arena-td.msav" and sent["url"] == v2.url
    assert sent["expectedContentHash"] == first.payload["sha256"]
    assert sent["discordUpload"]["creator"]["discordId"] == "77"
    assert sent["discordUpload"]["caseNumber"] == second.number
    assert sent["discordUpload"]["revision"] == 2
    note = next(m.content for m in thread.messages if m.content.startswith("📦"))
    assert "`mini-td`: ✅ Installed" in note
    assert "/maps install" not in note


async def test_only_the_chosen_server_is_updated_when_asked_so():
    env, _, second, _, _ = await accepted_on_two_servers()
    service, _, bot, bus, _, _, _, _, _ = env
    await accept(service, second, bot, others="only")
    assert [call[0] for call in bus.upload_calls[1:]] == ["mini-pvp"]


async def test_a_frozen_other_server_is_left_alone_and_a_frozen_target_warns():
    env, _, second, _, _ = await accepted_on_two_servers()
    service, _, bot, bus, _, _, thread, _, _ = env
    from cases_maps_fakes import reviewer

    await MapFreezes(bot).freeze("mini-td", "tournament", reviewer())
    await MapFreezes(bot).freeze("mini-pvp", "testing", reviewer())

    asked = await accept(service, second, bot, others="all")
    question = asked.events[-1][1]
    assert "❄️ `mini-pvp` is frozen by" in question and "testing" in question
    assert len(bus.upload_calls) == 1

    await accept(service, second, bot, others="all", accept_anyway="yes")
    assert [call[0] for call in bus.upload_calls[1:]] == ["mini-pvp"]
    note = next(m.content for m in thread.messages if m.content.startswith("📦"))
    assert "`mini-td`: ❄️ frozen" in note and "/maps install" in note


async def test_install_puts_the_accepted_revision_on_one_more_server():
    env, first = await first_review()
    service, db, bot, bus, _, _, thread, _, attachment = env
    with_store(bot, db)
    await accept(service, first, bot)
    bot.container.servers.update_server("mini-td", 1, 0, 20, "v1", mode="pvp")
    lists(bot, {"mini-pvp": [item("arena.msav", first.payload["sha256"], thread)]})

    command = reviewer_interaction(bot, thread)
    await cmd_install(bot, command, "mini-td", None)
    question, kwargs = command.events[-1][1], command.events[-1][2]
    assert question.startswith("Install revision 1 (case #")
    pressed = reviewer_interaction(bot, thread)
    await kwargs["view"].children[0].callback(pressed)

    server, (sent,), _ = bus.upload_calls[-1]
    assert server == "mini-td" and sent["filename"] == "arena.msav"
    assert sent["url"] == attachment.url and sent["expectAbsent"] is True
    assert sent["discordUpload"]["caseNumber"] == first.number
    assert sent["discordUpload"]["threadId"] == str(thread.id)
    # A new server credits the post's author.
    assert sent["discordUpload"]["creator"]["discordId"] == "42"
    assert "✅ Installed" in pressed.events[-1][1]["content"]
    assert thread.messages[-1].content.startswith("📦")


async def test_install_does_not_guess_when_the_server_has_a_similar_map():
    env, first = await first_review()
    service, db, bot, bus, _, _, thread, _, _ = env
    with_store(bot, db)
    await accept(service, first, bot)
    bot.container.servers.update_server("mini-td", 1, 0, 20, "v1", mode="pvp")
    lists(bot, {"mini-td": [item("old-arena.msav", "1" * 64)]})
    uploads = len(bus.upload_calls)

    command = reviewer_interaction(bot, thread)
    await cmd_install(bot, command, "mini-td", None)
    text = command.events[-1][1]
    assert "look like this one" in text and "old-arena.msav" in text
    assert "view" not in command.events[-1][2]

    # Naming the map it replaces keeps that file name and its creator.
    lists(bot, {"mini-td": [item("old-arena.msav", "1" * 64, creator="77")]})
    command = reviewer_interaction(bot, thread)
    await cmd_install(bot, command, "mini-td", "old-arena.msav")
    assert command.events[-1][1].startswith("Replace `old-arena.msav` on `mini-td`")
    pressed = reviewer_interaction(bot, thread)
    await command.events[-1][2]["view"].children[0].callback(pressed)
    (sent,) = bus.upload_calls[-1][1]
    assert len(bus.upload_calls) == uploads + 1
    assert sent["filename"] == "old-arena.msav"
    assert sent["expectedContentHash"] == "1" * 64
    assert sent["discordUpload"]["creator"]["discordId"] == "77"


async def test_install_of_the_same_revision_changes_nothing():
    env, first = await first_review()
    service, db, bot, _, _, _, thread, _, _ = env
    with_store(bot, db)
    await accept(service, first, bot)
    lists(bot, {"mini-pvp": [item("arena.msav", first.payload["sha256"], thread)]})
    command = reviewer_interaction(bot, thread)
    await cmd_install(bot, command, "mini-pvp", None)
    assert (
        command.events[-1][1] == "`mini-pvp` already has this revision as `arena.msav`."
    )


async def test_freeze_lists_and_unfreeze():
    env, _ = await first_review()
    _, db, bot, _, _, _, thread, _, _ = env
    with_store(bot, db)
    command = reviewer_interaction(bot, thread)
    await cmd_freeze(bot, command, "mini-pvp", "event tonight")
    assert command.events[-1][1].startswith("`mini-pvp` is frozen")
    listing = reviewer_interaction(bot, thread)
    await cmd_freeze(bot, listing, None, None)
    assert "event tonight" in listing.events[-1][1]

    lifted = reviewer_interaction(bot, thread)
    await cmd_unfreeze(bot, lifted, "mini-pvp")
    assert lifted.events[-1][1] == "`mini-pvp` is no longer frozen."
    again = reviewer_interaction(bot, thread)
    await cmd_unfreeze(bot, again, "mini-pvp")
    assert again.events[-1][1] == "`mini-pvp` was not frozen."
    empty = reviewer_interaction(bot, thread)
    await cmd_freeze(bot, empty, None, None)
    assert empty.events[-1][1] == "No server is frozen."
