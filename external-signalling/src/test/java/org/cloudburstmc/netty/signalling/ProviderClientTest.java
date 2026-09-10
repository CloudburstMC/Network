package org.cloudburstmc.netty.signalling;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProviderClientTest {
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
                            () -> new ProviderClient.Health(true, 20, 0, "nethernet", "fixture"), message -> {
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
            ProviderClient client = new ProviderClient(
                    new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Counts"),
                    new ProviderStateStore(path), transport,
                    () -> new ServerStatus("Global listing", 1234, "fixture", "world", 25000, 30000, 0),
                    () -> new ProviderClient.Health(true, 20, .9, "nethernet", "fixture", sample.get()), message -> {
            });
            try {
                client.start().get(20, TimeUnit.SECONDS);
                assertEquals(3, stub.lastHeartbeat.getAsJsonObject("playerCount").get("connectedPlayers").getAsInt());
                assertEquals(sample.get().sampledAt(),
                        stub.lastHeartbeat.getAsJsonObject("playerCount").get("sampledAt").getAsLong());
                assertEquals(20, stub.lastHeartbeat.get("capacity").getAsInt());
                assertEquals(25000, stub.lastHeartbeat.getAsJsonObject("serverStatus").get("players").getAsInt());
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
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            assertTrue(stub.draining);
            assertEquals(4, stub.lastHeartbeat.getAsJsonObject("playerCount").get("connectedPlayers").getAsInt());
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
                            () -> new ProviderClient.Health(true, 10, 0, "nethernet", "fixture"), message -> {
                    });
            try {
                JsonObject registration = client.start().get(20, TimeUnit.SECONDS);
                assertEquals("example-machine-1", registration.get("instanceId").getAsString());
                assertEquals("Bearer independent-provider-token", stub.challengeAuthorization);
                assertEquals(0, stub.challengeDifficulty);
                assertFalse(java.nio.file.Files.readString(path.resolve("provider-state.json"))
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
                    () -> new ProviderClient.Health(true, 10, 0, "nethernet", "fixture"), message -> {
            });
            try {
                JsonObject result = client.start().get(20, TimeUnit.SECONDS);
                assertFalse(result.has("ticketKey"));
                assertEquals(stub.extensionMetadata, result.getAsJsonObject("extensions"));
                assertFalse(java.nio.file.Files.readString(path.resolve("provider-state.json"))
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
                    () -> new ProviderClient.Health(true, 40, players.get() / 40.0, "nethernet", "fixture"),
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
                    () -> new ProviderClient.Health(true, 40, 0, "nethernet", "fixture"), message -> {
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

    static final class FakeTransport implements ProviderTransport {
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        final java.util.Queue<JsonObject> events = new java.util.concurrent.ConcurrentLinkedQueue<>();
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

        public CompletionStage<Void> installTicketKeys(List<TicketKey> keys) {
            installed = keys.size();
            ticketKeyId = keys.getLast().keyId();
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
                    () -> new ProviderClient.Health(true, 100, 0.1, "nethernet", "fixture"), message -> {
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
                    () -> new ProviderClient.Health(true, 100, 0, "nethernet", "fixture"), message -> {
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

    private static void eventually(java.util.function.BooleanSupplier condition) throws Exception {
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
                    (java.util.function.Supplier<ProviderClient.Health>) () -> new ProviderClient.Health(true, 100, 0.1,
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
            stub.desiredState = "future-state";
            stub.desiredRevision = 2;
            assertThrows(ExecutionException.class, () -> client.readiness().get(10, TimeUnit.SECONDS));
            assertEquals(0, host.admissions, "NXS has no per-join provider state");
            assertTrue(stub.appliedRevision < 2, "Unknown state cannot be acknowledged");
            stub.desiredState = "draining";
            client.readiness().get(10, TimeUnit.SECONDS);
            assertTrue(host.drains > 0);
            assertEquals(2, stub.appliedRevision);
            assertTrue(stub.acknowledgements > beforeAck);
            client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void outcomeOutageBacksOffWhileHeartbeatsContinue(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            FakeTransport host = new FakeTransport();
            stub.failOutcomes = true;
            var client = new ProviderClient(
                    new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Example"),
                    new ProviderStateStore(path), host, () -> null,
                    () -> new ProviderClient.Health(true, 10, 0, "nethernet", "fixture"), message -> {
            });
            try {
                client.start().get(20, TimeUnit.SECONDS);
                host.events.add(JsonParser.parseString(
                                "{\"ticketId\":\"fixture-ticket\",\"stage\":\"ticket.failed\",\"occurredAt\":\"2026-09-07T00:00:00Z\"}")
                        .getAsJsonObject());
                eventually(() -> stub.outcomeAttempts == 1);
                int before = stub.heartbeats;
                eventually(() -> stub.heartbeats >= before + 2);
                assertEquals(1, stub.outcomeAttempts, "Outcome failure must back off independently of heartbeat");
                JsonObject saved =
                        JsonParser.parseString(java.nio.file.Files.readString(path.resolve("provider-state.json")))
                                .getAsJsonObject();
                assertEquals(1, saved.getAsJsonArray("pendingEvents").size());
                assertTrue(saved.get("profilePublishedAt").getAsLong() > 0);
            } finally {
                stub.failOutcomes = false;
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            assertEquals(1, stub.events.size(), "Shutdown retries the durable outcome without losing it");
        }
    }

    @Test
    void localPersistenceFailureStopsPublicationAndClosesTransport(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            FakeTransport host = new FakeTransport();
            var client = new ProviderClient(
                    new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Example"),
                    new ProviderStateStore(path), host, () -> null,
                    () -> new ProviderClient.Health(true, 10, 0, "nethernet", "fixture"), message -> {
            });
            client.start().get(20, TimeUnit.SECONDS);
            java.nio.file.Files.move(path.resolve("provider-state.json"), path.resolve("saved-state.json"));
            java.nio.file.Files.createDirectory(path.resolve("provider-state.json"));
            assertThrows(ExecutionException.class, () -> client.readiness().get(10, TimeUnit.SECONDS));
            client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(host.closed.isDone());
        }
    }

}
