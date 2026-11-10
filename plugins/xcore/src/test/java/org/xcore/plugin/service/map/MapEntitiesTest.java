package org.xcore.plugin.service.map;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xcore.plugin.map.domain.MapEntity;
import org.xcore.plugin.model.MapData;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MapEntitiesTest {

    static MapData record(String fileName, String mode, int like, int dislike, int reputation) {
        var data = new MapData("Arena", fileName, "someone", mode);
        data.id = new ObjectId();
        data.like = like;
        data.dislike = dislike;
        data.reputation = reputation;
        return data;
    }

    static MapEntity map(String... files) {
        var entity = new MapEntity();
        entity.fileNames = List.of(files);
        entity.fileNamesLower = java.util.Arrays.stream(files).map(f -> f.toLowerCase(java.util.Locale.ROOT)).toList();
        return entity;
    }

    @Test
    @DisplayName("a map's totals add up every record of every file it links, in every mode")
    void totalsSumTheWholeMap() {
        var arena = map("arena.msav", "Arena-V2.msav");
        var desert = map("desert.msav");
        var records = List.of(
                record("arena.msav", "pvp", 2, 1, 3),
                record("arena.msav", "survival", 1, 0, 1),
                record("Arena-V2.msav", "pvp", 4, 2, 5),
                record("desert.msav", "pvp", 7, 0, 7),
                record("unrelated.msav", "pvp", 100, 0, 100));

        var totals = MapEntities.totals(List.of("arena-v2.msav", "desert.msav", "nowhere.msav"),
                List.of(arena, desert), records);

        assertThat(totals).containsOnlyKeys("arena-v2.msav", "desert.msav");
        assertThat(totals.get("arena-v2.msav")).isEqualTo(new MapEntities.Totals(7, 3, 9));
        assertThat(totals.get("desert.msav")).isEqualTo(new MapEntities.Totals(7, 0, 7));
    }

    @Test
    @DisplayName("a listed record shows its map's ratings but keeps its own id and mode numbers")
    void withTotalsKeepsTheRecordItself() {
        var own = record("arena.msav", "pvp", 2, 1, 3);
        own.popularity = 4.5;
        own.interest = 0.7;
        var totals = new MapEntities.Totals(9, 4, 11);

        MapData shown = MapEntities.withTotals(own, totals, "arena.msav", "pvp");

        assertThat(shown).isNotSameAs(own);
        assertThat(shown.id).isEqualTo(own.id);
        assertThat(shown.like).isEqualTo(9);
        assertThat(shown.dislike).isEqualTo(4);
        assertThat(shown.reputation).isEqualTo(11);
        assertThat(shown.popularity).isEqualTo(4.5);
        assertThat(shown.interest).isEqualTo(0.7);
        assertThat(own.like).isEqualTo(2);

        assertThat(MapEntities.withTotals(own, null, "arena.msav", "pvp")).isSameAs(own);
        MapData fresh = MapEntities.withTotals(null, totals, "arena-v2.msav", "pvp");
        assertThat(fresh.fileName).isEqualTo("arena-v2.msav");
        assertThat(fresh.gameMode).isEqualTo("pvp");
        assertThat(fresh.like).isEqualTo(9);
    }
}
