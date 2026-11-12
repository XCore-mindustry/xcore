package org.xcore.plugin.map.domain;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xcore.plugin.model.MapData;
import org.xcore.plugin.model.MapDiscordUpload;
import org.xcore.plugin.model.MapUploadRecord;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MapKeysTest {

    static MapUploadRecord upload(String server, String fileName, String threadId) {
        var row = new MapUploadRecord();
        row.server = server;
        row.fileName = fileName;
        if (threadId != null || server.startsWith("attributed")) {
            row.upload = new MapDiscordUpload();
            row.upload.threadId = threadId;
        }
        return row;
    }

    static MapData record(String fileName, String mode) {
        var data = new MapData("Arena", fileName, "someone", mode);
        data.id = new ObjectId();
        return data;
    }

    @Test
    @DisplayName("a file installed from one post links to it; a file from two posts links to none")
    void linksKeepOnlyUnambiguousPosts() {
        var links = MapKeys.links(List.of(
                upload("pvp", "Arena.msav", "100"),
                upload("survival", "arena.msav", " 100 "),
                upload("pvp", "clash.msav", "200"),
                upload("survival", "clash.msav", "300"),
                upload("attack", "manual.msav", null),
                upload("attributed-only", "blank.msav", "  ")));

        assertThat(links.posts()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of("arena.msav", "100"));
        assertThat(links.ambiguous()).containsExactly("clash.msav");
    }

    @Test
    @DisplayName("revisions of one post, on two servers and in two modes, are one map; others stay apart")
    void groupsRecordsByPostThenFile() {
        var links = MapKeys.links(List.of(
                upload("pvp", "arena.msav", "100"),
                upload("survival", "arena-v2.msav", "100"),
                upload("pvp", "clash.msav", "200"),
                upload("survival", "clash.msav", "300")));
        var pvp = record("arena.msav", "pvp");
        var survival = record("arena-v2.msav", "survival");
        var renamed = record("Arena-V2.msav", "attack");
        var clash = record("clash.msav", "pvp");
        var desert = record("desert.msav", "pvp");
        var legacy = record("Unknown", "pvp");
        var nothing = new MapData("Ghost", "", "someone", "pvp");

        var groups = MapKeys.group(List.of(pvp, survival, renamed, clash, desert, legacy, nothing), links);

        assertThat(groups.keySet()).containsExactly(
                "post:100", "file:clash.msav", "file:desert.msav", "record:" + legacy.id.toHexString());
        assertThat(groups.get("post:100")).containsExactly(pvp, survival, renamed);
        assertThat(groups.get("file:clash.msav")).containsExactly(clash);
    }

    @Test
    @DisplayName("the entity of a post names the post and every file of the map")
    void consolidateFillsTheKeyedFields() {
        var first = record("arena.msav", "pvp");
        first.like = 2;
        var second = record("Arena-V2.msav", "survival");
        second.like = 3;

        MapEntity entity = new MapDataConsolidator().consolidate("post:100", List.of(first, second));

        assertThat(entity.mapKey).isEqualTo("post:100");
        assertThat(entity.threadId).isEqualTo("100");
        assertThat(entity.fileNames).containsExactly("Arena-V2.msav", "arena.msav");
        assertThat(entity.fileNamesLower).containsExactly("arena-v2.msav", "arena.msav");
        assertThat(entity.likes).isEqualTo(5);

        MapEntity file = new MapDataConsolidator().consolidate("file:desert.msav", List.of(record("desert.msav", "pvp")));
        assertThat(file.threadId).isNull();
        assertThat(file.fileNamesLower).containsExactly("desert.msav");
    }
}
