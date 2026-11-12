package org.xcore.plugin.database.migration;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.xcore.plugin.map.domain.MapEntity;
import org.xcore.plugin.model.MapData;
import org.xcore.plugin.model.MapDiscordUpload;
import org.xcore.plugin.model.MapUploadRecord;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class V9__RebuildMapEntitiesByMapTest {

    @SuppressWarnings("unchecked")
    static <T> MongoCollection<T> collection(List<T> rows) {
        MongoCollection<T> collection = mock(MongoCollection.class);
        FindIterable<T> found = mock(FindIterable.class);
        when(collection.find()).thenReturn(found);
        when(found.into(any())).thenAnswer(inv -> {
            Collection<T> target = inv.getArgument(0);
            target.addAll(rows);
            return target;
        });
        return collection;
    }

    static MapData record(String fileName, String mode, int like) {
        var data = new MapData("Arena", fileName, "someone", mode);
        data.id = new ObjectId();
        data.like = like;
        return data;
    }

    static MapUploadRecord upload(String server, String fileName, String threadId) {
        var row = new MapUploadRecord();
        row.server = server;
        row.fileName = fileName;
        row.upload = new MapDiscordUpload();
        row.upload.threadId = threadId;
        return row;
    }

    @Test
    @DisplayName("is version 9")
    void metadata() {
        var migration = new V9__RebuildMapEntitiesByMap();
        assertThat(migration.getVersion()).isEqualTo(9);
        assertThat(migration.getDescription()).containsIgnoringCase("map entities");
    }

    @Test
    @DisplayName("drops the slug documents of v3 and writes one entity per post or file")
    @SuppressWarnings("unchecked")
    void rebuildsByPost() {
        MongoDatabase database = mock(MongoDatabase.class);
        var maps = collection(List.of(
                record("arena.msav", "pvp", 2),
                record("arena-v2.msav", "survival", 3),
                record("desert.msav", "pvp", 7)));
        var uploads = collection(List.of(
                upload("pvp", "arena.msav", "100"),
                upload("survival", "arena-v2.msav", "100")));
        MongoCollection<MapEntity> entities = mock(MongoCollection.class);
        when(database.getCollection(eq("maps"), eq(MapData.class))).thenReturn(maps);
        when(database.getCollection(eq("map_uploads"), eq(MapUploadRecord.class))).thenReturn(uploads);
        when(database.getCollection(eq("map_entities"), eq(MapEntity.class))).thenReturn(entities);

        new V9__RebuildMapEntitiesByMap().up(database);

        var order = inOrder(entities);
        order.verify(entities).deleteMany(Filters.exists("map_key", false));
        order.verify(entities).createIndex(eq(new Document("map_key", 1)), any());
        var saved = ArgumentCaptor.forClass(MapEntity.class);
        order.verify(entities).replaceOne(eq(Filters.eq("map_key", "post:100")), saved.capture(), any(ReplaceOptions.class));
        order.verify(entities).replaceOne(eq(Filters.eq("map_key", "file:desert.msav")), saved.capture(), any(ReplaceOptions.class));
        order.verify(entities).deleteMany(any(Bson.class));

        MapEntity arena = saved.getAllValues().getFirst();
        assertThat(arena.threadId).isEqualTo("100");
        assertThat(arena.fileNamesLower).containsExactly("arena-v2.msav", "arena.msav");
        assertThat(arena.likes).isEqualTo(5);
        var cleanup = ArgumentCaptor.forClass(Bson.class);
        verify(entities, times(2)).deleteMany(cleanup.capture());
        // Only maps missing from the snapshot and last written before it: another server's
        // newer map survives.
        assertThat(cleanup.getAllValues().get(1).toBsonDocument().toJson())
                .contains("\"$nin\": [\"post:100\", \"file:desert.msav\"]")
                .contains("\"updated_at\": {\"$lt\": " + arena.editModelTime + "}");
    }
}
