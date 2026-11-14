package org.xcore.plugin.model;

import java.util.List;

public record PlayerStatsOverview(
        AggregatedPlayerStats overall,
        ModeStatsSummary pvp,
        ModeStatsSummary survival,
        ModeStatsSummary hexed,
        String status,
        boolean updating,
        /** Whether left, streaks, play time and recent matches were recorded; the legacy reader has none of them. */
        boolean detailed,
        long left,
        long streak,
        List<RecentMatch> recent,
        /** Time spent in counted matches of every mode. */
        long playMs,
        ModeStatsSummary towerDefense,
        ModeStatsSummary rvsb
) {
    /**
     * @param category the stats category (PVP, SURVIVAL, HEXED, TOWER_DEFENSE, RVSB, EVENT)
     * @param map the map's name, {@code null} when it is unknown
     */
    public record RecentMatch(String category, String outcome, long endedAt, String map) {}
    public PlayerStatsOverview(AggregatedPlayerStats overall, ModeStatsSummary pvp, ModeStatsSummary survival,
                               ModeStatsSummary hexed, String status, boolean updating, boolean detailed,
                               long left, long streak, List<RecentMatch> recent, long playMs) {
        this(overall, pvp, survival, hexed, status, updating, detailed, left, streak, recent, playMs,
                ModeStatsSummary.EMPTY, ModeStatsSummary.EMPTY);
    }
    public PlayerStatsOverview(AggregatedPlayerStats overall, ModeStatsSummary pvp, ModeStatsSummary survival,
                               ModeStatsSummary hexed, String status, boolean updating, boolean detailed,
                               long left, long streak, List<RecentMatch> recent) {
        this(overall, pvp, survival, hexed, status, updating, detailed, left, streak, recent, 0);
    }
    public PlayerStatsOverview(AggregatedPlayerStats overall, ModeStatsSummary pvp, ModeStatsSummary survival, ModeStatsSummary hexed) {
        this(overall, pvp, survival, hexed, "ready", false, false, 0, 0, List.of());
    }
    public static final PlayerStatsOverview EMPTY = new PlayerStatsOverview(
            AggregatedPlayerStats.EMPTY,
            ModeStatsSummary.EMPTY,
            ModeStatsSummary.EMPTY,
            ModeStatsSummary.EMPTY
    );

    /** Games outside the modes with their own card (events, mostly): what is left of the total once those are taken out. */
    public long otherGamesPlayed() {
        return Math.max(0, overall.gamesPlayed() - pvp.gamesPlayed() - survival.gamesPlayed() - hexed.gamesPlayed()
                - towerDefense.gamesPlayed() - rvsb.gamesPlayed());
    }

    /** Wins in the games counted by {@link #otherGamesPlayed()}. */
    public long otherGamesWon() {
        return Math.max(0, overall.gamesWon() - pvp.gamesWon() - survival.gamesWon() - hexed.gamesWon()
                - towerDefense.gamesWon() - rvsb.gamesWon());
    }

    /** Time in the games counted by {@link #otherGamesPlayed()}. */
    public long otherPlayMs() {
        return Math.max(0, playMs - pvp.playMs() - survival.playMs() - hexed.playMs()
                - towerDefense.playMs() - rvsb.playMs());
    }
}
