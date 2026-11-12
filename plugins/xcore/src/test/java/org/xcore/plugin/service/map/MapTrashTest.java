package org.xcore.plugin.service.map;

import arc.files.Fi;
import mindustry.Vars;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xcore.plugin.config.TomlSecretsConfig;
import org.xcore.plugin.map.domain.MapContentHash;
import org.xcore.plugin.model.MapDiscordUpload;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class MapTrashTest {
    private static final long DAY = 86_400_000L;

    private Fi previous;
    private Fi maps;
    private final AtomicLong now = new AtomicLong(1_791_800_000_000L);
    private final TomlSecretsConfig secrets = new TomlSecretsConfig();
    private MapTrash trash;

    @BeforeEach
    void setUp(@TempDir Path directory) {
        previous = Vars.customMapDirectory;
        maps = new Fi(directory.toFile());
        Vars.customMapDirectory = maps;
        trash = new MapTrash(secrets, now::get);
    }

    @AfterEach
    void tearDown() {
        Vars.customMapDirectory = previous;
    }

    private Fi map(String name, byte... bytes) {
        Fi file = maps.child(name);
        file.writeBytes(bytes, false);
        return file;
    }

    @Test
    void keepsTheFileWithWhatItWasAndItsAttribution() {
        var upload = new MapDiscordUpload("1", "Reviewer", 5, "2", "Creator");
        var entry = trash.keep(map("asteroid C-843 (1).msav", (byte) 1, (byte) 2), MapTrash.Reason.removed,
                "asteroid C-843", "andrpearl", upload).orElseThrow();

        assertThat(entry.trashId()).isEqualTo("1791800000000-" + MapContentHash.ofContent(new byte[] {1, 2}).asHex().substring(0, 8)
                + "-asteroid_C-843__1_.msav");
        assertThat(MapTrash.validId(entry.trashId())).isTrue();
        assertThat(entry.fileName()).isEqualTo("asteroid C-843 (1).msav");
        assertThat(trash.list()).extracting(MapTrash.Entry::trashId).containsExactly(entry.trashId());
        var found = trash.find(entry.trashId()).orElseThrow();
        assertThat(found.upload().creatorName).isEqualTo("Creator");
        assertThat(trash.bytes(found)).hasValueSatisfying(bytes -> assertThat(bytes).containsExactly(1, 2));
        // The engine loads *.msav files in the maps directory only; nothing kept is offered as a map.
        assertThat(maps.list()).extracting(Fi::name).doesNotContain(entry.trashId() + ".msav");
    }

    @Test
    void aMissingFileKeepsNothing() {
        assertThat(trash.keep(maps.child("gone.msav"), MapTrash.Reason.removed, null, null, null)).isEmpty();
        assertThat(trash.list()).isEmpty();
    }

    @Test
    void aDamagedCopyIsNotHandedBack() {
        var entry = trash.keep(map("a.msav", (byte) 1), MapTrash.Reason.replaced, null, null, null).orElseThrow();
        maps.child(".trash").child(entry.trashId() + ".msav.kept").writeBytes(new byte[] {9}, false);
        assertThat(trash.bytes(entry)).isEmpty();
    }

    @Test
    void oldAndSurplusEntriesArePruned() {
        secrets.maps.trash.retentionDays = 30;
        secrets.maps.trash.maxEntries = 2;
        var old = trash.keep(map("old.msav", (byte) 1), MapTrash.Reason.removed, null, null, null).orElseThrow();
        now.addAndGet(31 * DAY);
        var a = trash.keep(map("a.msav", (byte) 2), MapTrash.Reason.removed, null, null, null).orElseThrow();
        now.addAndGet(1);
        var b = trash.keep(map("b.msav", (byte) 3), MapTrash.Reason.removed, null, null, null).orElseThrow();
        now.addAndGet(1);
        var c = trash.keep(map("c.msav", (byte) 4), MapTrash.Reason.removed, null, null, null).orElseThrow();

        assertThat(trash.list()).extracting(MapTrash.Entry::trashId).containsExactly(c.trashId(), b.trashId());
        assertThat(trash.find(old.trashId())).isEmpty();
        assertThat(trash.find(a.trashId())).isEmpty();
    }

    @Test
    void idsThatCouldLeaveTheDirectoryAreRefused() {
        assertThat(MapTrash.validId("../maps/arena.msav")).isFalse();
        assertThat(MapTrash.validId("..")).isFalse();
        assertThat(trash.find("../../etc/passwd")).isEmpty();
    }
}
