package org.xcore.plugin.database.repository;

import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.Test;
import org.xcore.plugin.config.TomlSecretsConfig;
import org.xcore.plugin.model.MapData;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

class MapDataRepositoryCreationTest {

    @Test
    void concurrentCallsForOneMapCreateOneRecord() throws Exception {
        var repository = new SlowLookupRepository();

        var first = CompletableFuture.supplyAsync(() -> repository.findOrCreate("Arena", "arena.msav", "Author", "pvp"));
        var second = CompletableFuture.supplyAsync(() -> repository.findOrCreate("Arena", "arena.msav", "Author", "pvp"));

        assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(second.get(5, TimeUnit.SECONDS));
        assertThat(repository.stored).hasSize(1);
    }

    @Test
    void differentMapsDoNotWaitForEachOther() throws Exception {
        var repository = new SlowLookupRepository();

        var arena = CompletableFuture.supplyAsync(() -> repository.findOrCreate("Arena", "arena.msav", "Author", "pvp"));
        var desert = CompletableFuture.supplyAsync(() -> repository.findOrCreate("Desert", "desert.msav", "Author", "pvp"));

        assertThat(arena.get(5, TimeUnit.SECONDS).fileName).isEqualTo("arena.msav");
        assertThat(desert.get(5, TimeUnit.SECONDS).fileName).isEqualTo("desert.msav");
        assertThat(repository.bothLookedUpAtOnce).as("two maps are looked up side by side").isTrue();
    }

    /**
     * A repository whose lookup waits a moment for a second lookup to start, which makes two
     * unserialized calls both miss and both insert.
     */
    private static final class SlowLookupRepository extends MapDataRepository {
        final List<MapData> stored = new CopyOnWriteArrayList<>();
        private final CountDownLatch looking = new CountDownLatch(2);
        volatile boolean bothLookedUpAtOnce;

        SlowLookupRepository() {
            super(mock(MongoDatabase.class, RETURNS_DEEP_STUBS), new TomlSecretsConfig());
        }

        @Override
        public Optional<MapData> findByFileName(String fileName, String gameMode) {
            looking.countDown();
            try {
                if (looking.await(300, TimeUnit.MILLISECONDS)) bothLookedUpAtOnce = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return stored.stream()
                    .filter(map -> Objects.equals(map.fileName, fileName) && Objects.equals(map.gameMode, gameMode))
                    .findFirst();
        }

        @Override
        public Optional<MapData> find(String name, String author, String gameMode, String fileName) {
            return Optional.empty();
        }

        @Override
        public boolean save(MapData data) {
            stored.add(data);
            return true;
        }
    }
}
