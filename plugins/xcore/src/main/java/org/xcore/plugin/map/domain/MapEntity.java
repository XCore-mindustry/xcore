package org.xcore.plugin.map.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.xcore.plugin.model.ModelData;

import java.util.HashMap;
import java.util.Map;

/**
 * Domain entity representing a persistent Map across all gamemodes.
 * Decouples content-addressed physical identity from mutable display aliases and modes.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class MapEntity extends ModelData {

    /**
     * The map this entity is: {@code post:<thread id>} for a reviewed map, {@code file:<name>}
     * for a file no review installed, {@code record:<id>} for a legacy record without a file name.
     * Unique; see {@link MapKeys}.
     */
    @BsonProperty("map_key")
    @Builder.Default
    public String mapKey = null;

    /** The forum post of a reviewed map. */
    @BsonProperty("thread_id")
    @Builder.Default
    public String threadId = null;

    /** Every file name the map's statistics records are stored under, as stored. */
    @BsonProperty("file_names")
    @Builder.Default
    public java.util.List<String> fileNames = new java.util.ArrayList<>();

    /** {@link #fileNames} in lower case, for finding the map of an installed file. */
    @BsonProperty("file_names_lower")
    @Builder.Default
    public java.util.List<String> fileNamesLower = new java.util.ArrayList<>();

    /** SHA-256 digest of the newest known file bytes. Not an identity: every revision differs. */
    @BsonProperty("content_hash")
    @Builder.Default
    public String contentHash = null;

    /** Canonical slug (author/name): a display alias only, never used to group maps. */
    @BsonProperty("slug")
    @Builder.Default
    public String slug = null;

    @Builder.Default
    public String name = "Unknown";

    @Builder.Default
    public String author = "Unknown";

    @BsonProperty("discord_upload")
    public org.xcore.plugin.model.MapDiscordUpload discordUpload;

    @BsonProperty("file_name")
    @Builder.Default
    public String fileName = "Unknown";

    @Builder.Default
    public int width = 0;

    @Builder.Default
    public int height = 0;

    // --- Global Ratings & Popularity (across ALL gamemodes) ---
    @Builder.Default
    public int likes = 0;

    @Builder.Default
    public int dislikes = 0;

    @Builder.Default
    public int reputation = 0;

    @Builder.Default
    public double popularity = 0.0;

    @Builder.Default
    public double interest = 0.0;

    @BsonProperty("total_play_count")
    @Builder.Default
    public long totalPlayCount = 0L;

    @BsonProperty("last_played_at")
    @Builder.Default
    public long lastPlayedAt = 0L;

    /** Gamemode-partitioned play durations and telemetry matrix. */
    @BsonProperty("gamemode_stats")
    @Builder.Default
    public Map<String, GamemodeMetrics> gamemodeStats = new HashMap<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GamemodeMetrics {
        @Builder.Default
        public long plays = 0L;

        @Builder.Default
        public long wins = 0L;

        @BsonProperty("minimum_duration_ms")
        @Builder.Default
        public long minimumDurationMs = 0L;

        @BsonProperty("average_duration_ms")
        @Builder.Default
        public long averageDurationMs = 0L;

        @BsonProperty("maximum_duration_ms")
        @Builder.Default
        public long maximumDurationMs = 0L;
    }
}
