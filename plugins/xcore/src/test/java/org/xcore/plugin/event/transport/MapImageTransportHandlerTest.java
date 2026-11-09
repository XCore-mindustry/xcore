package org.xcore.plugin.event.transport;

import arc.func.Cons;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xcore.plugin.config.TomlXcoreConfig;
import org.xcore.plugin.service.NetworkService;
import org.xcore.plugin.service.map.MapImageService;
import org.xcore.protocol.generated.messages.maps.MapsFileResponseV1Status;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsFileRequestV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsFileResponseV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsPreviewRequestV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsPreviewResponseV1;
import org.xcore.protocol.generated.messages.maps.MapsPreviewResponseV1Status;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MapImageTransportHandlerTest {
    private final NetworkService network = mock(NetworkService.class);
    private final MapImageService images = mock(MapImageService.class);
    private final AtomicReference<Cons<MapsPreviewRequestV1>> previews = new AtomicReference<>();
    private final AtomicReference<Cons<MapsFileRequestV1>> files = new AtomicReference<>();

    @BeforeEach
    void start() {
        var config = new TomlXcoreConfig();
        config.server.name = "survival";
        doAnswer(call -> { previews.set(call.getArgument(1)); return null; })
                .when(network).subscribe(eq(MapsPreviewRequestV1.class), any());
        doAnswer(call -> { files.set(call.getArgument(1)); return null; })
                .when(network).subscribe(eq(MapsFileRequestV1.class), any());
        new MapImageTransportHandler(network, config, images).registerListeners();
    }

    @Test
    void requestsForOtherServersAreIgnored() {
        previews.get().get(new MapsPreviewRequestV1("hexed", "arena.msav", null));
        files.get().get(new MapsFileRequestV1("hexed", "arena.msav"));

        verifyNoInteractions(images);
        verify(network, never()).respond(any(), any());
    }

    @Test
    void theServiceAnswerIsPublished() {
        var request = new MapsPreviewRequestV1("survival", "arena.msav", null);
        var response = new MapsPreviewResponseV1("survival", "arena.msav", MapsPreviewResponseV1Status.NOT_FOUND, null, null);
        when(images.preview(request)).thenReturn(CompletableFuture.completedFuture(response));

        previews.get().get(request);

        verify(network).respond(request, response);
    }

    @Test
    void aFailedStageIsAnsweredWithFailed() {
        var request = new MapsFileRequestV1("survival", "arena.msav");
        when(images.file(request)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));

        files.get().get(request);

        verify(network).respond(request,
                new MapsFileResponseV1("survival", "arena.msav", MapsFileResponseV1Status.FAILED, null, null));
    }

    @Test
    void moreThanFourAtOnceAreRejected() {
        when(images.preview(any())).thenReturn(new CompletableFuture<>());
        for (int i = 0; i < 5; i++) previews.get().get(new MapsPreviewRequestV1("survival", "map" + i + ".msav", null));

        verify(images, times(4)).preview(any());
        verify(network).respondError(any(), eq("REJECTED"), any());
    }
}
