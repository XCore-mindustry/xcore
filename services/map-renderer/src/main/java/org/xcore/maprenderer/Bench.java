package org.xcore.maprenderer;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Measures the prototype: renders maps with a fixed number of concurrent renders and reports
 * time, image size and the process's peak resident memory (VmHWM).
 *
 * <p>Usage: {@code Bench <render-data dir> <output dir> <concurrency> <rounds> <map.msav>...}
 */
public final class Bench {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int concurrency = Integer.parseInt(args[2]);
        int rounds = Integer.parseInt(args[3]);
        List<Path> maps = new ArrayList<>();
        for (int i = 4; i < args.length; i++) maps.add(Path.of(args[i]));
        Files.createDirectories(out);

        report("start");
        var content = RenderContent.load(data.resolve("content.json"));
        var renderer = new MapRenderer(content, data.resolve("sprites.pack"), 8 * 1024 * 1024, 1_000_000);
        report("content loaded");

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        long started = System.nanoTime();
        for (int round = 0; round < rounds; round++) {
            List<Future<String>> results = new ArrayList<>();
            for (Path map : maps) {
                boolean save = round == 0;
                results.add(pool.submit(() -> {
                    byte[] bytes = Files.readAllBytes(map);
                    var result = renderer.render(new java.io.ByteArrayInputStream(bytes), bytes.length, Integer.getInteger("maxSide", 4096), Integer.getInteger("maxBytes", 8 * 1024 * 1024));
                    String name = map.getFileName().toString().replace(".msav", "");
                    if (save) Files.write(out.resolve(name + ".png"), result.png());
                    return String.format("%-28s %4dx%-4d tiles  %2d px  %5dx%-5d  %7.1f KB  decode %5.0f ms  draw+encode %6.0f ms  encode %6.0f ms",
                            name, result.tilesWide(), result.tilesHigh(), result.scale(), result.width(), result.height(),
                            result.png().length / 1024.0, result.decodeNanos() / 1e6, result.drawNanos() / 1e6,
                            result.encodeNanos() / 1e6);
                }));
            }
            for (var result : results) {
                try {
                    String line = result.get();
                    if (round == rounds - 1) System.out.println(line);
                } catch (java.util.concurrent.ExecutionException error) {
                    if (round == rounds - 1) System.out.println("FAILED " + error.getCause());
                }
            }
            report("round " + (round + 1));
        }
        pool.shutdown();
        double cpu = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime() / 1e9;
        System.out.printf("total %.1f s for %d renders, process CPU %.1f s%n", (System.nanoTime() - started) / 1e9,
                rounds * maps.size(), cpu);
        report("end");
    }

    static void report(String stage) throws Exception {
        String rss = "?", hwm = "?";
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("VmRSS:")) rss = line.substring(6).trim();
            if (line.startsWith("VmHWM:")) hwm = line.substring(6).trim();
        }
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        System.out.printf("[%s] RSS %s, peak RSS %s, heap used %d MB / committed %d MB, non-heap %d MB%n", stage, rss, hwm,
                heap.getUsed() >> 20, heap.getCommitted() >> 20, nonHeap.getUsed() >> 20);
    }

    private Bench() {}
}
