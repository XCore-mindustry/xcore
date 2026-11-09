package org.xcore.maprenderer.service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal RESP2 Redis client: one socket, one command at a time. The renderer needs a handful
 * of commands (PING, XGROUP, XREADGROUP, XACK, XADD, GET, SET), so it carries no client library
 * and no event loop.
 *
 * <p>Replies come back as {@code String} (simple strings), {@code Long}, {@code byte[]} (bulk
 * strings), {@code List<Object>} or {@code null}; error replies throw {@link Error}.
 */
public final class Resp implements AutoCloseable {
    /** An error reply from Redis. */
    public static final class Error extends IOException {
        public Error(String message) { super(message); }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private Resp(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
        this.out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
    }

    /**
     * Connects to {@code redis://[[user]:password@]host[:port][/db]}. TLS ({@code rediss://}) is
     * not supported; the services reach Redis on the same host.
     */
    public static Resp connect(String url, int readTimeoutMillis) throws IOException {
        URI uri = URI.create(url);
        if (!"redis".equals(uri.getScheme())) throw new IOException("Unsupported Redis URL scheme: " + uri.getScheme());
        var socket = new Socket();
        socket.connect(new InetSocketAddress(uri.getHost() == null ? "127.0.0.1" : uri.getHost(),
                uri.getPort() < 0 ? 6379 : uri.getPort()), 5000);
        socket.setSoTimeout(readTimeoutMillis);
        socket.setTcpNoDelay(true);
        var resp = new Resp(socket);
        try {
            String userInfo = uri.getRawUserInfo();
            if (userInfo != null && !userInfo.isEmpty()) {
                int colon = userInfo.indexOf(':');
                String user = colon < 0 ? "" : decode(userInfo.substring(0, colon));
                String password = decode(colon < 0 ? userInfo : userInfo.substring(colon + 1));
                if (user.isEmpty()) resp.command("AUTH", password);
                else resp.command("AUTH", user, password);
            }
            String path = uri.getPath();
            if (path != null && path.length() > 1) resp.command("SELECT", path.substring(1));
            return resp;
        } catch (IOException | RuntimeException error) {
            resp.close();
            throw error;
        }
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** Sends one command; arguments are {@code byte[]} or anything else as its string. */
    public synchronized Object command(Object... args) throws IOException {
        writeArray(args);
        out.flush();
        Object reply = read();
        if (reply instanceof Error error) throw new Error(error.getMessage());
        return reply;
    }

    private void writeArray(Object[] args) throws IOException {
        out.write(('*' + Integer.toString(args.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (Object arg : args) {
            byte[] bytes = arg instanceof byte[] b ? b : String.valueOf(arg).getBytes(StandardCharsets.UTF_8);
            out.write(('$' + Integer.toString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.write('\r');
            out.write('\n');
        }
    }

    private Object read() throws IOException {
        int type = in.read();
        if (type < 0) throw new EOFException("Redis closed the connection");
        String line = line();
        return switch (type) {
            case '+' -> line;
            case '-' -> new Error(line);
            case ':' -> Long.parseLong(line);
            case '$' -> {
                int length = Integer.parseInt(line);
                if (length < 0) yield null;
                byte[] bytes = in.readNBytes(length);
                if (bytes.length != length || in.read() != '\r' || in.read() != '\n') throw new EOFException("Short bulk reply");
                yield bytes;
            }
            case '*' -> {
                int count = Integer.parseInt(line);
                if (count < 0) yield null;
                var items = new ArrayList<Object>(count);
                for (int i = 0; i < count; i++) {
                    Object item = read();
                    if (item instanceof Error error) throw new Error(error.getMessage());
                    items.add(item);
                }
                yield items;
            }
            default -> throw new IOException("Unexpected Redis reply type: " + (char) type);
        };
    }

    private String line() throws IOException {
        var buffer = new ByteArrayOutputStream(32);
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException("Redis closed the connection");
            if (b == '\r') {
                if (in.read() != '\n') throw new IOException("Malformed Redis reply");
                return buffer.toString(StandardCharsets.UTF_8);
            }
            buffer.write(b);
        }
    }

    /** A bulk reply as text. */
    public static String text(Object reply) {
        return reply instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : reply == null ? null : reply.toString();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object reply) {
        return reply == null ? List.of() : (List<Object>) reply;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
