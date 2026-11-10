package org.xcore.plugin.database.repository;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
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

class MapEntityProjectionTest {

    @SuppressWarnings("unchecked")
    static <T> FindIterable<T> found(List<T> rows) {
        FindIterable<T> found = mock(FindIterable.class);
        when(found.into(any())).thenAnswer(inv -> {
            Collection<T> target = inv.getArgument(0);
            target.addAll(rows);
            return target;
        });
        return found;
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

    static MapEntity entity(String key, String... files) {
        var entity = new MapEntity();
        entity.mapKey = key;
        entity.fileNames = List.of(files);
        entity.fileNamesLower = List.of(files);
        return entity;
    }

    @Test
    @DisplayName("an upload from a post moves only that file into the post's map, reading only that map")
    @SuppressWarnings("unchecked")
    void updateTouchesOnlyTheChangedMaps() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<MapData> maps = mock(MongoCollection.class);
        MongoCollection<MapUploadRecord> uploads = mock(MongoCollection.class);
        MongoCollection<MapEntity> entities = mock(MongoCollection.class);
        when(database.getCollection(eq("maps"), eq(MapData.class))).thenReturn(maps);
        when(database.getCollection(eq("map_uploads"), eq(MapUploadRecord.class))).thenReturn(uploads);
        when(database.getCollection(eq("map_entities"), eq(MapEntity.class))).thenReturn(entities);
        // arena.msav came from post 100 on pvp; arena-v2.msav, until now a map of its own, has
        // just been installed from the same post on survival. desert.msav is not involved.
        var uploadRows = List.of(upload("pvp", "arena.msav", "100"), upload("survival", "arena-v2.msav", "100"),
                upload("pvp", "desert.msav", null));
        when(uploads.find()).thenAnswer(inv -> found(uploadRows));
        when(entities.find(any(Bson.class))).thenAnswer(inv -> found(List.of(entity("file:arena-v2.msav", "arena-v2.msav"))));
        when(maps.find(any(Bson.class))).thenAnswer(inv -> found(List.of(
                record("arena.msav", "pvp", 2), record("arena-v2.msav", "survival", 3))));

        int looked = MapEntityProjection.update(database, List.of("Arena-V2.msav"));

        assertThat(looked).isEqualTo(2);
        verify(maps, never()).find();
        var query = ArgumentCaptor.forClass(Bson.class);
        verify(maps).find(query.capture());
        assertThat(query.getValue().toBsonDocument().toJson())
                .contains("arena.msav").contains("arena-v2.msav").doesNotContain("desert.msav");
        var saved = ArgumentCaptor.forClass(MapEntity.class);
        verify(entities).replaceOne(eq(Filters.eq("map_key", "post:100")), saved.capture(), any(ReplaceOptions.class));
        assertThat(saved.getValue().fileNamesLower).containsExactly("arena-v2.msav", "arena.msav");
        assertThat(saved.getValue().likes).isEqualTo(5);
        var removed = ArgumentCaptor.forClass(Bson.class);
        verify(entities).deleteOne(removed.capture());
        assertThat(removed.getValue().toBsonDocument().toJson()).contains("file:arena-v2.msav").contains("updated_at");
        verify(entities, never()).deleteMany(any(Bson.class));
    }

    @Test
    @DisplayName("no named file, nothing read")
    void updateWithoutNamesDoesNothing() {
        MongoDatabase database = mock(MongoDatabase.class);
        assertThat(MapEntityProjection.update(database, List.of("Unknown", " "))).isZero();
        verifyNoInteractions(database);
    }
}
