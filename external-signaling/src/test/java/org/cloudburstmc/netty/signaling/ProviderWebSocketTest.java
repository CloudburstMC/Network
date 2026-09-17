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
    private static final class ChangingProfileTransport extends ProviderClientTest.FakeTransport {
        volatile long revision = 1;
        boolean replaceDuringCapture;
        volatile List<TicketKey> keys = List.of();

        @Override public CompletionStage<HostProfileSnapshot> captureHostProfile() {
            long captured = revision;
            JsonObject profile = hostProfile().toCompletableFuture().join();
            profile.getAsJsonArray("candidates").get(0).getAsJsonObject().addProperty("port", 19132 + captured);
            HostProfileSnapshot snapshot = new HostProfileSnapshot(profile, captured, captured, () -> {
                if (closed.isDone()) throw new IllegalStateException("Native listener closed");
                if (revision != captured) throw new HostProfileSnapshotChangedException();
            });
            if (replaceDuringCapture) { replaceDuringCapture = false; revision++; }
            return CompletableFuture.completedFuture(snapshot);
        }

        @Override public CompletionStage<Void> installTicketKeys(List<TicketKey> keys) {
            this.keys = List.copyOf(keys);
            return super.installTicketKeys(keys);
        }
    }

    @Test
    void recoveredStartupRetainsCommittedKeysAndRepublishesChangedProfile(@TempDir Path rootDirectory) throws Exception {
        for (var mode : List.of(ProviderClient.ControlTransport.AUTO, ProviderClient.ControlTransport.HTTP)) try (Provider provider = new Provider()) {
            Path directory = rootDirectory.resolve(mode.name());
            var first = client(provider, directory, mode, new ProviderClientTest.FakeTransport());
            try { first.start().get(20, TimeUnit.SECONDS); }
            finally { first.stop().toCompletableFuture().get(15, TimeUnit.SECONDS); }
            try (var store = new ProviderStateStore(directory)) {
                var state = store.read();
                state.addProperty("profilePublishedAt", 1);
                state.addProperty("keyRequestId", UUID.randomUUID().toString());
                store.write(state);
            }
            var transport = new ChangingProfileTransport();
            long retirement = System.currentTimeMillis() + 300000;
            var retired = new JsonObject(); retired.addProperty("keyId", "T001"); retired.addProperty("retireAfter", retirement);
            provider.stub.heartbeatRetirements = new JsonArray(); provider.stub.heartbeatRetirements.add(retired);
            var committed = new AtomicInteger();
            var beforeFreshAcknowledgement = new AtomicReference<JsonObject>();
            provider.stub.heartbeatResponseHook = () -> {
                if (committed.incrementAndGet() == 1) {
                    transport.revision++;
                    provider.stub.heartbeatRetirements = null; // Retirement is delivered only in the superseded response.
                } else if (committed.get() == 2) {
                    try { beforeFreshAcknowledgement.set(JsonParser.parseString(Files.readString(directory.resolve("provider-state.json"))).getAsJsonObject()); }
                    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }
            };
            var recovered = client(provider, directory, mode, transport);
            try {
                assertEquals(2, recovered.start().get(20, TimeUnit.SECONDS).get("leaseGeneration").getAsLong());
                assertEquals(2, committed.get());
                var between = beforeFreshAcknowledgement.get();
                assertEquals(1, between.get("profilePublishedAt").getAsLong(), "Stale acknowledgement must not publish the old profile");
                assertFalse(between.has("keyRequestId"));
                assertEquals(2, between.getAsJsonArray("ticketKeys").size());
                assertEquals(retirement, between.getAsJsonArray("ticketKeys").get(0).getAsJsonObject().get("retireAfter").getAsLong());
                assertEquals(List.of("T001", "T002"), transport.keys.stream().map(ProviderTransport.TicketKey::keyId).toList());
                assertEquals(19134, provider.stub.lastHeartbeat.getAsJsonObject("hostProfile").getAsJsonArray("candidates").get(0).getAsJsonObject().get("port").getAsInt());
                var saved = JsonParser.parseString(Files.readString(directory.resolve("provider-state.json"))).getAsJsonObject();
                assertTrue(saved.get("profilePublishedAt").getAsLong() > 1);
                assertFalse(saved.has("pendingWebSocketOperation"));
                assertEquals(mode == ProviderClient.ControlTransport.AUTO ? "websocket" : "http", recovered.lastControlCarrier());
                assertEquals(0, transport.drains);
            } finally { provider.stub.heartbeatResponseHook = () -> { }; recovered.stop().toCompletableFuture().get(15, TimeUnit.SECONDS); }
        }
    }

    @Test
    void startupRecapturesChangesDuringCaptureAndBeforeSend(@TempDir Path directory) throws Exception {
        for (boolean duringCapture : List.of(true, false)) try (Provider provider = new Provider()) {
            var transport = new ChangingProfileTransport(); transport.replaceDuringCapture = duringCapture;
            var healthCalls = new AtomicInteger();
            var client = client(provider, directory.resolve(Boolean.toString(duringCapture)), ProviderClient.ControlTransport.AUTO,
                    transport, () -> {
                        if (!duringCapture && healthCalls.getAndIncrement() == 0) transport.revision++;
                        return new ProviderClient.Health(true, true, 20, 0, "nethernet", "fixture");
                    });
            try {
                client.start().get(20, TimeUnit.SECONDS);
                assertEquals(1, provider.stub.heartbeats, "Superseded bytes must not reach the provider");
                assertEquals(19134, provider.stub.lastHeartbeat.getAsJsonObject("hostProfile").getAsJsonArray("candidates").get(0).getAsJsonObject().get("port").getAsInt());
            } finally { client.stop().toCompletableFuture().get(15, TimeUnit.SECONDS); }
        }
    }

    @Test
    void repeatedCandidateChangesRetainLeaseAndScheduleFreshPublication(@TempDir Path directory) throws Exception {
        try (Provider provider = new Provider()) {
            provider.stub.checkInMillis = 120000;
            var transport = new ChangingProfileTransport();
            provider.stub.heartbeatResponseHook = () -> transport.revision++;
            var client = client(provider, directory, ProviderClient.ControlTransport.AUTO, transport);
            try {
                client.start().get(20, TimeUnit.SECONDS);
                assertEquals(3, provider.stub.heartbeats, "Each immediate exchange remains bounded");
                assertFalse(JsonParser.parseString(Files.readString(directory.resolve("provider-state.json"))).getAsJsonObject().has("profilePublishedAt"));
                var response = client.readiness().get(20, TimeUnit.SECONDS);
                assertEquals(120000, response.getAsJsonObject("checkIn").get("afterMillis").getAsLong());
                var freshCommit = new CompletableFuture<Void>();
                provider.stub.heartbeatResponseHook = () -> freshCommit.complete(null);
                freshCommit.get(5, TimeUnit.SECONDS);
                client.extensions().get(5, TimeUnit.SECONDS); // Wait behind the scheduled heartbeat on its serialized executor.
                assertTrue(JsonParser.parseString(Files.readString(directory.resolve("provider-state.json"))).getAsJsonObject().has("profilePublishedAt"));
                assertEquals(0, transport.drains);
            } finally { provider.stub.heartbeatResponseHook = () -> { }; client.stop().toCompletableFuture().get(15, TimeUnit.SECONDS); }
        }
    }

    @Test
    void closedListenerAfterCommittedHeartbeatStillFailsStartup(@TempDir Path directory) throws Exception {
        try (Provider provider = new Provider()) {
            var transport = new ChangingProfileTransport();
            provider.stub.heartbeatResponseHook = () -> transport.closed.complete(null);
            var client = client(provider, directory, ProviderClient.ControlTransport.AUTO, transport);
            try {
                var failure = assertThrows(ExecutionException.class, () -> client.start().get(20, TimeUnit.SECONDS));
                assertEquals(IllegalStateException.class, failure.getCause().getClass());
                assertEquals(1, provider.stub.heartbeats);
                assertFalse(JsonParser.parseString(Files.readString(directory.resolve("provider-state.json"))).getAsJsonObject().has("profilePublishedAt"));
            } finally { provider.stub.heartbeatResponseHook = () -> { }; client.stop().toCompletableFuture().get(15, TimeUnit.SECONDS); }
        }
    }

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
    void lostAcknowledgementRequiresOrdinaryRecoveryBeforeFreshHeartbeat(@TempDir Path directory) throws Exception {
        try (Provider provider = new Provider()) {
            ProviderClient client = client(provider, directory, ProviderClient.ControlTransport.AUTO, new ProviderClientTest.FakeTransport());
            try {
                client.start().get(20, TimeUnit.SECONDS);
                provider.dropAfterCommit = "heartbeat";
                provider.rejectAfterDrop = new Provider.Result(400, "{\"code\":\"invalid_heartbeat\"}");
                assertThrows(ExecutionException.class, () -> client.drain().get(10, TimeUnit.SECONDS));
                assertTrue(Files.readString(directory.resolve("provider-state.json")).contains("pendingWebSocketOperation"));
                client.drain().get(10, TimeUnit.SECONDS);
                assertEquals(2, provider.stub.generation, "Fresh signed operations require a recovered generation");
                assertEquals(List.of(1L, 2L), provider.generations);
                assertFalse(Files.readString(directory.resolve("provider-state.json")).contains("pendingWebSocketOperation"));
                assertEquals("draining", provider.stub.lastHeartbeat.get("state").getAsString());
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

    @Test
    void assistedPushUsesOwnedNativeCaptureAndSuppressesLatePreparedAnswer(@TempDir Path directory) throws Exception {
        try (Provider provider = new Provider()) {
            var started = new LinkedBlockingQueue<org.cloudburstmc.netty.signaling.control.AssistedJoin>();
            var nativeResult = new AtomicReference<CompletableFuture<String>>();
            provider.stub.extensionMetadata.add("org.nethernet.connectivity", connectivityFeedback(System.currentTimeMillis()));
            var transport = new ProviderClientTest.FakeTransport() {
                @Override public CompletionStage<JsonObject> hostProfile() {
                    return super.hostProfile().thenApply(profile -> {
                        profile.getAsJsonArray("candidates").get(0).getAsJsonObject().addProperty("address", "8.8.8.8");
                        return profile;
                    });
                }
                @Override public CompletionStage<HostProfileSnapshot> captureHostProfile() {
                    return hostProfile().thenApply(profile -> new HostProfileSnapshot(profile, 1, 0, profile.getAsJsonArray("candidates"), Set.of(4), () -> {
                        if (closed.isDone()) throw new IllegalStateException("Native listener closed");
                    }));
                }
                @Override public boolean supportsAssistedJoins() { return true; }
                @Override public CompletionStage<String> assistedJoin(org.cloudburstmc.netty.signaling.control.AssistedJoin join, Runnable guard) {
                    guard.run(); var result = new CompletableFuture<String>(); nativeResult.set(result); started.add(join); return result;
                }
            };
            ProviderClient client = new ProviderClient(new ProviderClient.Configuration(URI.create(provider.stub.origin), "nxs-admission-v1",
                    "assisted", ProviderClient.NEW_SERVICE, ProviderClient.BEARER_TOKEN, "independent-provider-token", null, null,
                    Map.of(), ProviderClient.ControlTransport.AUTO, false, "discovered", true), new ProviderStateStore(directory), transport,
                    () -> null, () -> new ProviderClient.Health(true,true,10,0,"nethernet","test"), message -> {});
            try {
                client.start().get(20,TimeUnit.SECONDS);
                var scheduled = ProviderClient.class.getDeclaredField("nextHeartbeat"); scheduled.setAccessible(true);
                long untilNext = scheduled.getLong(client) - System.nanoTime();
                assertTrue(untilNext > 0 && untilNext <= TimeUnit.MINUTES.toNanos(4),
                        "A 15-minute idle provider schedule must renew the original five-minute assisted authority early");
                JsonObject profile = provider.stub.lastHeartbeat.getAsJsonObject("hostProfile");
                assertEquals("nethernet.websocket-assisted.v1",profile.getAsJsonObject("statelessAdmission").get("assisted").getAsString());
                int upgrades = provider.upgrades.get();
                provider.stub.extensionMetadata.add("org.nethernet.connectivity", connectivityFeedback(null));
                client.readiness().get(10, TimeUnit.SECONDS);
                assertEquals(upgrades, provider.upgrades.get(), "An empty feedback gap must not remove assistance and reconnect");
                var generator = java.security.KeyPairGenerator.getInstance("EC"); generator.initialize(new java.security.spec.ECGenParameterSpec("secp384r1"));
                JsonObject join = new JsonObject(); join.addProperty("kind","assisted-join"); join.addProperty("version",1); join.addProperty("id","ab".repeat(16));
                join.add("instanceId",provider.stub.registration.get("instanceId")); join.addProperty("generation",1);
                join.add("incarnation",profile.getAsJsonObject("statelessAdmission").get("incarnation"));
                join.add("keyId",profile.get("credentialKeyId")); join.add("hostFingerprint",profile.get("dtlsFingerprint"));
                join.addProperty("expiresAt",System.currentTimeMillis()+14000); join.addProperty("networkId","1234");
                join.addProperty("cpk",Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded()));
                join.addProperty("localUfrag","assistedHost"); join.addProperty("localPassword","h".repeat(32));
                join.addProperty("offer","v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=setup:actpass\r\na=sctp-port:5000\r\na=max-message-size:262144\r\na=ice-ufrag:assistedClient\r\na=ice-pwd:"+"c".repeat(128)+"\r\na=fingerprint:sha-256 "+String.join(":",Collections.nCopies(32,"AA"))+"\r\na=candidate:1 1 UDP 123 127.0.0.1 19132 typ host\r\n");
                provider.socket.writeAndFlush(new TextWebSocketFrame(join.toString()));
                assertNotNull(started.poll(5,TimeUnit.SECONDS)); nativeResult.get().complete("actual-prepared-answer");
                JsonObject reply = provider.assistedReplies.poll(5,TimeUnit.SECONDS); assertNotNull(reply);
                assertTrue(reply.get("accepted").getAsBoolean()); assertEquals("actual-prepared-answer",reply.get("answer").getAsString());
                var authorityField = ProviderClient.class.getDeclaredField("assistedAuthority"); authorityField.setAccessible(true);
                Object originalAuthority = authorityField.get(client);
                var constructor = originalAuthority.getClass().getDeclaredConstructors()[0]; constructor.setAccessible(true);
                var components = originalAuthority.getClass().getRecordComponents();
                Object[] arguments = new Object[components.length];
                for (int i = 0; i < components.length; i++) {
                    var accessor = components[i].getAccessor(); accessor.setAccessible(true); arguments[i] = accessor.invoke(originalAuthority);
                }
                for (int expiredField : List.of(3, 4)) {
                    Object[] expired = arguments.clone();
                    expired[expiredField] = expiredField == 3 ? System.nanoTime() - 1 : System.currentTimeMillis() - 1;
                    authorityField.set(client, constructor.newInstance(expired));
                    join.addProperty("id", (expiredField == 3 ? "ef" : "01").repeat(16));
                    provider.socket.writeAndFlush(new TextWebSocketFrame(join.toString()));
                    var denied = provider.assistedReplies.poll(5, TimeUnit.SECONDS); assertNotNull(denied);
                    assertFalse(denied.get("accepted").getAsBoolean(), "Retained mode cannot extend either authority deadline");
                    assertTrue(started.isEmpty(), "Expired authority must not reach the native transport");
                }
                authorityField.set(client, originalAuthority);
                join.addProperty("id","cd".repeat(16));
                provider.socket.writeAndFlush(new TextWebSocketFrame(join.toString()));
                assertNotNull(started.poll(5,TimeUnit.SECONDS));
                client.stop().toCompletableFuture().get(10,TimeUnit.SECONDS);
                nativeResult.get().complete("late-after-close");
                assertNull(provider.assistedReplies.poll(200,TimeUnit.MILLISECONDS));
            } finally { client.stop().toCompletableFuture().get(10,TimeUnit.SECONDS); }
        }
    }

    private static JsonObject connectivityFeedback(Long checkedAt) {
        var checks = new JsonArray();
        if (checkedAt != null) {
            var check = new JsonObject(); check.addProperty("family", 4); check.addProperty("outcome", "not-established");
            check.addProperty("checkedAt", checkedAt); check.addProperty("expiresAt", checkedAt + 60000); checks.add(check);
        }
        var data = new JsonObject(); data.addProperty("candidateRevision", 1); data.add("checks", checks);
        var extension = new JsonObject(); extension.addProperty("version", 1); extension.addProperty("critical", false); extension.add("data", data);
        return extension;
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
        final LinkedBlockingQueue<JsonObject> assistedReplies = new LinkedBlockingQueue<>();
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
                                                    if (envelope.has("kind") && envelope.get("kind").getAsString().equals("assisted-join-result")) {
                                                        assistedReplies.add(envelope); return;
                                                    }
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
