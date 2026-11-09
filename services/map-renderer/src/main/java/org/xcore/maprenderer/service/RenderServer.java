package org.xcore.maprenderer.service;

import com.sun.net.httpserver.HttpServer;
import org.xcore.maprenderer.MapRenderer;
import org.xcore.maprenderer.RenderContent;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsRenderRequestV1;
import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsRenderResponseV1;
import org.xcore.protocol.generated.messages.maps.MapsRenderResponseV1Status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * The map renderer service: answers {@code maps.render} RPCs from {@code xcore:rpc:req:map-renderer}
 * in the bus's RPC envelope, renders at most {@code concurrency} maps at once with a short queue
 * (anything beyond it is answered {@code busy}), and serves {@code /ready} and {@code /metrics}.
 *
 * <p>Configuration comes from the environment; see {@link Config}.
 */
public final class RenderServer implements AutoCloseable {
    static final String STREAM = "xcore:rpc:req:map-renderer";
    static final String RPC_TYPE = MapsRenderRequestV1.MESSAGE_TYPE;
    /**
     * Present while the renderer listens: clients skip asking for a render while it is absent
     * instead of waiting out the RPC timeout. Refreshed every {@link #PRESENCE_EVERY_MS}.
     */
    static final String PRESENCE_KEY = "xcore:service:map-renderer";
    static final long PRESENCE_TTL_MS = 30_000, PRESENCE_EVERY_MS = 10_000;
    /** Bumped when drawing changes, so cached renders of the old drawing are not served. */
    static final String RENDERER_REVISION = "2";
    private static final long RESPONSE_STREAM_MAXLEN = 20_000;
    private static final Pattern HASH = Pattern.compile("\"contentHash\"\\s*:\\s*\"([a-f0-9]{64})\"");

    /** Settings, from {@code MAP_RENDERER_*} environment variables. */
    public record Config(String redisUrl, Path data, Path cache, long cacheBytes, String http, int concurrency,
                         int queue, String group, String consumer) {
        public static Config fromEnvironment(Map<String, String> env) {
            String host;
            try {
                host = java.net.InetAddress.getLocalHost().getHostName();
            } catch (IOException unknown) {
                host = "map-renderer";
            }
            return new Config(
                    env.getOrDefault("MAP_RENDERER_REDIS_URL", env.getOrDefault("REDIS_URL", "redis://127.0.0.1:6379")),
                    Path.of(env.getOrDefault("MAP_RENDERER_DATA", "render-data")),
                    Path.of(env.getOrDefault("MAP_RENDERER_CACHE", "cache")),
                    Long.parseLong(env.getOrDefault("MAP_RENDERER_CACHE_MB", "1024")) * 1024 * 1024,
                    env.getOrDefault("MAP_RENDERER_HTTP", "127.0.0.1:9471"),
                    Integer.parseInt(env.getOrDefault("MAP_RENDERER_CONCURRENCY", "2")),
                    Integer.parseInt(env.getOrDefault("MAP_RENDERER_QUEUE", "4")),
                    env.getOrDefault("MAP_RENDERER_GROUP", "xcore:cg:map-renderer"),
                    env.getOrDefault("MAP_RENDERER_CONSUMER", host + "-" + ProcessHandle.current().pid()));
        }
    }

    private final Config config;
    private final Metrics metrics = new Metrics();
    private final RenderHandler handler;
    private final ThreadPoolExecutor workers;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean ready = new AtomicBoolean();
    private volatile Resp reader, commands;
    private volatile String version;
    private long presenceAt;
    private HttpServer http;

    public RenderServer(Config config) throws IOException {
        this.config = config;
        var content = RenderContent.load(config.data().resolve("content.json"));
        var renderer = new MapRenderer(content, config.data().resolve("sprites.pack"), 16 * 1024 * 1024, 1_000_000);
        version = RENDERER_REVISION + "-" + dataHash(config.data()).substring(0, 12);
        handler = new RenderHandler(renderer, new RenderCache(config.cache(), config.cacheBytes()), new RedisBlobs(),
                version, metrics, Clock.systemUTC());
        workers = new ThreadPoolExecutor(config.concurrency(), config.concurrency(), 1, TimeUnit.MINUTES,
                new ArrayBlockingQueue<>(Math.max(1, config.queue())), runnable -> {
                    var thread = new Thread(runnable, "render");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public static void main(String[] args) throws Exception {
        var server = new RenderServer(Config.fromEnvironment(System.getenv()));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "shutdown"));
        server.startHttp();
        server.run();
    }

    void startHttp() throws IOException {
        int colon = config.http().lastIndexOf(':');
        http = HttpServer.create(new InetSocketAddress(config.http().substring(0, colon),
                Integer.parseInt(config.http().substring(colon + 1))), 8);
        http.createContext("/ready", exchange -> {
            boolean ok = ready.get();
            byte[] body = (ok ? "ready\n" : "not ready\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(ok ? 200 : 503, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        http.createContext("/metrics", exchange -> {
            byte[] body = metrics.prometheus().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        http.start();
    }

    /** Reads requests until {@link #close()}; reconnects to Redis with a growing pause. */
    public void run() {
        long pause = 1000;
        while (running.get()) {
            try {
                connect();
                pause = 1000;
                log("Map renderer listening on " + STREAM + " as " + config.consumer());
                while (running.get()) poll();
            } catch (IOException | RuntimeException error) {
                ready.set(false);
                if (!running.get()) break;
                log("Redis unavailable (" + error.getMessage() + "), retrying in " + pause / 1000 + " s");
                closeConnections();
                sleep(pause);
                pause = Math.min(pause * 2, 30_000);
            }
        }
    }

    private void connect() throws IOException {
        closeConnections();
        reader = Resp.connect(config.redisUrl(), 15_000);
        commands = Resp.connect(config.redisUrl(), 15_000);
        commands.command("PING");
        try {
            commands.command("XGROUP", "CREATE", STREAM, config.group(), "$", "MKSTREAM");
        } catch (Resp.Error exists) {
            if (!exists.getMessage().startsWith("BUSYGROUP")) throw exists;
        }
        presenceAt = 0;
        ready.set(true);
    }

    private void poll() throws IOException {
        long now = System.currentTimeMillis();
        if (now - presenceAt >= PRESENCE_EVERY_MS) {
            commands.command("SET", PRESENCE_KEY, Json.write(Map.of("version", version,
                    "concurrency", config.concurrency(), "inFlight", metrics.inFlight.get())), "PX", PRESENCE_TTL_MS);
            presenceAt = now;
        }
        Object reply = reader.command("XREADGROUP", "GROUP", config.group(), config.consumer(), "COUNT", "4",
                "BLOCK", "5000", "STREAMS", STREAM, ">");
        for (Object stream : Resp.list(reply)) {
            List<Object> parts = Resp.list(stream);
            for (Object message : Resp.list(parts.get(1))) {
                List<Object> entry = Resp.list(message);
                String id = Resp.text(entry.get(0));
                var fields = new LinkedHashMap<String, String>();
                List<Object> flat = Resp.list(entry.get(1));
                for (int i = 0; i + 1 < flat.size(); i += 2) fields.put(Resp.text(flat.get(i)), Resp.text(flat.get(i + 1)));
                // A render is not retried by redelivery: the caller asks again if it still wants it.
                commands.command("XACK", STREAM, config.group(), id);
                dispatch(fields);
            }
        }
    }

    void dispatch(Map<String, String> fields) {
        String replyTo = fields.get("reply_to"), correlation = fields.get("correlation_id");
        if (replyTo == null || correlation == null) return;
        long expiresAt = parseLong(fields.get("expires_at"));
        if (expiresAt > 0 && expiresAt < System.currentTimeMillis()) return;
        if (!RPC_TYPE.equals(fields.get("rpc_type"))) {
            respondError(fields, "REJECTED", "The map renderer answers " + RPC_TYPE + " only");
            return;
        }
        MapsRenderRequestV1 request;
        try {
            request = RenderHandler.parse(fields.getOrDefault("payload_json", "{}"));
        } catch (RuntimeException invalid) {
            var hash = HASH.matcher(fields.getOrDefault("payload_json", ""));
            if (hash.find()) {
                respond(fields, new MapsRenderResponseV1(hash.group(1), MapsRenderResponseV1Status.FAILED, null, null,
                        null, "Invalid request: " + invalid.getMessage()));
            } else {
                respondError(fields, "REJECTED", "Invalid maps.render.request: " + invalid.getMessage());
            }
            return;
        }
        try {
            workers.execute(() -> {
                metrics.inFlight.incrementAndGet();
                try {
                    respond(fields, handler.handle(request));
                } finally {
                    metrics.inFlight.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException full) {
            metrics.answered(MapsRenderResponseV1Status.BUSY);
            respond(fields, new MapsRenderResponseV1(request.contentHash(), MapsRenderResponseV1Status.BUSY, null, null,
                    null, "The renderer is busy; try again in a minute"));
        }
    }

    private void respond(Map<String, String> request, MapsRenderResponseV1 response) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("messageType", MapsRenderResponseV1.MESSAGE_TYPE);
        payload.put("messageVersion", MapsRenderResponseV1.MESSAGE_VERSION);
        payload.putAll(response.toPayload());
        send(request, "ok", "", "", Json.write(payload));
    }

    private void respondError(Map<String, String> request, String code, String message) {
        send(request, "error", code, message, "{}");
    }

    /** The bus's RPC response envelope, as the game servers write it. */
    private void send(Map<String, String> request, String status, String code, String message, String payloadJson) {
        Object[] args = {"XADD", request.get("reply_to"), "MAXLEN", "~", RESPONSE_STREAM_MAXLEN, "*",
                "schema_version", "1",
                "rpc_type", request.getOrDefault("rpc_type", RPC_TYPE),
                "correlation_id", request.get("correlation_id"),
                "server", "map-renderer",
                "status", status,
                "error_code", code,
                "error_message", message,
                "responded_at", System.currentTimeMillis(),
                "payload_json", payloadJson};
        try {
            commands.command(args);
        } catch (IOException | RuntimeException error) {
            log("Could not answer " + request.get("correlation_id") + ": " + error.getMessage());
        }
    }

    /** Blobs through the command connection. */
    private final class RedisBlobs implements RenderHandler.Blobs {
        @Override
        public byte[] get(String key) throws IOException {
            Object value = commands.command("GET", key);
            return value instanceof byte[] bytes ? bytes : null;
        }

        @Override
        public void set(String key, byte[] value, long ttlSeconds) throws IOException {
            commands.command("SET", key, value, "EX", ttlSeconds);
        }
    }

    boolean ready() { return ready.get(); }

    Metrics metrics() { return metrics; }

    @Override
    public void close() {
        if (!running.getAndSet(false)) return;
        ready.set(false);
        workers.shutdown();
        try {
            workers.awaitTermination(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (http != null) http.stop(0);
        try {
            // Clients stop asking at once rather than when the key expires.
            if (commands != null) commands.command("DEL", PRESENCE_KEY);
        } catch (IOException | RuntimeException gone) {
            // Redis is down: the key expires by itself.
        }
        closeConnections();
    }

    private void closeConnections() {
        if (reader != null) reader.close();
        if (commands != null) commands.close();
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String line) {
        System.out.println(line);
    }

    /** Names the render data: new content or new sprites must not reuse renders cached on disk. */
    static String dataHash(java.nio.file.Path data) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (String name : new String[]{"content.json", "sprites.pack"}) {
                try (var in = Files.newInputStream(data.resolve(name))) {
                    for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
