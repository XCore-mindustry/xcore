package org.xcore.plugin.event.transport;

import arc.files.Fi;
import arc.func.Cons;
import arc.struct.StringMap;
import mindustry.Vars;
import mindustry.game.Gamemode;
import mindustry.game.Rules;
import mindustry.maps.Maps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.xcore.plugin.concurrent.Async;
import org.xcore.plugin.concurrent.InlineStorageExecutor;
import org.xcore.plugin.config.TomlSecretsConfig;
import org.xcore.plugin.config.TomlXcoreConfig;
import org.xcore.plugin.database.repository.MapDataRepository;
import org.xcore.plugin.database.repository.MapUploadRepository;
import org.xcore.plugin.map.domain.MapContentHash;
import org.xcore.plugin.model.MapDiscordUpload;
import org.xcore.plugin.service.MapService;
import org.xcore.plugin.service.NetworkService;
import org.xcore.plugin.service.map.MapInspectionService;
import org.xcore.plugin.service.map.MapTrash;
import org.xcore.plugin.service.network.RedisNetworkBackend;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsRemoveRequestV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsRemoveResponseV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsRestoreRequestV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsRestoreResponseV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsTrashListRequestV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsTrashListResponseV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsUploadRequestV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsUploadResponseV1;
import org.xcore.protocol.generated.messages.maps.MapsRemoveResponseV1Status;
import org.xcore.protocol.generated.messages.maps.MapsRestoreResponseV1Status;
import org.xcore.protocol.generated.shared.MapFileSourceV1;
import org.xcore.protocol.generated.shared.MapTrashEntryV1Reason;
import org.xcore.protocol.generated.shared.MapUploadResultV1;
import org.xcore.protocol.generated.shared.MapUploadResultV1Status;
import org.xcore.protocol.generated.runtime.ProtocolPayload;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Conditional writes, and files kept aside instead of deleted (protocol 0.19.0). */
class MapTransportTrashTest {
    private static final byte[] OLD = {1, 2, 3};
    private static final byte[] NEW = {4, 5, 6};

    private Fi previousDirectory;
    private Maps previousMaps;
    private mindustry.core.GameState previousState;
    private Fi maps;
    private final NetworkService network = mock(NetworkService.class);
    private final MapService service = mock(MapService.class);
    private final MapUploadRepository uploads = mock(MapUploadRepository.class);
    private final Maps registry = mock(Maps.class);
    private final Map<Class<?>, Cons<?>> listeners = new HashMap<>();
    private MapTrash trash;

    @BeforeEach
    void setUp(@TempDir Path directory) {
        previousDirectory = Vars.customMapDirectory;
        previousMaps = Vars.maps;
        previousState = Vars.state;
        maps = new Fi(directory.toFile());
        Vars.customMapDirectory = maps;
        Vars.maps = registry;
        Vars.state = new mindustry.core.GameState();
        Vars.state.rules = mock(Rules.class);
        when(Vars.state.rules.mode()).thenReturn(Gamemode.pvp);
        when(service.rebuildIdentityCatalogAsync()).thenReturn(CompletableFuture.completedFuture(null));
        when(service.refreshAttribution()).thenReturn(CompletableFuture.completedFuture(null));
        when(uploads.save(anyString(), any(), any())).thenReturn(true);
        doAnswer(call -> {
            listeners.put(call.getArgument(0), call.getArgument(1));
            return mock(RedisNetworkBackend.Subscription.class);
        }).when(network).subscribe(any(), any());
        var config = new TomlXcoreConfig();
        config.server.name = "mini-pvp";
        trash = new MapTrash(new TomlSecretsConfig());
        new MapTransportHandler(network, config, service, mock(MapDataRepository.class),
                new Async(InlineStorageExecutor.create(), Runnable::run), uploads, mock(MapInspectionService.class), trash)
                .registerListeners();
    }

    @AfterEach
    void tearDown() {
        Vars.customMapDirectory = previousDirectory;
        Vars.maps = previousMaps;
        Vars.state = previousState;
    }

    private static String sha(byte[] bytes) {
        return MapContentHash.ofContent(bytes).asHex();
    }

    @SuppressWarnings("unchecked")
    private <T> void send(Class<T> type, T request) {
        ((Cons<T>) listeners.get(type)).get(request);
    }

    private <T extends ProtocolPayload> T answer(Class<T> type) {
        var captor = ArgumentCaptor.forClass(type);
        verify(network).respond(any(), captor.capture());
        return captor.getValue();
    }

    private mindustry.maps.Map installed(String fileName, byte[] bytes) {
        var file = maps.child(fileName);
        file.writeBytes(bytes, false);
        var map = new mindustry.maps.Map(file, 10, 10, StringMap.of("name", "Arena", "author", "somebody"), true);
        when(service.findMapByFileName(fileName)).thenReturn(map);
        return map;
    }

    /** Uploads {@code NEW} as {@code source}; the download answers at once. */
    private List<MapUploadResultV1> upload(MapFileSourceV1 source) {
        try (var http = org.mockito.Mockito.mockStatic(arc.util.Http.class)) {
            var request = mock(arc.util.Http.HttpRequest.class);
            http.when(() -> arc.util.Http.get(source.url())).thenReturn(request);
            when(request.timeout(org.mockito.ArgumentMatchers.anyInt())).thenReturn(request);
            when(request.error(any())).thenReturn(request);
            doAnswer(call -> {
                var response = mock(arc.util.Http.HttpResponse.class);
                when(response.getResult()).thenReturn(NEW);
                ((arc.func.ConsT<arc.util.Http.HttpResponse, Exception>) call.getArgument(0)).get(response);
                return null;
            }).when(request).submit(any());
            send(MapsUploadRequestV1.class, new MapsUploadRequestV1("mini-pvp", List.of(source)));
        }
        return answer(MapsUploadResponseV1.class).results();
    }

    @Test
    void anUploadOverAFileOtherThanTheExpectedOneChangesNothing() {
        installed("arena.msav", OLD);
        var results = upload(new MapFileSourceV1("https://example/arena", "arena.msav", null, sha(new byte[] {9}), null));
        assertThat(results).extracting(MapUploadResultV1::status).containsExactly(MapUploadResultV1Status.CONFLICT);
        assertThat(maps.child("arena.msav").readBytes()).containsExactly(OLD);
        assertThat(trash.list()).isEmpty();
        verify(uploads, never()).save(anyString(), any(), any());
    }

    @Test
    void anUploadThatExpectsAFreeNameDoesNotOverwrite() {
        installed("arena.msav", OLD);
        var results = upload(new MapFileSourceV1("https://example/arena", "arena.msav", null, null, true));
        assertThat(results).extracting(MapUploadResultV1::status).containsExactly(MapUploadResultV1Status.CONFLICT);
        assertThat(maps.child("arena.msav").readBytes()).containsExactly(OLD);
    }

    @Test
    void aReplacedFileIsKeptAsideAndNamedInTheResult() {
        installed("arena.msav", OLD);
        var results = upload(new MapFileSourceV1("https://example/arena", "arena.msav", null, sha(OLD), null));
        var result = results.getFirst();
        assertThat(result.status()).isEqualTo(MapUploadResultV1Status.INSTALLED);
        assertThat(result.contentHash()).isEqualTo(sha(NEW));
        assertThat(result.replacedContentHash()).isEqualTo(sha(OLD));
        assertThat(maps.child("arena.msav").readBytes()).containsExactly(NEW);
        var kept = trash.find(result.trashId()).orElseThrow();
        assertThat(kept.reason()).isEqualTo(MapTrash.Reason.replaced);
        assertThat(trash.bytes(kept)).hasValueSatisfying(bytes -> assertThat(bytes).containsExactly(OLD));
    }

    @Test
    void overwritingTheMapBeingPlayedKeepsTheGameOnItsLoadedCopy() {
        // The running world was read into memory when it loaded; only the file changes.
        Vars.state.map = installed("arena.msav", OLD);
        var results = upload(new MapFileSourceV1("https://example/arena", "arena.msav", null, null, null));
        assertThat(results.getFirst().status()).isEqualTo(MapUploadResultV1Status.INSTALLED);
        assertThat(Vars.state.map.file.readBytes()).containsExactly(NEW);
        assertThat(trash.find(results.getFirst().trashId())).isPresent();
        verify(registry).reload();
    }

    @Test
    void aRemovalOfAFileOtherThanTheExpectedOneRemovesNothing() {
        installed("starwars.msav", OLD);
        send(MapsRemoveRequestV1.class, new MapsRemoveRequestV1("mini-pvp", "starwars.msav", sha(NEW)));
        var answer = answer(MapsRemoveResponseV1.class);
        assertThat(answer.status()).isEqualTo(MapsRemoveResponseV1Status.CONFLICT);
        assertThat(answer.contentHash()).isEqualTo(sha(OLD));
        verify(registry, never()).removeMap(any());
    }

    @Test
    void aRemovedFileIsKeptAsideWithItsAttribution() {
        var map = installed("starwars.msav", OLD);
        when(service.discordUploadFor(map)).thenReturn(new MapDiscordUpload("1", "Reviewer", 5, "42", "RiTr"));
        send(MapsRemoveRequestV1.class, new MapsRemoveRequestV1("mini-pvp", "starwars.msav", sha(OLD)));
        var answer = answer(MapsRemoveResponseV1.class);
        assertThat(answer.status()).isEqualTo(MapsRemoveResponseV1Status.REMOVED);
        assertThat(answer.result()).startsWith("Successfully removed map Arena");
        var kept = trash.find(answer.trashId()).orElseThrow();
        assertThat(kept.upload().creatorName).isEqualTo("RiTr");
        verify(registry).removeMap(map);

        org.mockito.Mockito.clearInvocations(network);
        send(MapsTrashListRequestV1.class, new MapsTrashListRequestV1("mini-pvp"));
        var listed = answer(MapsTrashListResponseV1.class).entries();
        assertThat(listed).singleElement().satisfies(entry -> {
            assertThat(entry.trashId()).isEqualTo(answer.trashId());
            assertThat(entry.reason()).isEqualTo(MapTrashEntryV1Reason.REMOVED);
            assertThat(entry.name()).isEqualTo("Arena");
        });
    }

    @Test
    void aKeptFileGoesBackWithItsAttribution() {
        var file = maps.child("starwars.msav");
        file.writeBytes(OLD, false);
        var kept = trash.keep(file, MapTrash.Reason.removed, "Star Wars", "someone",
                new MapDiscordUpload("1", "Reviewer", 5, "42", "RiTr")).orElseThrow();
        file.delete();
        var restored = new mindustry.maps.Map(file, 10, 10, StringMap.of("name", "Star Wars", "author", "someone"), true);
        when(service.findMapByFileName("starwars.msav")).thenReturn(restored);

        send(MapsRestoreRequestV1.class, new MapsRestoreRequestV1("mini-pvp", kept.trashId(), null));

        var answer = answer(MapsRestoreResponseV1.class);
        assertThat(answer.status()).isEqualTo(MapsRestoreResponseV1Status.RESTORED);
        assertThat(answer.fileName()).isEqualTo("starwars.msav");
        assertThat(file.readBytes()).containsExactly(OLD);
        var attribution = ArgumentCaptor.forClass(MapDiscordUpload.class);
        verify(uploads).save(eq("starwars.msav"), eq(sha(OLD)), attribution.capture());
        assertThat(attribution.getValue().creatorDiscordId).isEqualTo("42");
        assertThat(trash.find(kept.trashId())).isEmpty();
    }

    @Test
    void aKeptFileDoesNotOverwriteTheFileThatTookItsName() {
        var file = maps.child("starwars.msav");
        file.writeBytes(OLD, false);
        var kept = trash.keep(file, MapTrash.Reason.removed, null, null, null).orElseThrow();
        file.writeBytes(NEW, false);

        send(MapsRestoreRequestV1.class, new MapsRestoreRequestV1("mini-pvp", kept.trashId(), null));

        assertThat(answer(MapsRestoreResponseV1.class).status()).isEqualTo(MapsRestoreResponseV1Status.CONFLICT);
        assertThat(file.readBytes()).containsExactly(NEW);
        assertThat(trash.find(kept.trashId())).isPresent();
    }

    @Test
    void aKeptFileTheServerCannotReadStaysKeptAndLeavesNoFileBehind() {
        var file = maps.child("starwars.msav");
        file.writeBytes(OLD, false);
        var kept = trash.keep(file, MapTrash.Reason.removed, null, null, null).orElseThrow();
        file.delete();
        // The engine skips a file it cannot parse: the reloaded registry does not have it.
        when(service.findMapByFileName("starwars.msav")).thenReturn(null);

        send(MapsRestoreRequestV1.class, new MapsRestoreRequestV1("mini-pvp", kept.trashId(), null));

        assertThat(answer(MapsRestoreResponseV1.class).status()).isEqualTo(MapsRestoreResponseV1Status.FAILED);
        assertThat(file.exists()).isFalse();
        assertThat(trash.find(kept.trashId())).isPresent();
        verify(uploads, never()).save(eq("starwars.msav"), anyString(), any());
    }
}
