package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.cloudburstmc.netty.signaling.control.JdkWebSocketTransport;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Optional carrier only. ProviderClient retains signing, sequencing, retries and all lifecycle handling. */
final class ProviderWebSocket implements AutoCloseable {
    static final int MAX_FRAME_BYTES = 524288;
    static final List<String> AUTH_HEADERS = List.of("nxs-instance-id", "nxs-key-id", "nxs-timestamp",
            "nxs-signature-version", "nxs-generation", "nxs-sequence", "idempotency-key", "nxs-signature");
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final JdkWebSocketTransport.Limits LIMITS = new JdkWebSocketTransport.Limits(
            MAX_FRAME_BYTES, 1024, 4, MAX_FRAME_BYTES + 64, TIMEOUT, TIMEOUT, TIMEOUT, Duration.ofSeconds(2));

    record Reply(int status, HttpHeaders headers, String body) { }
    @FunctionalInterface interface BeforeSend { void persist() throws IOException; }

    private final HttpClient http;
    private final URI endpoint;
    // Separate from the serialized/blocking ProviderClient executor, including during open and receive.
    private final ScheduledThreadPoolExecutor io = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(r, "nethernet-provider-websocket");
        thread.setDaemon(true);
        return thread;
    });
    private Connection current;
    private long retryAt;
    private int failures;
    private boolean closed;

    private static final class Connection {
        final String binding;
        JdkWebSocketTransport transport;
        CompletableFuture<Reply> pending;
        String id;
        long pingAt;
        Connection(String binding) { this.binding = binding; }
    }

    ProviderWebSocket(HttpClient http, URI endpoint) {
        this.http = http;
        this.endpoint = endpoint;
        io.setRemoveOnCancelPolicy(true);
        io.scheduleWithFixedDelay(this::keepAlive, 20, 20, TimeUnit.SECONDS);
    }

    /** Null means HTTP should be used without a WS attempt (backoff or oversized envelope). */
    Reply exchange(String operation, HttpRequest request, String body, Map<String, String> upgradeHeaders,
                   int timeoutSeconds, BeforeSend beforeSend, Runnable requireCurrent) throws Exception {
        JsonObject frame = new JsonObject(), headers = new JsonObject();
        for (String name : AUTH_HEADERS) headers.addProperty(name, request.headers().firstValue(name).orElseThrow());
        frame.addProperty("operation", operation);
        frame.add("headers", headers);
        frame.addProperty("body", body);
        String wire = JSON.toJson(frame);
        if (wire.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) return null;
        String binding = headers.get("nxs-instance-id").getAsString() + ":" + headers.get("nxs-generation").getAsString();
        Connection connection;
        synchronized (this) {
            if (closed) throw new IOException("WebSocket carrier is closed");
            if (current != null && !binding.equals(current.binding)) discard(current, false);
            if (current == null) {
                if (System.nanoTime() < retryAt) return null;
                current = connection = new Connection(binding);
                connection.transport = JdkWebSocketTransport.connect(http, endpoint, ProviderCrypto.PROTOCOL,
                        upgradeHeaders, LIMITS, io, io, text -> receive(connection, text));
                connection.transport.closed().whenComplete((result, failure) -> lost(connection));
            } else connection = current;
        }
        try {
            connection.transport.opened().toCompletableFuture().get(11, TimeUnit.SECONDS);
            CompletableFuture<Reply> reply = new CompletableFuture<>();
            synchronized (this) {
                if (current != connection) throw new IOException("WebSocket connection changed");
                if (connection.pending != null) throw new IOException("Concurrent provider operation");
                connection.pending = reply;
                connection.id = headers.get("idempotency-key").getAsString();
            }
            // Executed on ProviderClient's serialized thread before any operation frame can be sent.
            beforeSend.persist();
            connection.transport.sendText(wire, requireCurrent).whenComplete((ignored, failure) -> {
                if (failure != null) reply.completeExceptionally(failure);
            });
            Reply result = reply.get(timeoutSeconds, TimeUnit.SECONDS);
            synchronized (this) {
                if (current == connection) {
                    connection.pending = null;
                    connection.id = null;
                    failures = 0;
                }
            }
            return result;
        } catch (Exception failure) {
            synchronized (this) { discard(connection, true); }
            throw failure;
        }
    }

    private synchronized CompletionStage<Void> receive(Connection connection, String text) {
        if (current != connection || closed) return CompletableFuture.completedFuture(null);
        if (text.equals("pong")) {
            connection.pingAt = 0;
            return CompletableFuture.completedFuture(null);
        }
        try {
            Parsed parsed = parse(text);
            if (connection.pending == null || !parsed.id.equals(connection.id))
                throw new IOException("Unexpected provider response");
            connection.pending.complete(parsed.reply);
            return CompletableFuture.completedFuture(null);
        } catch (Exception failure) {
            discard(connection, true);
            return CompletableFuture.failedFuture(failure);
        }
    }

    private record Parsed(String id, Reply reply) { }

    /** Closed, shallow carrier envelope; the operation body remains an untouched string. */
    private static Parsed parse(String wire) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(wire))) {
            reader.setStrictness(Strictness.STRICT);
            Set<String> fields = new HashSet<>();
            String id = null, body = null;
            int status = 0;
            Map<String, List<String>> headers = new HashMap<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!fields.add(name)) throw new IOException("Duplicate response field");
                switch (name) {
                    case "id" -> { id = string(reader); if (!id.matches("[A-Za-z0-9_-]{16,128}")) throw new IOException("Invalid response id"); }
                    case "status" -> {
                        if (reader.peek() != JsonToken.NUMBER) throw new IOException("Invalid response status");
                        String token = reader.nextString();
                        if (!token.matches("[2-5][0-9]{2}")) throw new IOException("Invalid response status");
                        status = Integer.parseInt(token);
                    }
                    case "body" -> { body = string(reader); if (body.getBytes(StandardCharsets.UTF_8).length > 65536) throw new IOException("Response body exceeds limit"); }
                    case "headers" -> {
                        reader.beginObject();
                        while (reader.hasNext()) {
                            String key = reader.nextName();
                            if (!Set.of("content-type", "retry-after", "x-provider-time", "warden-idempotent-replay").contains(key)
                                    || headers.containsKey(key)) throw new IOException("Invalid response header");
                            String value = string(reader);
                            if (value.length() > 1024 || value.chars().anyMatch(c -> c < 32 || c == 127)) throw new IOException("Invalid response header value");
                            headers.put(key, List.of(value));
                        }
                        reader.endObject();
                    }
                    default -> throw new IOException("Unknown response field");
                }
            }
            reader.endObject();
            if (!fields.equals(Set.of("id", "status", "headers", "body")) || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IOException("Incomplete response envelope");
            return new Parsed(id, new Reply(status, HttpHeaders.of(headers, (name, value) -> true), body));
        } catch (IllegalStateException | IllegalArgumentException failure) {
            throw new IOException("Invalid provider response", failure);
        }
    }

    private static String string(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw new IOException("Expected response string");
        return reader.nextString();
    }

    private synchronized void keepAlive() {
        Connection connection = current;
        if (closed || connection == null || !connection.transport.opened().toCompletableFuture().isDone()) return;
        if (connection.pingAt != 0) { discard(connection, true); return; }
        connection.pingAt = System.nanoTime();
        connection.transport.sendText("ping").whenComplete((ignored, failure) -> {
            if (failure != null) lost(connection);
        });
    }

    private synchronized void lost(Connection connection) { discard(connection, true); }

    private void discard(Connection connection, boolean backoff) {
        if (current != connection) return;
        current = null; // Fence callbacks before abort invokes completion handlers.
        if (backoff) {
            long delay = Math.min(30000, 500L << Math.min(failures++, 6));
            retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay + ThreadLocalRandom.current().nextLong(delay));
        }
        if (connection.pending != null) connection.pending.completeExceptionally(new IOException("Provider WebSocket disconnected"));
        connection.transport.abort();
    }

    @Override public synchronized void close() {
        closed = true;
        if (current != null) discard(current, false);
        io.shutdownNow();
    }
}
