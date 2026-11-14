package org.xcore.plugin.service.stats;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xcore.plugin.service.stats.ParticipantOutcomes.Outcome;
import org.xcore.plugin.service.stats.ParticipantOutcomes.Result;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerTotalsTest {

    @Test
    @DisplayName("tower defence keeps its waves and Red vs Blue its wins, apart from survival")
    void towerDefenceAndRvsbFoldIntoTheirOwnCards() {
        var totals = PlayerTotals.empty("uuid");
        PlayerTotals.apply(totals, game("TOWER_DEFENSE", 30, 1), result(Outcome.LOSS));
        PlayerTotals.apply(totals, game("TOWER_DEFENSE", 10, 2), result(Outcome.LOSS));
        PlayerTotals.apply(totals, game("RVSB", 2, 3), result(Outcome.WIN));

        var overview = PlayerTotalsView.overview(totals);

        assertThat(overview.towerDefense().gamesPlayed()).isEqualTo(2);
        assertThat(overview.towerDefense().bestWave()).isEqualTo(30);
        assertThat(overview.towerDefense().averageWave()).isEqualTo(20);
        assertThat(overview.rvsb().gamesWon()).isEqualTo(1);
        assertThat(overview.rvsb().bestWave()).isZero();
        assertThat(overview.survival().hasData()).isFalse();
        assertThat(overview.otherGamesPlayed()).isZero();
    }

    private static Document game(String category, int wave, long endedAt) {
        return new Document("_id", new ObjectId())
                .append("stats_category", category)
                .append("waves_reached", wave)
                .append("started_at", 0L)
                .append("ended_at", endedAt);
    }

    private static Result result(Outcome outcome) {
        return new Result(outcome, "completed", 60_000, 0, 0, null);
    }
}
