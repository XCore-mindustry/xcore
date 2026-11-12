"""What installing a file would do to the maps already on a server."""

from __future__ import annotations

from xcore_discord_bot.map_targets import (
    Incoming,
    Match,
    Outcome,
    duplicate_groups,
    find_candidates,
    outcome,
    replacement_targets,
    title_key,
)

SHA_A = "a" * 64
SHA_B = "b" * 64
SHA_NEW = "c" * 64


def entry(file_name: str, name: str, author: str, sha: str = "") -> dict[str, str]:
    return {"file_name": file_name, "name": name, "author": author, "content_hash": sha}


ASTEROID = entry("asteroid_-843.msav", "asteroid C-843", "andrpearl", SHA_A)
STAR_WARS = entry("starwars.msav", "Star  Wars", "someone", SHA_B)


def test_title_ignores_colors_case_and_spacing_and_unknown_authors() -> None:
    assert title_key("[accent]Star  Wars", "Someone") == title_key(
        "star wars", "someone"
    )
    assert title_key("Arena", "Unknown") == title_key("Arena", "")
    assert title_key("", "andrpearl") is None


def test_nothing_installed_like_it_is_a_new_map() -> None:
    incoming = Incoming("arena.msav", SHA_B, "Arena", "anuke")
    assert find_candidates([ASTEROID], incoming) == []
    assert outcome([], "arena.msav") == Outcome.NEW


def test_the_same_bytes_under_the_same_name_are_already_installed() -> None:
    candidates = find_candidates(
        [ASTEROID], Incoming("asteroid_-843.msav", SHA_A, "asteroid C-843", "andrpearl")
    )
    assert candidates[0].reasons == {
        Match.SAME_BYTES,
        Match.SAME_FILE,
        Match.SAME_TITLE,
    }
    assert outcome(candidates, "asteroid_-843.msav") == Outcome.SAME


def test_a_new_version_under_the_same_name_updates_it() -> None:
    candidates = find_candidates(
        [ASTEROID],
        Incoming("asteroid_-843.msav", SHA_NEW, "asteroid C-843", "andrpearl"),
    )
    assert outcome(candidates, "asteroid_-843.msav") == Outcome.UPDATE


def test_a_renamed_new_version_asks_and_offers_the_installed_file() -> None:
    # The case that started this: "asteroid C-843 (1).msav" installed next to the original.
    incoming = Incoming("asteroid_-843_1.msav", SHA_NEW, "asteroid C-843", "andrpearl")
    candidates = find_candidates([ASTEROID, STAR_WARS], incoming)
    assert [c.file_name for c in candidates] == ["asteroid_-843.msav"]
    assert candidates[0].reasons == {Match.SAME_TITLE}
    assert outcome(candidates, incoming.file_name) == Outcome.CONFIRM
    assert replacement_targets(candidates, incoming.file_name) == ["asteroid_-843.msav"]


def test_overwriting_a_different_map_with_the_same_file_name_asks() -> None:
    incoming = Incoming("starwars.msav", SHA_A, "Star Wars Remastered", "other")
    candidates = find_candidates([STAR_WARS], incoming)
    assert candidates[0].overwritten_foreign
    assert outcome(candidates, "starwars.msav") == Outcome.CONFIRM
    # Nothing else to replace: the only choices are to overwrite or cancel.
    assert replacement_targets(candidates, "starwars.msav") == []


def test_an_unreadable_file_is_still_matched_by_its_name() -> None:
    candidates = find_candidates([STAR_WARS], Incoming("STARWARS.msav"))
    assert [c.reasons for c in candidates] == [{Match.SAME_FILE}]
    assert outcome(candidates, "STARWARS.msav") == Outcome.CONFIRM


def test_identical_bytes_come_first() -> None:
    copy = entry("copy.msav", "Something else", "x", SHA_A)
    incoming = Incoming("new.msav", SHA_A, "asteroid C-843", "andrpearl")
    names = [c.file_name for c in find_candidates([ASTEROID, copy], incoming)]
    assert names == ["asteroid_-843.msav", "copy.msav"]


def test_duplicate_groups_by_bytes_and_by_title() -> None:
    renamed = entry("asteroid_-843_1.msav", "asteroid C-843", "andrpearl", SHA_NEW)
    copy = entry("copy.msav", "Copy", "x", SHA_NEW)
    groups = duplicate_groups([ASTEROID, renamed, copy, STAR_WARS])
    assert [(match, [i["file_name"] for i in items]) for match, items in groups] == [
        (Match.SAME_BYTES, ["asteroid_-843_1.msav", "copy.msav"]),
        (Match.SAME_TITLE, ["asteroid_-843.msav", "asteroid_-843_1.msav"]),
    ]


def test_a_group_that_is_both_the_same_bytes_and_title_is_listed_once() -> None:
    twin = entry("twin.msav", "asteroid C-843", "andrpearl", SHA_A)
    groups = duplicate_groups([ASTEROID, twin])
    assert [match for match, _ in groups] == [Match.SAME_BYTES]
