package org.xcore.maprenderer.service;

import org.xcore.protocol.generated.messages.maps.MapsMessages.MapsRenderRequestV1;

import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The service against a real redis-server, speaking the bus's RPC envelope as the bot does. */
class RenderServerTest {
    private static final String REPLY = "xcore:rpc:resp:discord";
    @TempDir Path directory;
    private Process redis;
    private RenderServer server;
    private Thread loop;
    private Resp client;
    private int httpPort;

    @BeforeEach
    void start() throws Exception {
        Path binary = Path.of("/usr/bin/redis-server");
        assumeTrue(Files.isExecutable(binary), "redis-server is not installed");
        int port = freePort();
        redis = new ProcessBuilder(binary.toString(), "--port", Integer.toString(port), "--save", "", "--appendonly", "no")
                .redirectErrorStream(true).redirectOutput(directory.resolve("redis.log").toFile()).start();
        String url = "redis://127.0.0.1:" + port;
        for (int i = 0; i < 50 && client == null; i++) {
            try {
                client = Resp.connect(url, 10_000);
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        assertNotNull(client, "redis-server did not start");
        httpPort = freePort();
        var config = new RenderServer.Config(url, Path.of(System.getProperty("renderData")), directory.resolve("cache"),
                64L << 20, "127.0.0.1:" + httpPort, 1, 1, "xcore:cg:map-renderer", "test");
        server = new RenderServer(config);
        server.startHttp();
        loop = Thread.ofPlatform().daemon().start(server::run);
        for (int i = 0; i < 100 && !server.ready(); i++) Thread.sleep(50);
        assertTrue(server.ready());
    }

    @AfterEach
    void stop() throws Exception {
        if (server != null) server.close();
        if (loop != null) loop.join(10_000);
        if (client != null) client.close();
        if (redis != null) {
            redis.destroy();
            redis.waitFor();
        }
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static byte[] bundled(String name) throws IOException {
        try (var zip = new ZipFile(System.getProperty("clientJar"))) {
            var entry = zip.stream().filter(e -> e.getName().endsWith("/" + name + ".msav")).findFirst().orElseThrow();
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private String request(byte[] map, String sha) throws IOException {
        return request(map, sha, sha);
    }

    private String request(byte[] map, String contentHash, String sha) throws IOException {
        String correlation = UUID.randomUUID().toString();
        String payload = "{\"messageType\":\"maps.render.request\",\"messageVersion\":1,\"contentHash\":\"" + contentHash
                + "\",\"file\":{\"key\":\"xcore:blob:" + sha + "\",\"sha256\":\"" + sha + "\",\"sizeBytes\":" + map.length
                + ",\"contentType\":\"application/octet-stream\",\"expiresAt\":" + (System.currentTimeMillis() + 60_000)
                + "},\"maxSide\":1024}";
        client.command("XADD", RenderServer.STREAM, "*", "schema_version", "1", "rpc_type", "maps.render.request",
                "correlation_id", correlation, "reply_to", REPLY, "requested_by", "discord-bot",
                "server", "map-renderer", "expires_at", System.currentTimeMillis() + 60_000, "payload_json", payload);
        return correlation;
    }

    /** Waits for the response with this correlation id and returns its fields. */
    private Map<String, String> response(String correlation) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            for (Object message : Resp.list(client.command("XRANGE", REPLY, "-", "+"))) {
                List<Object> entry = Resp.list(message);
                List<Object> flat = Resp.list(entry.get(1));
                var fields = new java.util.HashMap<String, String>();
                for (int i = 0; i + 1 < flat.size(); i += 2) fields.put(Resp.text(flat.get(i)), Resp.text(flat.get(i + 1)));
                if (correlation.equals(fields.get("correlation_id"))) return fields;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("No response for " + correlation);
    }

    @Test
    void rendersAMapFromItsBlobAndPublishesThePng() throws Exception {
        byte[] map = bundled("groundZero");
        String sha = RenderHandler.sha256(map);
        client.command("SET", "xcore:blob:" + sha, map, "EX", 60);

        var fields = response(request(map, sha));

        assertEquals("ok", fields.get("status"));
        assertEquals("map-renderer", fields.get("server"));
        JsonValue payload = new JsonReader().parse(fields.get("payload_json"));
        assertEquals("maps.render.response", payload.getString("messageType"));
        assertEquals("ok", payload.getString("status"));
        assertEquals(4, payload.getInt("scale"));
        JsonValue image = payload.get("image");
        assertEquals(1024, image.getInt("width"));
        byte[] png = (byte[]) client.command("GET", image.getString("key"));
        assertEquals(image.getInt("sizeBytes"), png.length);
        assertEquals(image.getString("sha256"), RenderHandler.sha256(png));

        // The second time comes from the disk cache, with the same picture.
        var again = new JsonReader().parse(response(request(map, sha)).get("payload_json"));
        assertEquals(image.getString("sha256"), again.get("image").getString("sha256"));
        assertTrue(server.metrics().prometheus().contains("xcore_map_renderer_cache_hits_total 1"));
    }

    @Test
    void aMissingBlobIsDownloadFailed() throws Exception {
        byte[] map = bundled("groundZero");
        var payload = new JsonReader().parse(response(request(map, RenderHandler.sha256(map))).get("payload_json"));

        assertEquals("download_failed", payload.getString("status"));
        assertTrue(!payload.has("image"));
    }

    @Test
    void aContentHashThatIsNotADigestIsRefused() throws Exception {
        byte[] map = bundled("groundZero");
        String sha = RenderHandler.sha256(map);
        client.command("SET", "xcore:blob:" + sha, map, "EX", 60);

        // The hash names files in the disk cache, so it must never be a path.
        var payload = new JsonReader().parse(response(request(map, "../../etc/passwd", sha)).get("payload_json"));

        assertEquals("failed", payload.getString("status"));
        assertTrue(!payload.has("image"));
    }

    @Test
    void theRendererAnnouncesItselfWhileListening() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        Object presence = null;
        while (presence == null && System.currentTimeMillis() < deadline) {
            presence = client.command("GET", RenderServer.PRESENCE_KEY);
            if (presence == null) Thread.sleep(50);
        }
        assertTrue(presence != null, "no presence key");
        var value = new JsonReader().parse(Resp.text(presence));
        assertEquals(1, value.getInt("concurrency"));
        long ttl = ((Number) client.command("PTTL", RenderServer.PRESENCE_KEY)).longValue();
        assertTrue(ttl > 0 && ttl <= RenderServer.PRESENCE_TTL_MS);
    }

    @Test
    void aRequestWithoutLayersGetsTheDefaultRender() {
        var file = new org.xcore.protocol.generated.shared.MapBlobRefV1("xcore:blob:" + "ab".repeat(32), "ab".repeat(32), 10,
                org.xcore.protocol.generated.shared.MapBlobRefV1ContentType.APPLICATION_OCTET_STREAM, null, null, 1L);
        var plain = new MapsRenderRequestV1("ab".repeat(32), file, null, null, null, null, null, null, null);
        var dark = new MapsRenderRequestV1("ab".repeat(32), file, null, null, true, null, null, true, null);

        assertEquals(org.xcore.maprenderer.RenderOptions.DEFAULT, RenderHandler.options(plain));
        assertEquals(new org.xcore.maprenderer.RenderOptions(true, true, false, true, false), RenderHandler.options(dark));
    }

    @Test
    void anotherRpcTypeIsRejected() throws Exception {
        String correlation = UUID.randomUUID().toString();
        client.command("XADD", RenderServer.STREAM, "*", "rpc_type", "maps.list.request", "correlation_id", correlation,
                "reply_to", REPLY, "payload_json", "{}");

        var fields = response(correlation);

        assertEquals("error", fields.get("status"));
        assertEquals("REJECTED", fields.get("error_code"));
    }

    @Test
    void readyAndMetricsAreServed() throws Exception {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var ready = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/ready")).build(),
                HttpResponse.BodyHandlers.ofString());
        var metrics = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/metrics")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, ready.statusCode());
        assertTrue(metrics.body().contains("xcore_map_renderer_responses_total{status=\"ok\"}"));
        assertTrue(metrics.body().contains("xcore_map_renderer_resident_bytes"));
    }

    @Test
    void respBinaryRoundTrip() throws Exception {
        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        client.command("SET", "binary", bytes);
        assertArrayEquals(bytes, (byte[]) client.command("GET", "binary"));
        assertEquals(null, client.command("GET", "missing"));
    }
}
