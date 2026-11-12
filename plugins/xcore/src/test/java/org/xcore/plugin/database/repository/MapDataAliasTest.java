package org.xcore.plugin.database.repository;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.xcore.plugin.config.TomlSecretsConfig;
import org.xcore.plugin.model.MapData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Two installed files with the same map name and author keep separate statistics. */
@SuppressWarnings("unchecked")
class MapDataAliasTest {

    private static MapData record(String fileName, int likes) {
        var data = new MapData("asteroid C-843", fileName, "andrpearl", "pvp");
        data.like = likes;
        return data;
    }

    private static Predicate<String> installed(String... files) {
        return Set.of(files)::contains;
    }

    @Test
    void aRecordStaysWithItsOwnFileWhileThatFileIsInstalled() {
        var owned = record("asteroid_-843.msav", 12);
        assertThat(MapDataRepository.aliasAllowed(owned, "asteroid_-843_1.msav",
                installed("asteroid_-843.msav", "asteroid_-843_1.msav"))).isFalse();
        assertThat(MapDataRepository.aliasAllowed(owned, "ASTEROID_-843.msav", installed("asteroid_-843.msav")))
                .isTrue();
    }

    @Test
    void aRenamedFileStillFindsTheRecordOfItsOldName() {
        var renamed = record("asteroid_old.msav", 12);
        assertThat(MapDataRepository.aliasAllowed(renamed, "asteroid.msav", installed("asteroid.msav"))).isTrue();
    }

    @Test
    void legacyRecordsWithoutAFileNameAreSharedAsBefore() {
        assertThat(MapDataRepository.aliasAllowed(record(null, 1), "a.msav", installed("a.msav"))).isTrue();
        assertThat(MapDataRepository.aliasAllowed(record("", 1), "a.msav", installed("a.msav"))).isTrue();
        assertThat(MapDataRepository.aliasAllowed(record("Unknown", 1), "a.msav", installed("Unknown"))).isTrue();
    }

    @Test
    void resolvePrefersTheFileItselfThenAnUnownedRecordOfTheSameMap() {
        var first = record("asteroid_-843.msav", 12);
        var gone = record("asteroid_old.msav", 3);
        var records = List.of(first, gone);
        var both = installed("asteroid_-843.msav", "asteroid_-843_1.msav");

        assertThat(MapDataRepository.resolve(records, "asteroid_-843.msav", "asteroid C-843", "andrpearl", "pvp", both))
                .isSameAs(first);
        assertThat(MapDataRepository.resolve(records, "asteroid_-843_1.msav", "asteroid C-843", "andrpearl", "pvp",
                both)).isSameAs(gone);
        assertThat(MapDataRepository.resolve(List.of(first), "asteroid_-843_1.msav", "asteroid C-843", "andrpearl",
                "pvp", both)).isNull();
        assertThat(MapDataRepository.resolve(records, "asteroid_-843.msav", "asteroid C-843", "andrpearl", "survival",
                both)).isNull();
    }

    @Test
    void findOrCreateGivesTheSecondInstalledFileItsOwnRecord() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<MapData> collection = mock(MongoCollection.class);
        when(database.getCollection("maps", MapData.class)).thenReturn(collection);
        var stored = new ArrayList<MapData>(List.of(record("asteroid_-843.msav", 12)));
        // Nothing is stored under the new file name; every title query finds the first file's record.
        FindIterable<MapData> none = query(List.of());
        FindIterable<MapData> titled = query(stored);
        when(collection.find(any(Bson.class))).thenReturn(none, none, titled, titled);
        var repository = new MapDataRepository(database, new TomlSecretsConfig());
        repository.installedFiles(installed("asteroid_-843.msav", "asteroid_-843_1.msav"));

        MapData data = repository.findOrCreate("asteroid C-843", "asteroid_-843_1.msav", "andrpearl", "pvp");

        assertThat(data.fileName).isEqualTo("asteroid_-843_1.msav");
        assertThat(data.like).isZero();
        verify(collection).insertOne(data);
    }

    private static FindIterable<MapData> query(List<MapData> result) {
        FindIterable<MapData> query = mock(FindIterable.class);
        when(query.limit(anyInt())).thenReturn(query);
        when(query.first()).thenReturn(result.isEmpty() ? null : result.getFirst());
        when(query.into(any(Collection.class))).thenAnswer(call -> {
            Collection<MapData> target = call.getArgument(0);
            target.addAll(result);
            return target;
        });
        return query;
    }

    @Test
    void installedFilesComeFromTheLastReloadNotTheDisk(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) {
        var previous = mindustry.Vars.customMapDirectory;
        try {
            var directory = new arc.files.Fi(folder.toFile());
            mindustry.Vars.customMapDirectory = directory;
            directory.child("on-disk.msav").writeBytes(new byte[] {1}, false);
            var repository = new MapDataRepository(mock(MongoDatabase.class, RETURNS_DEEP_STUBS), new TomlSecretsConfig());

            // Before the engine's first load, the folder is all there is.
            assertThat(repository.customMapInstalled("on-disk.msav")).isTrue();

            repository.installedCustomMaps(List.of("loaded.msav"));
            assertThat(repository.customMapInstalled("loaded.msav")).isTrue();
            assertThat(repository.customMapInstalled("on-disk.msav")).isFalse();
            assertThat(repository.customMapInstalled(null)).isFalse();
        } finally {
            mindustry.Vars.customMapDirectory = previous;
        }
    }
}
