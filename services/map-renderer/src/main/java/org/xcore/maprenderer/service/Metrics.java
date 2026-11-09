package org.xcore.maprenderer.service;

import org.xcore.protocol.generated.messages.maps.MapsRenderResponseV1Status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Counters for {@code /metrics}, in the Prometheus text format. */
final class Metrics {
    private final Map<MapsRenderResponseV1Status, LongAdder> answers = new EnumMap<>(MapsRenderResponseV1Status.class);
    private final LongAdder renders = new LongAdder(), cacheHits = new LongAdder();
    private final AtomicLong renderNanos = new AtomicLong();
    final AtomicLong inFlight = new AtomicLong();

    Metrics() {
        for (var status : MapsRenderResponseV1Status.values()) answers.put(status, new LongAdder());
    }

    void answered(MapsRenderResponseV1Status status) { answers.get(status).increment(); }

    void rendered(long nanos) {
        renders.increment();
        renderNanos.addAndGet(nanos);
    }

    void cacheHit() { cacheHits.increment(); }

    String prometheus() {
        var out = new StringBuilder(1024);
        out.append("# TYPE xcore_map_renderer_responses_total counter\n");
        answers.forEach((status, count) -> out.append("xcore_map_renderer_responses_total{status=\"")
                .append(status.value()).append("\"} ").append(count.sum()).append('\n'));
        out.append("# TYPE xcore_map_renderer_render_seconds summary\n")
                .append("xcore_map_renderer_render_seconds_sum ").append(renderNanos.get() / 1e9).append('\n')
                .append("xcore_map_renderer_render_seconds_count ").append(renders.sum()).append('\n')
                .append("# TYPE xcore_map_renderer_cache_hits_total counter\n")
                .append("xcore_map_renderer_cache_hits_total ").append(cacheHits.sum()).append('\n')
                .append("# TYPE xcore_map_renderer_in_flight gauge\n")
                .append("xcore_map_renderer_in_flight ").append(inFlight.get()).append('\n');
        long[] rss = residentMemory();
        out.append("# TYPE xcore_map_renderer_resident_bytes gauge\n")
                .append("xcore_map_renderer_resident_bytes ").append(rss[0]).append('\n')
                .append("# TYPE xcore_map_renderer_resident_peak_bytes gauge\n")
                .append("xcore_map_renderer_resident_peak_bytes ").append(rss[1]).append('\n');
        return out.toString();
    }

    /** VmRSS and VmHWM in bytes, or zeros off Linux. */
    static long[] residentMemory() {
        long rss = 0, peak = 0;
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) rss = kilobytes(line) * 1024;
                if (line.startsWith("VmHWM:")) peak = kilobytes(line) * 1024;
            }
        } catch (Exception notLinux) {
            // Leave zeros.
        }
        return new long[]{rss, peak};
    }

    private static long kilobytes(String line) {
        return Long.parseLong(line.replaceAll("[^0-9]", ""));
    }
}
