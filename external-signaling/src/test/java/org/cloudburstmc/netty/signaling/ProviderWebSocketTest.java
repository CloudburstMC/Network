package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ProviderWebSocketTest {
    @Test
    void existingLifecycleUsesSignedWebSocketOperationsAndHttpsRecovery(@TempDir Path directory) throws Exception {
        try (Provider provider = new Provider()) {
            var transport = new ProviderClientTest.FakeTransport();
            transport.stateless = true;
            ProviderClient client = client(provider, directory, ProviderClient.ControlTransport.AUTO, transport);
            try {
                client.start().get(20, TimeUnit.SECONDS);
                assertTrue(provider.websocketOps.contains("heartbeat"));
                assertEquals("websocket", client.lastControlCarrier());
                assertFalse(provider.stub.lastHeartbeat.has("appliedStateRevision"));
                assertFalse(provider.httpOps.contains("/v1/nxs/heartbeat"));
                assertTrue(provider.httpOps.contains("/v1/nxs/register"));
                assertTrue(provider.httpOps.contains("/v1/nxs/complete"));
                provider.dropAfterCommit = "rotate";
                client.rotateMachineKey().get(20, TimeUnit.SECONDS);
                assertTrue(provider.websocketOps.contains("rotate"));
                assertTrue(provider.httpOps.contains("/v1/nxs/rotate"));
                assertTrue(provider.httpOps.contains("/v1/nxs/retire"));
                assertEquals(1, provider.fallbacks.get());
                assertFalse(Files.readString(directory.resolve("provider-state.json")).contains("pendingWebSocketOperation"));
                assertEquals(0, transport.drains, "Control loss must preserve gameplay");
            } finally { client.stop().toCompletableFuture().get(15, TimeUnit.SECONDS); }
            ProviderClient recovered = client(provider, directory, ProviderClient.ControlTransport.AUTO, new ProviderClientTest.FakeTransport());
            try {
                assertEquals(2, recovered.start().get(20, TimeUnit.SECONDS).get("leaseGeneration").getAsLong());
                assertEquals(List.of(1L, 2L), provider.generations);
                recovered.deregister().get(10, TimeUnit.SECONDS);
                assertTrue(provider.websocketOps.contains("deregister"));
            } finally { recovered.stop().toCompletableFuture().get(15, TimeUnit.SECONDS); }
        }
    }

    @Test
    void httpIsDefaultAndAutoRequiresAdvertisement(@TempDir Path directory) throws Exception {
        for (boolean advertised : List.of(false, true)) try (Provider provider = new Provider()) {
            if (!advertised) provider.stub.extensionMetadata = null;
            var mode = advertised ? ProviderClient.ControlTransport.HTTP : ProviderClient.ControlTransport.AUTO;
            ProviderClient client = client(provider, directory.resolve(Boolean.toString(advertised)), mode, new ProviderClientTest.FakeTransport());
            try {
                client.start().get(20, TimeUnit.SECONDS);
                assertEquals(0, provider.upgrades.get());
                assertEquals("http", client.lastControlCarrier());
                assertTrue(provider.httpOps.contains("/v1/nxs/heartbeat"));
            } finally { client.stop().toCompletableFuture().get(15, TimeUnit.SECONDS); }
        }
    }

    @Test
    void ambiguousRotationRetainsCandidateAndRequiresOrdinaryRecovery(@TempDir Path directory) throws Exception {
        try (Provider provider = new Provider()) {
            ProviderClient client = client(provider, directory, ProviderClient.ControlTransport.AUTO, new ProviderClientTest.FakeTransport());
            try {
                client.start().get(20, TimeUnit.SECONDS);
                provider.dropAfterCommit = "rotate";
                provider.refuseAfterDrop = true;
                assertThrows(ExecutionException.class, () -> client.rotateMachineKey().get(20, TimeUnit.SECONDS));
                JsonObject state = JsonParser.parseString(Files.readString(directory.resolve("provider-state.json"))).getAsJsonObject();
                assertTrue(state.has("pendingWebSocketOperation"));
                String candidate = state.get("pendingPrivateKey").getAsString();
                assertThrows(ExecutionException.class, () -> client.rotateMachineKey().get(5, TimeUnit.SECONDS));
                JsonObject unchanged = JsonParser.parseString(Files.readString(directory.resolve("provider-state.json"))).getAsJsonObject();
                assertEquals(candidate, unchanged.get("pendingPrivateKey").getAsString());
            } finally { client.stop().toCompletableFuture().get(15, TimeUnit.SECONDS); }
            provider.refuseAfterDrop = false;
            ProviderClient recovered = client(provider, directory, ProviderClient.ControlTransport.AUTO, new ProviderClientTest.FakeTransport());
            try {
                recovered.start().get(20, TimeUnit.SECONDS);
                JsonObject state = JsonParser.parseString(Files.readString(directory.resolve("provider-state.json"))).getAsJsonObject();
                assertFalse(state.has("pendingWebSocketOperation"));
                assertFalse(state.has("pendingPrivateKey"));
                assertEquals(provider.stub.registration.get("keyId"), state.getAsJsonObject("registration").get("keyId"));
            } finally { recovered.stop().toCompletableFuture().get(15, TimeUnit.SECONDS); }
        }
    }

    @Test
    void malformedDuplicateReplyFallsBackWithTheOriginalSignedBytes(@TempDir Path directory) throws Exception {
        try (Provider provider = new Provider()) {
            ProviderClient client = client(provider, directory, ProviderClient.ControlTransport.AUTO, new ProviderClientTest.FakeTransport());
            provider.brokenReply = "{\"id\":\"AAAAAAAAAAAAAAAA\" ,\"id\":\"BBBBBBBBBBBBBBBB\",\"status\":200,\"headers\":{},\"body\":\"{}\"}";
            try {
                client.start().get(20, TimeUnit.SECONDS);
                assertTrue(provider.fallbacks.get() > 0);
                assertFalse(Files.readString(directory.resolve("provider-state.json")).contains("pendingWebSocketOperation"));
            } finally { client.stop().toCompletableFuture().get(15, TimeUnit.SECONDS); }
        }
    }

    @Test
    void definitiveHeartbeatRejectionAndRateLimitKeepExistingRetryUsable(@TempDir Path directory) throws Exception {
        try (Provider provider = new Provider()) {
            ProviderClient client = client(provider, directory, ProviderClient.ControlTransport.AUTO, new ProviderClientTest.FakeTransport());
            try {
                client.start().get(20, TimeUnit.SECONDS);
                provider.rejectNext = new Provider.Result(400, "{\"code\":\"check_in_profile_required\"}");
                assertThrows(ExecutionException.class, () -> client.drain().get(10, TimeUnit.SECONDS));
                assertFalse(Files.readString(directory.resolve("provider-state.json")).contains("pendingWebSocketOperation"));
                provider.rejectNext = new Provider.Result(429, "{\"code\":\"rate_limited\"}");
                client.drain().get(10, TimeUnit.SECONDS);
                assertEquals("websocket", client.lastControlCarrier());
                assertFalse(Files.readString(directory.resolve("provider-state.json")).contains("pendingWebSocketOperation"));
            } finally { client.stop().toCompletableFuture().get(15, TimeUnit.SECONDS); }
        }
    }

    @Test
    void correctingAnInvalidHeartbeatDoesNotRequireRegistrationRecovery(@TempDir Path directory) throws Exception {
        try (Provider provider = new Provider()) {
            AtomicReference<String> protocol = new AtomicReference<>("nethernet");
            ProviderClient client = client(provider, directory, ProviderClient.ControlTransport.AUTO,
                    new ProviderClientTest.FakeTransport(),
                    () -> new ProviderClient.Health(true, true, 20, 0, protocol.get(), "fixture"));
            try {
                client.start().get(20, TimeUnit.SECONDS);
                protocol.set("x".repeat(129));
                ExecutionException rejected = assertThrows(ExecutionException.class,
                        () -> client.drain().get(10, TimeUnit.SECONDS));
                assertEquals("Provider request failed: 400 invalid_heartbeat", rejected.getCause().getMessage());
                assertFalse(Files.readString(directory.resolve("provider-state.json")).contains("pendingWebSocketOperation"));
                protocol.set("nethernet-corrected");
                client.drain().get(10, TimeUnit.SECONDS);
                assertEquals("nethernet-corrected", provider.stub.lastHeartbeat.get("protocolVersion").getAsString());
                assertEquals("websocket", client.lastControlCarrier());
                assertEquals(1, provider.stub.generation);
            } finally { client.stop().toCompletableFuture().get(15, TimeUnit.SECONDS); }
        }
    }

    @Test
    void validationRejectionAfterLostAcknowledgementDoesNotClearAmbiguity(@TempDir Path directory) throws Exception {
        try (Provider provider = new Provider()) {
            ProviderClient client = client(provider, directory, ProviderClient.ControlTransport.AUTO, new ProviderClientTest.FakeTransport());
            try {
                client.start().get(20, TimeUnit.SECONDS);
                provider.dropAfterCommit = "heartbeat";
                provider.rejectAfterDrop = new Provider.Result(400, "{\"code\":\"invalid_heartbeat\"}");
                assertThrows(ExecutionException.class, () -> client.drain().get(10, TimeUnit.SECONDS));
                assertTrue(Files.readString(directory.resolve("provider-state.json")).contains("pendingWebSocketOperation"));
                int received = provider.websocketOps.size() + provider.httpOps.size();
                assertThrows(ExecutionException.class, () -> client.drain().get(10, TimeUnit.SECONDS));
                assertEquals(received, provider.websocketOps.size() + provider.httpOps.size());
            } finally { client.stop().toCompletableFuture().get(15, TimeUnit.SECONDS); }
        }
    }

    @Test
    void refusedUpgradeUsesHttpsAndBacksOff(@TempDir Path directory) throws Exception {
        try (Provider provider = new Provider()) {
            provider.rejectUpgrade = true;
            ProviderClient client = client(provider, directory, ProviderClient.ControlTransport.AUTO, new ProviderClientTest.FakeTransport());
            try {
                client.start().get(20, TimeUnit.SECONDS);
                assertEquals(1, provider.upgrades.get(), "Immediate key-install check-ins must not create a reconnect storm");
                assertTrue(provider.httpOps.contains("/v1/nxs/heartbeat"));
                assertFalse(Files.readString(directory.resolve("provider-state.json")).contains("pendingWebSocketOperation"));
            } finally { client.stop().toCompletableFuture().get(15, TimeUnit.SECONDS); }
        }
    }

    private static ProviderClient client(Provider provider, Path path, ProviderClient.ControlTransport mode,
                                         ProviderClientTest.FakeTransport transport) throws Exception {
        return client(provider, path, mode, transport,
                () -> new ProviderClient.Health(true, true, 20, 0, "nethernet", "fixture"));
    }

    private static ProviderClient client(Provider provider, Path path, ProviderClient.ControlTransport mode,
                                         ProviderClientTest.FakeTransport transport, Supplier<ProviderClient.Health> health) throws Exception {
        return new ProviderClient(new ProviderClient.Configuration(URI.create(provider.stub.origin), "nxs-admission-v1",
                "WebSocket host", ProviderClient.NEW_SERVICE, ProviderClient.BEARER_TOKEN, "independent-provider-token",
                null, null, Map.of(), mode), new ProviderStateStore(path), transport, () -> null,
                health, message -> { });
    }

    /** Real JDK/Netty socket front door. The independent HTTP provider still verifies the original signature. */
    private static final class Provider implements AutoCloseable {
        final IndependentProviderStub stub = new IndependentProviderStub();
        final String backend = stub.origin;
        final NioEventLoopGroup group = new NioEventLoopGroup(1);
        final ExecutorService forwarding = Executors.newSingleThreadExecutor();
        final HttpClient http = HttpClient.newHttpClient();
        final Channel server;
        final Map<String, Result> receipts = new ConcurrentHashMap<>();
        final List<String> websocketOps = new CopyOnWriteArrayList<>(), httpOps = new CopyOnWriteArrayList<>();
        final List<Long> generations = new CopyOnWriteArrayList<>();
        final AtomicInteger upgrades = new AtomicInteger(), pings = new AtomicInteger(), fallbacks = new AtomicInteger();
        volatile String dropAfterCommit, brokenReply;
        volatile Result rejectNext, rejectAfterDrop;
        volatile boolean refuseAfterDrop, rejectUpgrade;
        volatile Channel socket;
        volatile Map<String, String> droppedHeaders;
        volatile String droppedBody;
        final CompletableFuture<Throwable> failure = new CompletableFuture<>();
        record Result(int status, String body) { }

        Provider() throws Exception {
            server = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(65536),
                                    new SimpleChannelInboundHandler<Object>() {
                                        @Override protected void channelRead0(ChannelHandlerContext ctx, Object message) {
                                            try {
                                                if (message instanceof FullHttpRequest request) {
                                                    if (request.uri().equals("/v1/nxs/control")) {
                                                        upgrades.incrementAndGet();
                                                        if (rejectUpgrade) {
                                                            var denied = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN);
                                                            denied.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
                                                            ctx.writeAndFlush(denied).addListener(ChannelFutureListener.CLOSE);
                                                            return;
                                                        }
                                                        Map<String, String> headers = authHeaders(request);
                                                        assertEquals(ProviderCrypto.PROTOCOL, request.headers().get("sec-websocket-protocol"));
                                                        assertTrue(ProviderCrypto.verify(stub.keys.get(headers.get("nxs-key-id")), headers.get("nxs-signature"),
                                                                ProviderCrypto.request(stub.origin, "GET", request.uri(), Long.parseLong(headers.get("nxs-timestamp")),
                                                                        headers.get("nxs-instance-id"), headers.get("nxs-key-id"), headers.get("idempotency-key"),
                                                                        Long.parseLong(headers.get("nxs-generation")), Long.parseLong(headers.get("nxs-sequence")), "")));
                                                        generations.add(Long.parseLong(headers.get("nxs-generation")));
                                                        new WebSocketServerHandshakerFactory(stub.origin.replace("http:", "ws:") + request.uri(),
                                                                ProviderCrypto.PROTOCOL, false, ProviderWebSocket.MAX_FRAME_BYTES).newHandshaker(request)
                                                                .handshake(ctx.channel(), request).addListener(f -> socket = ctx.channel());
                                                    } else {
                                                        String path = request.uri(), method = request.method().name(), body = request.content().toString(StandardCharsets.UTF_8);
                                                        Map<String, String> headers = new HashMap<>();
                                                        request.headers().forEach(entry -> {
                                                            if (entry.getKey().toLowerCase(Locale.ROOT).startsWith("nxs-") || entry.getKey().equalsIgnoreCase("idempotency-key")
                                                                    || entry.getKey().equalsIgnoreCase("authorization")) headers.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
                                                        });
                                                        forwarding.execute(() -> {
                                                            try {
                                                                httpOps.add(path);
                                                                if (droppedHeaders != null && headers.get("idempotency-key") != null
                                                                        && headers.get("idempotency-key").equals(droppedHeaders.get("idempotency-key"))) {
                                                                    assertEquals(droppedBody, body, "HTTP fallback rewrote the signed body");
                                                                    // The first fallback must preserve the entire already-signed request.
                                                                    if (fallbacks.getAndIncrement() == 0) assertEquals(droppedHeaders, headers);
                                                                }
                                                                Result response = dispatch(path, method, body, headers);
                                                                byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
                                                                var reply = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(response.status), Unpooled.wrappedBuffer(bytes));
                                                                reply.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json").setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
                                                                ctx.writeAndFlush(reply);
                                                            } catch (Throwable error) { failure.complete(error); ctx.close(); }
                                                        });
                                                    }
                                                } else if (message instanceof TextWebSocketFrame text) {
                                                    if (text.text().equals("ping")) { pings.incrementAndGet(); ctx.writeAndFlush(new TextWebSocketFrame("pong")); return; }
                                                    JsonObject envelope = JsonParser.parseString(text.text()).getAsJsonObject();
                                                    assertEquals(Set.of("operation", "headers", "body"), envelope.keySet());
                                                    String op = envelope.get("operation").getAsString(), body = envelope.get("body").getAsString();
                                                    Map<String, String> headers = new HashMap<>();
                                                    envelope.getAsJsonObject("headers").entrySet().forEach(entry -> headers.put(entry.getKey(), entry.getValue().getAsString()));
                                                    assertEquals(new HashSet<>(ProviderWebSocket.AUTH_HEADERS), headers.keySet());
                                                    websocketOps.add(op);
                                                    forwarding.execute(() -> {
                                                        try {
                                                            Result result = dispatch("/v1/nxs/" + op, "POST", body, headers);
                                                            if (op.equals(dropAfterCommit)) {
                                                                droppedHeaders = headers; droppedBody = body; dropAfterCommit = null; ctx.close(); return;
                                                            }
                                                            JsonObject reply = new JsonObject();
                                                            reply.addProperty("id", headers.get("idempotency-key")); reply.addProperty("status", result.status);
                                                            reply.add("headers", new JsonObject()); reply.addProperty("body", result.body);
                                                            if (brokenReply != null) {
                                                                droppedHeaders = headers; droppedBody = body;
                                                                ctx.writeAndFlush(new TextWebSocketFrame(brokenReply)); brokenReply = null;
                                                            } else {
                                                                String wire = reply.toString(); int split = wire.length() / 2;
                                                                ctx.writeAndFlush(new TextWebSocketFrame(false, 0, wire.substring(0, split)));
                                                                ctx.writeAndFlush(new ContinuationWebSocketFrame(true, 0, wire.substring(split)));
                                                            }
                                                        } catch (Throwable error) { failure.complete(error); ctx.close(); }
                                                    });
                                                } else if (message instanceof CloseWebSocketFrame close) {
                                                    ctx.writeAndFlush(close.retainedDuplicate()).addListener(ChannelFutureListener.CLOSE);
                                                }
                                            } catch (Throwable error) { failure.complete(error); ctx.close(); }
                                        }
                                    });
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
            stub.origin = "http://127.0.0.1:" + ((InetSocketAddress) server.localAddress()).getPort();
            stub.operationPrefix = "/v1/nxs/";
            stub.checkInMillis = 900000;
            stub.extensionMetadata = JsonParser.parseString("{\"org.nethernet.websocket\":{\"version\":1,\"critical\":false,\"data\":{\"url\":\""
                    + stub.origin.replace("http:", "ws:") + "/v1/nxs/control\",\"subprotocol\":\"" + ProviderCrypto.PROTOCOL + "\"}}}").getAsJsonObject();
        }

        Map<String, String> authHeaders(FullHttpRequest request) {
            Map<String, String> result = new HashMap<>();
            for (String name : ProviderWebSocket.AUTH_HEADERS) result.put(name, request.headers().get(name));
            return result;
        }

        Result dispatch(String path, String method, String body, Map<String, String> headers) throws Exception {
            String id = headers.get("idempotency-key");
            if (rejectAfterDrop != null && droppedHeaders != null && Objects.equals(id, droppedHeaders.get("idempotency-key")))
                return rejectAfterDrop;
            if (path.equals("/v1/nxs/heartbeat") && JsonParser.parseString(body).getAsJsonObject()
                    .get("protocolVersion").getAsString().length() > 128)
                return new Result(400, "{\"code\":\"invalid_heartbeat\"}");
            if (id != null && rejectNext != null) {
                Result rejected = rejectNext;
                rejectNext = null;
                return rejected;
            }
            if (refuseAfterDrop && droppedHeaders != null && Objects.equals(id, droppedHeaders.get("idempotency-key")))
                return new Result(503, "{\"code\":\"fixture_unavailable\"}");
            if (id != null && receipts.containsKey(id)) return receipts.get(id);
            var request = HttpRequest.newBuilder(URI.create(backend + path)).method(method, body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(request::header);
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            Result result = new Result(response.statusCode(), response.body());
            if (id != null && result.status / 100 == 2) receipts.put(id, result);
            return result;
        }

        @Override public void close() throws Exception {
            if (socket != null) socket.close().sync();
            server.close().sync();
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            forwarding.shutdownNow(); stub.close();
            if (failure.isDone()) throw new AssertionError("Independent provider fixture failed", failure.get());
        }
    }
}
