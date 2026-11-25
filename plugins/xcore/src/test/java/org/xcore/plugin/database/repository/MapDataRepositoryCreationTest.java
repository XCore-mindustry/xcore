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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

class MapDataRepositoryCreationTest {
    /** Two threads of its own, whatever the common pool's size on this machine. */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    @Test
    void concurrentCallsForOneMapCreateOneRecord() throws Exception {
        var repository = new SlowLookupRepository();

        var first = CompletableFuture.supplyAsync(() -> repository.findOrCreate("Arena", "arena.msav", "Author", "pvp"), POOL);
        var second = CompletableFuture.supplyAsync(() -> repository.findOrCreate("Arena", "arena.msav", "Author", "pvp"), POOL);

        assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(second.get(5, TimeUnit.SECONDS));
        assertThat(repository.stored).hasSize(1);
    }

    @Test
    void aMapWithoutAFileNamedTwoWaysGetsOneRecord() throws Exception {
        // MatchStartService passes no file for such a map, MapService its name; both reach the
        // record by name and author.
        var repository = new SlowLookupRepository();

        var withoutFile = CompletableFuture.supplyAsync(() -> repository.findOrCreate("Arena", null, "Author", "pvp"), POOL);
        var named = CompletableFuture.supplyAsync(() -> repository.findOrCreate("Arena", "Arena", "Author", "pvp"), POOL);

        assertThat(withoutFile.get(5, TimeUnit.SECONDS)).isSameAs(named.get(5, TimeUnit.SECONDS));
        assertThat(repository.stored).hasSize(1);
    }

    /**
     * A repository whose lookup waits a moment for a second lookup to start, which makes two
     * unserialized calls both miss and both insert. Like the real one, it finds a record by
     * file, then by name and author.
     */
    private static final class SlowLookupRepository extends MapDataRepository {
        final List<MapData> stored = new CopyOnWriteArrayList<>();
        private final CountDownLatch looking = new CountDownLatch(2);

        SlowLookupRepository() {
            super(mock(MongoDatabase.class, RETURNS_DEEP_STUBS), new TomlSecretsConfig());
        }

        @Override
        public Optional<MapData> findByFileName(String fileName, String gameMode) {
            return stored.stream()
                    .filter(map -> Objects.equals(map.fileName, fileName) && Objects.equals(map.gameMode, gameMode))
                    .findFirst();
        }

        @Override
        public Optional<MapData> find(String name, String author, String gameMode, String fileName) {
            Optional<MapData> found = stored.stream()
                    .filter(map -> Objects.equals(map.name, name) && Objects.equals(map.author, author)
                            && Objects.equals(map.gameMode, gameMode))
                    .findFirst();
            // The last lookup before the insert answers, then waits for a second call to look up
            // too: unserialized, both have missed before either saves.
            looking.countDown();
            try {
                looking.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return found;
        }

        @Override
        public boolean save(MapData data) {
            stored.add(data);
            return true;
        }
    }
}
