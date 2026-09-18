package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ProviderClientTest {
    @Test
    void anUnusableAdmissionKeyIsNotPersistedAndTheHostStartsAgain(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            var config = new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Key host",
                    ProviderClient.NEW_SERVICE, ProviderClient.BEARER_TOKEN, "independent-provider-token",
                    null, null, Map.of());
            Path directory = path.resolve("keys");

            FakeTransport refusing = new FakeTransport();
            refusing.refuseKeys = true;
            ProviderClient first = new ProviderClient(config, new ProviderStateStore(directory), refusing, () -> null,
                    () -> new ProviderClient.Health(true, true, 20, 0, "nethernet", "fixture"), message -> {
            });
            try {
                assertThrows(Exception.class, () -> first.start().get(20, TimeUnit.SECONDS));
            } finally {
                first.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }

            // The refused epoch must not have reached the state file, or every later start dies on it
            String persisted = Files.readString(directory.resolve("provider-state.json"));
            assertFalse(persisted.contains("\"ticketKeys\":[{"), "a refused admission key was persisted: " + persisted);

            ProviderClient second = new ProviderClient(config, new ProviderStateStore(directory), new FakeTransport(),
                    () -> null, () -> new ProviderClient.Health(true, true, 20, 0, "nethernet", "fixture"), message -> {
            });
            try {
                assertNotNull(second.start().get(20, TimeUnit.SECONDS));
            } finally {
                second.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void poolAttachmentsAndRemovedPublicEndpointsPreserveRuntimeIdentity(@TempDir Path path) throws Exception {
        for (boolean standalone : List.of(false, true)) {
            try (IndependentProviderStub stub = new IndependentProviderStub()) {
                stub.poolOnlyRegistration = !standalone;
                var config = new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Fleet host",
                        standalone ? ProviderClient.NEW_SERVICE : ProviderClient.ATTACH_INSTANCE,
                        ProviderClient.BEARER_TOKEN,
                        "independent-provider-token", standalone ? null : "EU", standalone ? null : "proxy", Map.of());
                JsonObject first = null;
                for (int generation = 1; generation <= 2; generation++) {
                    ProviderClient client = new ProviderClient(config,
                            new ProviderStateStore(path.resolve(standalone ? "standalone" : "pool")),
                            new FakeTransport(), () -> null,
                            () -> new ProviderClient.Health(true, true, 20, 0, "nethernet", "fixture"), message -> {
                    });
                    try {
                        JsonObject current = client.start().get(20, TimeUnit.SECONDS);
                        assertEquals(standalone && generation == 1, current.has("serviceId"));
                        assertEquals(standalone && generation == 1, current.has("publicAddress"));
                        assertEquals(generation, current.get("leaseGeneration").getAsInt());
                        if (first == null) {
                            first = current;
                        } else {
                            for (String field : List.of("instanceId", "registrationId")) {
                                assertEquals(first.get(field), current.get(field));
                            }
                        }
                        assertFalse(stub.lastHeartbeat.has("playerCount"),
                                "Missing runtime telemetry is unknown, not zero");
                    } finally {
                        client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    }
                    stub.poolOnlyRegistration = true;
                }
                assertEquals(1, stub.registrations);
            }
        }
    }

    @Test
    void runtimeCountsWakeCheckInsWithoutChangingPublicTotalsAndRemainCountedDuringDrain(
            @TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            stub.checkInMillis = 900000;
            AtomicReference<ProviderClient.PlayerCount> sample =
                    new AtomicReference<>(new ProviderClient.PlayerCount(3, System.currentTimeMillis()));
            var transport = new FakeTransport();
            transport.stateless = true;
            java.util.concurrent.atomic.AtomicBoolean accepting = new java.util.concurrent.atomic.AtomicBoolean(true);
            ProviderClient client = new ProviderClient(
                    new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Counts"),
                    new ProviderStateStore(path), transport,
                    () -> new ServerStatus("Global listing", 1234, "fixture", "world", 25000, 30000, 0),
                    () -> new ProviderClient.Health(true, accepting.get(), 20, .9, "nethernet", "fixture", sample.get()), message -> {
            });
            try {
                JsonObject extensions = com.google.gson.JsonParser.parseString("{\"org.example.location\":{\"version\":1,\"critical\":false,\"data\":{\"country\":\"NL\"}}}").getAsJsonObject();
                client.updateHeartbeatExtensions(extensions).get(10, TimeUnit.SECONDS);
                extensions.getAsJsonObject("org.example.location").getAsJsonObject("data").addProperty("country", "GB");
                client.start().get(20, TimeUnit.SECONDS);
                assertEquals("NL", stub.lastHeartbeat.getAsJsonObject("extensions").getAsJsonObject("org.example.location").getAsJsonObject("data").get("country").getAsString());
                assertEquals(3, stub.lastHeartbeat.getAsJsonObject("playerCount").get("connectedPlayers").getAsInt());
                assertEquals(sample.get().sampledAt(),
                        stub.lastHeartbeat.getAsJsonObject("playerCount").get("sampledAt").getAsLong());
                assertEquals(20, stub.lastHeartbeat.get("capacity").getAsInt());
                assertEquals(25000, stub.lastHeartbeat.getAsJsonObject("serverStatus").get("players").getAsInt());
                assertTrue(stub.lastHeartbeat.get("acceptingPlayers").getAsBoolean());
                accepting.set(false);
                client.requestStatusRefresh();
                eventually(() -> !stub.lastHeartbeat.get("acceptingPlayers").getAsBoolean());
                assertTrue(stub.lastHeartbeat.get("healthy").getAsBoolean());
                accepting.set(true);
                client.requestStatusRefresh();
                eventually(() -> stub.lastHeartbeat.get("acceptingPlayers").getAsBoolean());
                int before = stub.heartbeats;
                sample.set(new ProviderClient.PlayerCount(3, System.currentTimeMillis()));
                client.requestStatusRefresh();
                Thread.sleep(1200);
                assertEquals(before, stub.heartbeats, "Timestamp-only changes use the ordinary schedule");
                sample.set(new ProviderClient.PlayerCount(4, System.currentTimeMillis()));
                client.requestStatusRefresh();
                eventually(() -> stub.lastHeartbeat.getAsJsonObject("playerCount").get("connectedPlayers").getAsInt()
                        == 4);
                assertEquals(25000, stub.lastHeartbeat.getAsJsonObject("serverStatus").get("players").getAsInt());
                client.drain().get(10, TimeUnit.SECONDS);
                assertFalse(stub.lastHeartbeat.get("acceptingPlayers").getAsBoolean());
                sample.set(new ProviderClient.PlayerCount(5, System.currentTimeMillis()));
                client.requestStatusRefresh();
                eventually(() -> stub.lastHeartbeat.getAsJsonObject("playerCount").get("connectedPlayers").getAsInt() == 5);
                assertTrue(stub.lastHeartbeat.get("healthy").getAsBoolean());
                assertFalse(stub.lastHeartbeat.get("acceptingPlayers").getAsBoolean());
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            assertTrue(stub.draining);
            assertTrue(stub.lastHeartbeat.get("healthy").getAsBoolean());
            assertFalse(stub.lastHeartbeat.get("acceptingPlayers").getAsBoolean());
            assertEquals(5, stub.lastHeartbeat.getAsJsonObject("playerCount").get("connectedPlayers").getAsInt());
        }
    }

    @Test
    void usesProviderNeutralBearerAuthorizationWithoutPowOrPersistingTheToken(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            var config =
                    new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Hosted customer",
                            ProviderClient.NEW_SERVICE, ProviderClient.BEARER_TOKEN, "independent-provider-token", "EU",
                            "customers", Map.of("plan", "premium"));
            ProviderClient client =
                    new ProviderClient(config, new ProviderStateStore(path), new FakeTransport(), () -> null,
                            () -> new ProviderClient.Health(true, true, 10, 0, "nethernet", "fixture"), message -> {
                    });
            try {
                JsonObject registration = client.start().get(20, TimeUnit.SECONDS);
                assertEquals("example-machine-1", registration.get("instanceId").getAsString());
                assertEquals("Bearer independent-provider-token", stub.challengeAuthorization);
                assertEquals(0, stub.challengeDifficulty);
                assertFalse(Files.readString(path.resolve("provider-state.json"))
                        .contains("independent-provider-token"));
                assertFalse(config.toString().contains("independent-provider-token"));
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void optionalExtensionsRemainOpaqueAndExplicit(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            stub.extensionMetadata = JsonParser.parseString(
                    "{\"org.example.operator\":{\"version\":1,\"critical\":false,\"data\":{\"message\":\"optional\",\"operations\":{\"inspect\":\""
                            + stub.origin + "/example/extension\"}}}}").getAsJsonObject();
            ProviderClient client = new ProviderClient(
                    new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Example"),
                    new ProviderStateStore(path), new FakeTransport(), () -> null,
                    () -> new ProviderClient.Health(true, true, 10, 0, "nethernet", null), message -> {
            });
            try {
                JsonObject extensions = JsonParser.parseString(
                        "{\"org.example.location\":{\"version\":1,\"critical\":false,\"data\":{\"location\":null}}}")
                        .getAsJsonObject();
                client.updateHeartbeatExtensions(extensions).get(10, TimeUnit.SECONDS);
                JsonObject result = client.start().get(20, TimeUnit.SECONDS);
                assertEquals(extensions, stub.lastHeartbeat.getAsJsonObject("extensions"),
                        "Explicit null in opaque extension data must survive wire serialization");
                assertFalse(stub.lastHeartbeat.has("build"), "Unspecified optional health fields stay omitted");
                assertFalse(result.has("ticketKey"));
                assertEquals(stub.extensionMetadata, result.getAsJsonObject("extensions"));
                assertFalse(Files.readString(path.resolve("provider-state.json"))
                        .contains("org.example.operator"));
                assertEquals(0, stub.extensionRequests);
                assertTrue(client.readiness().get(10, TimeUnit.SECONDS).get("routable").getAsBoolean());
                client.extensionRequest("org.example.operator", "inspect", "POST", new JsonObject())
                        .get(10, TimeUnit.SECONDS);
                assertEquals(1, stub.extensionRequests);
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void followsProviderScheduleWithoutIdlePollingAndWakesForPlayers(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            stub.checkInMillis = 900000;
            AtomicInteger players = new AtomicInteger(0);
            FakeTransport transport = new FakeTransport();
            transport.stateless = true;
            ProviderClient client = new ProviderClient(
                    new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Scheduled"),
                    new ProviderStateStore(path), transport,
                    () -> new ServerStatus("Scheduled", 1234, "fixture", "world", players.get(), 40, 0),
                    () -> new ProviderClient.Health(true, true, 40, players.get() / 40.0, "nethernet", "fixture"),
                    message -> {
                    });
            try {
                client.start().get(20, TimeUnit.SECONDS);
                assertEquals(0, stub.controlPolls);
                Thread.sleep(2200);
                assertEquals(1, stub.heartbeats);
                assertEquals(0, stub.controlPolls);
                for (int i = 0; i < 100; i++) {
                    client.requestStatusRefresh();
                }
                Thread.sleep(1200);
                assertEquals(1, stub.heartbeats, "Unchanged local refreshes must not send requests");
                stub.checkInMillis = 1000;
                players.set(1);
                client.requestStatusRefresh();
                eventually(() -> stub.lastHeartbeat.getAsJsonObject("serverStatus").get("players").getAsInt() == 1);
                int busyBefore = stub.heartbeats;
                eventually(() -> stub.heartbeats > busyBefore && stub.controlPolls == 0);
                stub.checkInMillis = 3600000;
                players.set(0);
                client.requestStatusRefresh();
                eventually(() -> stub.lastHeartbeat.getAsJsonObject("serverStatus").get("players").getAsInt() == 0);
                Thread.sleep(2200);
                int before = stub.heartbeats;
                int polls = stub.controlPolls;
                Thread.sleep(2200);
                assertEquals(before, stub.heartbeats);
                assertEquals(polls, stub.controlPolls);
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            assertTrue(stub.draining, "Orderly shutdown still reports drain while the timer is asleep");
            int beforeRestart = stub.heartbeats;
            FakeTransport replacement = new FakeTransport();
            replacement.stateless = true;
            stub.checkInMillis = 900000;
            ProviderClient resumed = new ProviderClient(
                    new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Scheduled"),
                    new ProviderStateStore(path), replacement,
                    () -> new ServerStatus("Restarted", 1234, "fixture", "world", 0, 40, 0),
                    () -> new ProviderClient.Health(true, true, 40, 0, "nethernet", "fixture"), message -> {
            });
            try {
                resumed.start().get(20, TimeUnit.SECONDS);
                assertEquals(2, stub.generation);
                assertEquals(beforeRestart + 1, stub.heartbeats,
                        "Startup must publish immediately despite the previous one-hour schedule");
                assertEquals("Restarted", stub.lastHeartbeat.getAsJsonObject("serverStatus").get("name").getAsString());
            } finally {
                resumed.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }

        }
    }

    static class FakeTransport implements ProviderTransport {
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        final Queue<JsonObject> events = new ConcurrentLinkedQueue<>();
        volatile int installed, applied, admissions, drains;
        boolean stateless = true;
        String ticketKeyId = "T001";
        volatile ApplyResult result = ApplyResult.APPLIED;

        public CompletionStage<JsonObject> hostProfile() {
            JsonObject p = new JsonObject();
            p.addProperty("credentialKeyId", ticketKeyId);
            p.addProperty("dtlsFingerprint", "sha-256 " + String.join(":", Collections.nCopies(32, "11")));
            p.addProperty("sctpPort", 5000);
            p.addProperty("maxMessageSize", 262144);
            JsonObject c = new JsonObject();
            c.addProperty("foundation", "fixture");
            c.addProperty("component", 1);
            c.addProperty("protocol", "udp");
            c.addProperty("priority", 100);
            c.addProperty("address", "127.0.0.1");
            c.addProperty("port", 19133);
            c.addProperty("type", "host");
            JsonArray candidates = new JsonArray();
            candidates.add(c);
            p.add("candidates", candidates);
            if (stateless) {
                JsonObject cap = new JsonObject();
                cap.addProperty("capability", "nethernet.stateless-admission.v1");
                cap.addProperty("incarnation", "0123456789abcdef0123456789abcdef");
                p.add("statelessAdmission", cap);
            }
            return CompletableFuture.completedFuture(p);
        }

        boolean refuseKeys;

        public CompletionStage<Void> installTicketKeys(List<TicketKey> keys) {
            if (refuseKeys) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Unusable admission key"));
            }
            installed = keys.size();
            ticketKeyId = keys.get(keys.size() - 1).keyId();
            return CompletableFuture.completedFuture(null);
        }

        public CompletionStage<ApplyResult> applyState(String state) {
            applied++;
            String kind = state;
            if (kind.equals("join-admission")) {
                admissions++;
            }
            if (kind.equals("draining")) {
                drains++;
            }
            return CompletableFuture.completedFuture(kind.equals("join-admission") ? result : ApplyResult.APPLIED);
        }

        public List<JsonObject> pollEvents() {
            List<JsonObject> batch = new ArrayList<>();
            for (JsonObject event; (event = events.poll()) != null; ) {
                batch.add(event);
            }
            return batch;
        }

        public CompletionStage<Void> drain() {
            return CompletableFuture.completedFuture(null);
        }

        public CompletionStage<Void> close() {
            closed.complete(null);
            return closed;
        }
    }

    @Test
    void portableRegistrationRefreshRotationAndRestart(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            AtomicInteger players = new AtomicInteger(2);
            FakeTransport host = new FakeTransport();
            var config = new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Example");
            ProviderClient client = new ProviderClient(config, new ProviderStateStore(path), host,
                    () -> new ServerStatus("Example", 1234, "preview-fixture", "", players.get(), 40, 0),
                    () -> new ProviderClient.Health(true, true, 100, 0.1, "nethernet", "fixture"), message -> {
            });
            JsonObject registration = client.start().get(20, TimeUnit.SECONDS);
            assertEquals("example-machine-1", registration.get("instanceId").getAsString());
            assertEquals(1, stub.registrations);
            assertEquals(1, host.installed);
            assertEquals(100, stub.lastHeartbeat.get("capacity").getAsInt());
            assertEquals(40, stub.lastHeartbeat.getAsJsonObject("serverStatus").get("maxPlayers").getAsInt());
            players.set(7);
            eventually(() -> stub.lastHeartbeat.getAsJsonObject("serverStatus").get("players").getAsInt() == 7);
            client.setServerStatus(new ServerStatus("Renamed", 1234, "preview-fixture", "World", 8, 30, 2));
            eventually(() -> "Renamed".equals(
                    stub.lastHeartbeat.getAsJsonObject("serverStatus").get("name").getAsString()));
            assertTrue(client.readiness().get(10, TimeUnit.SECONDS).get("routable").getAsBoolean());
            client.rotateMachineKey().get(10, TimeUnit.SECONDS);
            client.drain().get(10, TimeUnit.SECONDS);
            assertTrue(stub.draining);
            client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            FakeTransport restarted = new FakeTransport();
            ProviderClient resumed = new ProviderClient(config, new ProviderStateStore(path), restarted,
                    () -> new ServerStatus("Restarted", 1234, "preview-fixture", "", 1, 50, 1),
                    () -> new ProviderClient.Health(true, true, 100, 0, "nethernet", "fixture"), message -> {
            });
            try {
                assertEquals("example-machine-1",
                        resumed.start().get(20, TimeUnit.SECONDS).get("instanceId").getAsString());
                assertEquals(1, stub.registrations);
                assertEquals(2, stub.generation);
            } finally {
                resumed.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void retirementRepliesAndRestartNeverExtendPersistedOriginalCutoff(@TempDir Path path) throws Exception {
        class CapturingTransport extends FakeTransport {
            final List<List<TicketKey>> snapshots = new CopyOnWriteArrayList<>();

            @Override
            public CompletionStage<Void> installTicketKeys(List<TicketKey> keys) {
                snapshots.add(List.copyOf(keys));
                return super.installTicketKeys(keys);
            }

            List<TicketKey> latest() {
                return snapshots.get(snapshots.size() - 1);
            }
        }
        try (var stub = new IndependentProviderStub()) {
            stub.checkInMillis = 3_600_000;
            var config = new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Rotation");
            var firstTransport = new CapturingTransport();
            var first = new ProviderClient(config, new ProviderStateStore(path), firstTransport, () -> null,
                    () -> new ProviderClient.Health(true, true, 10, 0, "nethernet", "fixture"), message -> { });
            long originalCutoff;
            try {
                first.start().get(20, TimeUnit.SECONDS);
                originalCutoff = System.currentTimeMillis() + 300_000;
                JsonObject oldKey = new JsonObject();
                oldKey.addProperty("keyId", "T001");
                oldKey.addProperty("retireAfter", originalCutoff);
                stub.heartbeatRetirements = new JsonArray();
                stub.heartbeatRetirements.add(oldKey);
                first.rotateTicketKey().get(10, TimeUnit.SECONDS);
                assertEquals(List.of("T001", "T002"), firstTransport.latest().stream().map(ProviderTransport.TicketKey::keyId).toList());
                assertEquals(originalCutoff, firstTransport.latest().get(0).retireAfter());
                assertEquals(Long.MAX_VALUE, firstTransport.latest().get(1).retireAfter());
                // Repeated responses are idempotent; even a provider erroneously rebasing a later
                // response cannot extend the original local retirement bound.
                first.readiness().get(10, TimeUnit.SECONDS);
                JsonArray laterReply = stub.heartbeatRetirements.deepCopy();
                laterReply.get(0).getAsJsonObject().addProperty("retireAfter", originalCutoff + 300_000);
                stub.heartbeatRetirements = laterReply;
                first.readiness().get(10, TimeUnit.SECONDS);
                assertEquals(originalCutoff, firstTransport.latest().get(0).retireAfter());
            } finally {
                first.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            var saved = JsonParser.parseString(Files.readString(path.resolve("provider-state.json")))
                    .getAsJsonObject().getAsJsonArray("ticketKeys");
            assertEquals(originalCutoff, saved.get(0).getAsJsonObject().get("retireAfter").getAsLong());
            var resumedTransport = new CapturingTransport();
            var resumed = new ProviderClient(config, new ProviderStateStore(path), resumedTransport, () -> null,
                    () -> new ProviderClient.Health(true, true, 10, 0, "nethernet", "fixture"), message -> { });
            try {
                resumed.start().get(20, TimeUnit.SECONDS);
                resumed.readiness().get(10, TimeUnit.SECONDS);
                assertEquals(2, stub.generation);
                assertFalse(resumedTransport.snapshots.isEmpty());
                for (var snapshot : resumedTransport.snapshots) {
                    assertEquals(List.of("T001", "T002"), snapshot.stream().map(ProviderTransport.TicketKey::keyId).toList());
                    assertEquals(originalCutoff, snapshot.get(0).retireAfter(),
                            "The very first native installation after restart must retain the saved deadline");
                    assertEquals(Long.MAX_VALUE, snapshot.get(1).retireAfter());
                }
            } finally {
                resumed.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static void eventually(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(40);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for provider lifecycle");
    }

    @Test
    void failedRefreshRetriesAndRejectsUnknownStateWithoutAcknowledgingIt(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            AtomicInteger players = new AtomicInteger(2);
            FakeTransport host = new FakeTransport();
            var config = new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Example");
            var health =
                    (Supplier<ProviderClient.Health>) () -> new ProviderClient.Health(true, true, 100, 0.1,
                            "nethernet", "fixture");
            ProviderClient client = new ProviderClient(config, new ProviderStateStore(path), host, () -> {
                if (players.get() < 0) {
                    throw new IllegalStateException("query unavailable");
                }
                return new ServerStatus("Example", 1234, "fixture", "", players.get(), 40, 0);
            }, health, message -> {
            });
            client.start().get(20, TimeUnit.SECONDS);
            players.set(-1);
            eventually(() -> !stub.lastHeartbeat.has("serverStatus"));
            int before = stub.heartbeats;
            players.set(5);
            stub.failHeartbeats = 1;
            for (int i = 0; i < 500; i++) {
                client.requestStatusRefresh();
            }
            eventually(() -> stub.lastHeartbeat.has("serverStatus")
                    && stub.lastHeartbeat.getAsJsonObject("serverStatus").get("players").getAsInt() == 5);
            assertTrue(stub.heartbeats - before <= 2, "Burst must coalesce within heartbeat cadence");
            int beforeAck = stub.acknowledgements;
            for (String desired : List.of("future-state", "draining", "closed")) {
                stub.desiredState = desired;
                stub.desiredRevision = 2;
                client.readiness().get(10, TimeUnit.SECONDS);
                assertEquals(0, host.drains, "Provider responses must not control the game listener");
                assertEquals("serving", stub.lastHeartbeat.get("state").getAsString());
            }
            assertEquals(0, host.admissions, "Stateless NXS has no per-join provider work");
            assertTrue(stub.appliedRevision < 2, "Host must not acknowledge an instruction it did not apply");
            assertEquals(beforeAck, stub.acknowledgements);
            client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void outcomeOutageBacksOffWhileHeartbeatsContinue(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            stub.extensionMetadata = JsonParser.parseString("{\"org.nethernet.connectivity\":{\"version\":1,\"critical\":false,\"data\":{}}}").getAsJsonObject();
            FakeTransport host = new FakeTransport();
            stub.failOutcomes = true;
            var client = new ProviderClient(
                    new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Example"),
                    new ProviderStateStore(path), host, () -> null,
                    () -> new ProviderClient.Health(true, true, 10, 0, "nethernet", "fixture"), message -> {
            });
            try {
                client.start().get(20, TimeUnit.SECONDS);
                JsonObject observed = JsonParser.parseString("""
                        {"ticketId":"fixture-ticket","stage":"ticket.data_channels_open",
                         "occurredAt":"2026-09-07T00:00:00Z","reason":"both_channels_open",
                         "remoteAddress":"2001:db8::1234","remotePort":54321,"sdp":"must-not-persist"}
                        """).getAsJsonObject();
                host.events.add(observed);
                eventually(() -> stub.outcomeAttempts == 1);
                int before = stub.heartbeats;
                eventually(() -> stub.heartbeats >= before + 2);
                assertEquals(1, stub.outcomeAttempts, "Outcome failure must back off independently of heartbeat");
                JsonObject saved =
                        JsonParser.parseString(Files.readString(path.resolve("provider-state.json")))
                                .getAsJsonObject();
                assertEquals(1, saved.getAsJsonArray("pendingEvents").size());
                JsonObject pending = saved.getAsJsonArray("pendingEvents").get(0).getAsJsonObject();
                assertEquals("2001:db8::1234", pending.get("remoteAddress").getAsString());
                assertEquals(54321, pending.get("remotePort").getAsInt());
                assertFalse(pending.has("sdp"));
                assertTrue(saved.get("profilePublishedAt").getAsLong() > 0);
            } finally {
                stub.failOutcomes = false;
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            assertEquals(1, stub.events.size(), "Shutdown retries the durable outcome without losing it");
            assertEquals("2001:db8::1234", stub.events.get(0).get("remoteAddress").getAsString());
            assertEquals(54321, stub.events.get(0).get("remotePort").getAsInt());
            assertFalse(stub.events.get(0).has("sdp"));
        }
    }

    @Test
    void olderProviderReceivesOriginalOutcomeFields(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            FakeTransport host = new FakeTransport();
            var client = new ProviderClient(
                    new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Example"),
                    new ProviderStateStore(path), host, () -> null,
                    () -> new ProviderClient.Health(true, true, 10, 0, "nethernet", "fixture"), ignored -> { });
            try {
                client.start().get(20, TimeUnit.SECONDS);
                client.readiness().get(10, TimeUnit.SECONDS);
                assertEquals(1, stub.appliedRevision, "Legacy heartbeat acknowledgement is retained");
                host.events.add(JsonParser.parseString("""
                        {"ticketId":"fixture-ticket","stage":"ticket.data_channels_open",
                         "occurredAt":"2026-09-07T00:00:00Z","remoteAddress":"8.8.8.8","remotePort":54321}
                        """).getAsJsonObject());
                eventually(() -> !stub.events.isEmpty());
                assertEquals(Set.of("ticketId", "stage", "occurredAt"), stub.events.get(0).keySet());
            } finally { client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        }
    }

    @Test
    void localPersistenceFailureStopsPublicationAndClosesTransport(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            FakeTransport host = new FakeTransport();
            var client = new ProviderClient(
                    new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Example"),
                    new ProviderStateStore(path), host, () -> null,
                    () -> new ProviderClient.Health(true, true, 10, 0, "nethernet", "fixture"), message -> {
            });
            client.start().get(20, TimeUnit.SECONDS);
            Files.move(path.resolve("provider-state.json"), path.resolve("saved-state.json"));
            Files.createDirectory(path.resolve("provider-state.json"));
            assertThrows(ExecutionException.class, () -> client.readiness().get(10, TimeUnit.SECONDS));
            client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(host.closed.isDone());
        }
    }

}
