package org.xcore.plugin.event.transport;

import arc.util.Log;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.xcore.plugin.config.TomlXcoreConfig;
import org.xcore.plugin.service.NetworkService;
import org.xcore.plugin.service.map.MapImageService;
import org.xcore.protocol.generated.messages.maps.MapsFileResponseV1Status;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsFileRequestV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsFileResponseV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsPreviewRequestV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsPreviewResponseV1;
import org.xcore.protocol.generated.messages.maps.MapsPreviewResponseV1Status;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Answers maps.preview and maps.file for this server (see {@link MapImageService}). */
@Singleton
public class MapImageTransportHandler {
    static final int MAX_IN_FLIGHT = 4;

    private final NetworkService network;
    private final TomlXcoreConfig config;
    private final MapImageService images;
    private final AtomicInteger inFlight = new AtomicInteger();

    @Inject
    public MapImageTransportHandler(NetworkService network, TomlXcoreConfig config, MapImageService images) {
        this.network = network;
        this.config = config;
        this.images = images;
    }

    public void registerListeners() {
        network.subscribe(MapsPreviewRequestV1.class, request -> {
            if (!config.server.name.equals(request.server())) return;
            handle(request, () -> images.preview(request), () -> new MapsPreviewResponseV1(request.server(),
                    request.fileName(), MapsPreviewResponseV1Status.FAILED, null, null));
        });
        network.subscribe(MapsFileRequestV1.class, request -> {
            if (!config.server.name.equals(request.server())) return;
            handle(request, () -> images.file(request), () -> new MapsFileResponseV1(request.server(),
                    request.fileName(), MapsFileResponseV1Status.FAILED, null, null));
        });
    }

    private <T> void handle(Object request, Supplier<CompletionStage<T>> work, Supplier<T> failed) {
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
            inFlight.decrementAndGet();
            network.respondError(request, "REJECTED", "Map images are busy");
            return;
        }
        CompletionStage<T> stage;
        try {
            stage = work.get();
        } catch (RuntimeException error) {
            inFlight.decrementAndGet();
            respond(request, failed.get());
            return;
        }
        stage.whenComplete((response, error) -> {
            inFlight.decrementAndGet();
            if (error != null) Log.warn("[Maps] Image request failed: @", error.toString());
            respond(request, error == null && response != null ? response : failed.get());
        });
    }

    private void respond(Object request, Object response) {
        try {
            network.respond(request, response);
        } catch (RuntimeException error) {
            Log.warn("[Maps] Could not publish image response: @", error.toString());
        }
    }
}
