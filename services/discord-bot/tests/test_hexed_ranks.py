from xcore_discord_bot import hexed_ranks


def test_points_below_the_first_threshold_are_the_first_rank() -> None:
    assert hexed_ranks.rank_for(-5) == 0


def test_each_threshold_reaches_its_rank() -> None:
    for rank, needed in enumerate(hexed_ranks.POINTS):
        assert hexed_ranks.rank_for(needed) == rank
