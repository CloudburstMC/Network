package org.cloudburstmc.netty.signaling;

import org.cloudburstmc.netty.signaling.admission.AdmissionKey;
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
    static final ProviderClient.Software SOFTWARE = new ProviderClient.Software("fixture", "1.0");

    static ProviderClient.ConnectedPlayers connected(int count) {
        return new ProviderClient.ConnectedPlayers(count, System.currentTimeMillis());
    }
    @Test
    void repeatedStatusReadFailuresRecoverOnlyWhenStatusCanBeRead(@TempDir Path path)
            throws Exception {
        try (var stub = new IndependentProviderStub()) {
            var broken = new java.util.concurrent.atomic.AtomicBoolean();
            var logs = new CopyOnWriteArrayList<ProviderDiagnostic>();
            var client =
                    new ProviderClient(
                            ProviderClient.Configuration.builder(URI.create(stub.origin))
                                    .label("Example")
                                    .build(),
                            new ProviderStateStore(path),
                            new FakeTransport(),
                            () -> {
                                if (broken.get()) {
                                    throw new IllegalStateException("private failure details");
                                }
                                return new ServerStatus("Example", "", 40, 0);
                            },
                            () -> new ProviderClient.Health(true, 100, SOFTWARE, connected(0)),
                            logs::add);
            try {
                client.start().get(20, TimeUnit.SECONDS);
                broken.set(true);
                client.readiness().get(10, TimeUnit.SECONDS);
                client.readiness().get(10, TimeUnit.SECONDS);
                assertEquals(
                        1,
                        logs.stream()
                                .filter(
                                        e ->
                                                e.level() == ProviderDiagnostic.Level.WARN
                                                        && e.message()
                                                                .startsWith(
                                                                        ProviderLog.Operation
                                                                                .SERVER_STATUS
                                                                                .failure))
                                .count());
                assertTrue(
                        logs.stream()
                                .anyMatch(
                                        e ->
                                                e.level() == ProviderDiagnostic.Level.DEBUG
                                                        && e.message()
                                                                .startsWith(
                                                                        ProviderLog.Operation
                                                                                .SERVER_STATUS
                                                                                .failure)));
                assertFalse(
                        logs.stream()
                                .anyMatch(
                                        e ->
                                                e.message()
                                                        .equals(
                                                                ProviderLog.Operation.SERVER_STATUS
                                                                        .recovery)));
                broken.set(false);
                client.readiness().get(10, TimeUnit.SECONDS);
                client.readiness().get(10, TimeUnit.SECONDS);
                assertEquals(
                        1,
                        logs.stream()
                                .filter(
                                        e ->
                                                e.level() == ProviderDiagnostic.Level.INFO
                                                        && e.message()
                                                                .equals(
                                                                        ProviderLog.Operation
                                                                                .SERVER_STATUS
                                                                                .recovery))
                                .count());
                assertTrue(
                        logs.stream()
                                .noneMatch(e -> e.message().contains("private failure details")));
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void anUnusableAdmissionKeyIsNotPersistedAndTheHostStartsAgain(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            var config = ProviderClient.Configuration.builder(URI.create(stub.origin))
                    .label("Key host")
                    .authorizationToken("independent-provider-token")
                    .build();
            Path directory = path.resolve("keys");

            FakeTransport refusing = new FakeTransport();
            refusing.refuseKeys = true;
            ProviderClient first = new ProviderClient(config, new ProviderStateStore(directory), refusing, () -> null,
                    () -> new ProviderClient.Health(true, 20, SOFTWARE, connected(0)), message -> {
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
                    () -> null, () -> new ProviderClient.Health(true, 20, SOFTWARE, connected(0)), message -> {
            });
            try {
                assertNotNull(second.start().get(20, TimeUnit.SECONDS));
            } finally {
                second.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void attachedInstancesAndRemovedPublicEndpointsPreserveRuntimeIdentity(@TempDir Path path) throws Exception {
        for (boolean standalone : List.of(false, true)) {
            try (IndependentProviderStub stub = new IndependentProviderStub()) {
                stub.poolOnlyRegistration = !standalone;
                stub.attachToken = !standalone;
                var config = ProviderClient.Configuration.builder(URI.create(stub.origin))
                        .label("Fleet host")
                        .authorizationToken("independent-provider-token")
                        .build();
                ProviderClient.Registration first = null;
                for (int generation = 1; generation <= 2; generation++) {
                    ProviderClient client = new ProviderClient(config,
                            new ProviderStateStore(path.resolve(standalone ? "standalone" : "pool")),
                            new FakeTransport(), () -> null,
                            () -> new ProviderClient.Health(true, 20, SOFTWARE, connected(0)), message -> {
                    });
                    try {
                        var current = client.start().get(20, TimeUnit.SECONDS);
                        assertEquals(standalone && generation == 1, current.serviceId() != null);
                        assertEquals(standalone && generation == 1, current.publicAddress() != null);
                        assertEquals(generation, current.leaseGeneration());
                        if (first == null) {
                            first = current;
                        } else {
                            assertEquals(first.instanceId(), current.instanceId());
                            assertEquals(first.registrationId(), current.registrationId());
                        }
                        assertEquals(0, stub.lastHeartbeat.getAsJsonObject("connectedPlayers").get("count").getAsInt());
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
            AtomicReference<ProviderClient.ConnectedPlayers> sample =
                    new AtomicReference<>(new ProviderClient.ConnectedPlayers(3, System.currentTimeMillis()));
            var transport = new FakeTransport();
            transport.stateless = true;
            java.util.concurrent.atomic.AtomicBoolean accepting = new java.util.concurrent.atomic.AtomicBoolean(true);
            ProviderClient client = new ProviderClient(
                    ProviderClient.Configuration.builder(URI.create(stub.origin))
                            .label("Counts")
                            .build(),
                    new ProviderStateStore(path), transport,
                    () -> new ServerStatus("Global listing", "world", 30000, 0),
                    () -> new ProviderClient.Health(accepting.get(), 20, SOFTWARE, sample.get()), message -> {
            });
            try {
                JsonObject extensions = com.google.gson.JsonParser.parseString("{\"org.example.location\":{\"version\":1,\"critical\":false,\"data\":{\"country\":\"NL\"}}}").getAsJsonObject();
                client.updateHeartbeatExtensions(extensions).get(10, TimeUnit.SECONDS);
                extensions.getAsJsonObject("org.example.location").getAsJsonObject("data").addProperty("country", "GB");
                client.start().get(20, TimeUnit.SECONDS);
                assertEquals("NL", stub.lastHeartbeat.getAsJsonObject("extensions").getAsJsonObject("org.example.location").getAsJsonObject("data").get("country").getAsString());
                assertEquals(3, stub.lastHeartbeat.getAsJsonObject("connectedPlayers").get("count").getAsInt());
                assertEquals(sample.get().sampledAt(),
                        stub.lastHeartbeat.getAsJsonObject("connectedPlayers").get("sampledAt").getAsLong());
                assertEquals(20, stub.lastHeartbeat.get("capacity").getAsInt());
                assertFalse(stub.lastHeartbeat.getAsJsonObject("serverStatus").has("players"));
                assertTrue(stub.lastHeartbeat.get("acceptingPlayers").getAsBoolean());
                accepting.set(false);
                client.requestStatusRefresh();
                eventually(() -> !stub.lastHeartbeat.get("acceptingPlayers").getAsBoolean());
                assertFalse(stub.lastHeartbeat.has("healthy"));
                accepting.set(true);
                client.requestStatusRefresh();
                eventually(() -> stub.lastHeartbeat.get("acceptingPlayers").getAsBoolean());
                int before = stub.heartbeats;
                sample.set(new ProviderClient.ConnectedPlayers(3, System.currentTimeMillis()));
                client.requestStatusRefresh();
                Thread.sleep(1200);
                assertEquals(before, stub.heartbeats, "Timestamp-only changes use the ordinary schedule");
                sample.set(new ProviderClient.ConnectedPlayers(4, System.currentTimeMillis()));
                client.requestStatusRefresh();
                eventually(() -> stub.lastHeartbeat.getAsJsonObject("connectedPlayers").get("count").getAsInt()
                        == 4);
                assertFalse(stub.lastHeartbeat.getAsJsonObject("serverStatus").has("players"));
                client.drain().get(10, TimeUnit.SECONDS);
                assertFalse(stub.lastHeartbeat.get("acceptingPlayers").getAsBoolean());
                sample.set(new ProviderClient.ConnectedPlayers(5, System.currentTimeMillis()));
                client.requestStatusRefresh();
                eventually(() -> stub.lastHeartbeat.getAsJsonObject("connectedPlayers").get("count").getAsInt() == 5);
                assertFalse(stub.lastHeartbeat.has("healthy"));
                assertFalse(stub.lastHeartbeat.get("acceptingPlayers").getAsBoolean());
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            assertTrue(stub.draining);
            assertFalse(stub.lastHeartbeat.has("healthy"));
            assertFalse(stub.lastHeartbeat.get("acceptingPlayers").getAsBoolean());
            assertEquals(5, stub.lastHeartbeat.getAsJsonObject("connectedPlayers").get("count").getAsInt());
        }
    }

    @Test
    void deregistersOnShutdownOnlyWhenItsStateWillNotPersist(@TempDir Path path) throws Exception {
        for (boolean deregister : List.of(false, true)) {
            try (IndependentProviderStub stub = new IndependentProviderStub()) {
                var config = ProviderClient.Configuration.builder(URI.create(stub.origin))
                        .label("Ephemeral")
                        .deregisterOnShutdown(deregister)
                        .build();
                Path directory = path.resolve(Boolean.toString(deregister));
                var transport = new FakeTransport();
                ProviderClient client = new ProviderClient(config, new ProviderStateStore(directory),
                        transport, () -> null, () -> new ProviderClient.Health(true, 10, SOFTWARE, connected(0)),
                        message -> {
                        });
                client.start().get(20, TimeUnit.SECONDS);
                // A report the provider will not take must not keep the instance registered
                stub.failOutcomes = true;
                JsonObject event = new JsonObject();
                event.addProperty("stage", "ticket.data_channels_open");
                event.addProperty("ticketId", "undeliverable");
                event.addProperty("occurredAt", System.currentTimeMillis());
                transport.events.add(event);
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals(deregister, stub.operationsSeen.contains("/example/deregister"));
                try (ProviderStateStore state = new ProviderStateStore(directory)) {
                    assertEquals(!deregister, state.read().has("registration"));
                }
            }
        }
    }

    @Test
    void heartbeatsUseTheStandardFormatAndPauseWithoutChangingGeneration(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            stub.checkInMillis = 60000;
            var accepting = new java.util.concurrent.atomic.AtomicBoolean(true);
            var transport = new FakeTransport();
            transport.stateless = true;
            ProviderClient client = new ProviderClient(
                    ProviderClient.Configuration.builder(URI.create(stub.origin))
                            .label("Compact")
                            .build(),
                    new ProviderStateStore(path), transport,
                    () -> new ServerStatus("Game", "world", 30, 0),
                    () -> new ProviderClient.Health(accepting.get(), 20, SOFTWARE,
                            new ProviderClient.ConnectedPlayers(3, System.currentTimeMillis())), message -> { });
            try {
                client.start().get(20, TimeUnit.SECONDS);
                long generation = stub.generation;
                for (String field : List.of("healthy", "load", "protocolVersion", "checkInVersion", "state",
                        "appliedStateRevision")) {
                    assertFalse(stub.lastHeartbeat.has(field), field);
                }
                for (String field : List.of("protocol", "version", "players")) {
                    assertFalse(stub.lastHeartbeat.getAsJsonObject("serverStatus").has(field), field);
                }
                assertTrue(stub.lastHeartbeat.has("gameOutcomes"));
                assertEquals("1.0", stub.lastHeartbeat.getAsJsonObject("software").get("version").getAsString());
                accepting.set(false);
                client.readiness().get(10, TimeUnit.SECONDS);
                assertFalse(stub.lastHeartbeat.get("acceptingPlayers").getAsBoolean());
                assertFalse(stub.lastHeartbeat.has("gameOutcomes"));
                assertFalse(stub.lastHeartbeat.has("software"), "Unchanged software is not repeated");
                assertEquals(3, stub.lastHeartbeat.getAsJsonObject("connectedPlayers").get("count").getAsInt());
                assertTrue(stub.lastHeartbeat.has("hostProfileRevision"));
                assertFalse(stub.lastHeartbeat.has("hostProfile"));
                accepting.set(true);
                client.readiness().get(10, TimeUnit.SECONDS);
                assertTrue(stub.lastHeartbeat.get("acceptingPlayers").getAsBoolean());
                assertEquals(generation, stub.generation);
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            assertFalse(stub.lastHeartbeat.get("acceptingPlayers").getAsBoolean());
        }
    }

    @Test
    void usesProviderNeutralBearerAuthorizationWithoutPowOrPersistingTheToken(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            var config =
                    ProviderClient.Configuration.builder(URI.create(stub.origin))
                            .label("Hosted customer")
                            .authorizationToken("independent-provider-token")
                            .build();
            ProviderClient client =
                    new ProviderClient(config, new ProviderStateStore(path), new FakeTransport(), () -> null,
                            () -> new ProviderClient.Health(true, 10, SOFTWARE, connected(0)), message -> {
                    });
            try {
                var registration = client.start().get(20, TimeUnit.SECONDS);
                assertEquals("example-machine-1", registration.instanceId());
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
                    ProviderClient.Configuration.builder(URI.create(stub.origin))
                            .label("Example")
                            .build(),
                    new ProviderStateStore(path), new FakeTransport(), () -> null,
                    () -> new ProviderClient.Health(true, 10, null, connected(0)), message -> {
            });
            try {
                JsonObject extensions = JsonParser.parseString(
                        "{\"org.example.location\":{\"version\":1,\"critical\":false,\"data\":{\"location\":null}}}")
                        .getAsJsonObject();
                client.updateHeartbeatExtensions(extensions).get(10, TimeUnit.SECONDS);
                client.start().get(20, TimeUnit.SECONDS);
                assertEquals(extensions, stub.lastHeartbeat.getAsJsonObject("extensions"),
                        "Explicit null in opaque extension data must survive wire serialization");
                assertFalse(stub.lastHeartbeat.has("software"), "Unspecified optional health fields stay omitted");
                assertEquals(stub.extensionMetadata, client.extensions().get(10, TimeUnit.SECONDS));
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
                    ProviderClient.Configuration.builder(URI.create(stub.origin))
                            .label("Scheduled")
                            .build(),
                    new ProviderStateStore(path), transport,
                    () -> new ServerStatus("Scheduled", "world", 40, 0),
                    () -> new ProviderClient.Health(true, 40, SOFTWARE, connected(players.get())),
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
                eventually(() -> stub.lastHeartbeat.getAsJsonObject("connectedPlayers").get("count").getAsInt() == 1);
                int busyBefore = stub.heartbeats;
                eventually(() -> stub.heartbeats > busyBefore && stub.controlPolls == 0);
                stub.checkInMillis = 3600000;
                players.set(0);
                client.requestStatusRefresh();
                eventually(() -> stub.lastHeartbeat.getAsJsonObject("connectedPlayers").get("count").getAsInt() == 0);
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
                    ProviderClient.Configuration.builder(URI.create(stub.origin))
                            .label("Scheduled")
                            .build(),
                    new ProviderStateStore(path), replacement,
                    () -> new ServerStatus("Restarted", "world", 40, 0),
                    () -> new ProviderClient.Health(true, 40, SOFTWARE, connected(0)), message -> {
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
        volatile int installed, drains;
        boolean stateless = true;
        String ticketKeyId = "T001";

        @Override
        public CompletionStage<HostProfileSnapshot> captureHostProfile() {
            return hostProfile().thenApply(profile -> new HostProfileSnapshot(profile, () -> {}));
        }

        public CompletionStage<JsonObject> hostProfile() {
            JsonObject p = new JsonObject();
            p.addProperty("admissionKeyId", ticketKeyId);
            p.addProperty("dtlsFingerprint", "sha-256 " + String.join(":", Collections.nCopies(32, "11")));
            p.addProperty("sctpPort", 5000);
            p.addProperty("maxMessageSize", 262144);
            JsonObject c = new JsonObject();
            c.addProperty("foundation", "fixture");
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

        public CompletionStage<Void> installAdmissionKeys(List<AdmissionKey> keys) {
            if (refuseKeys) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Unusable admission key"));
            }
            installed = keys.size();
            ticketKeyId = keys.get(keys.size() - 1).keyId();
            return CompletableFuture.completedFuture(null);
        }

        public List<JsonObject> pollEvents(int maximum) {
            List<JsonObject> batch = new ArrayList<>();
            JsonObject event;
            while (batch.size() < maximum && (event = events.poll()) != null) {
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
            AtomicReference<ServerStatus> renamed = new AtomicReference<>();
            FakeTransport host = new FakeTransport();
            var config = ProviderClient.Configuration.builder(URI.create(stub.origin))
                    .label("Example")
                    .build();
            ProviderClient client = new ProviderClient(config, new ProviderStateStore(path), host,
                    () -> renamed.get() != null ? renamed.get() : new ServerStatus("Example", "", 40, 0),
                    () -> new ProviderClient.Health(true, 100, SOFTWARE, connected(players.get())), message -> {
            });
            assertEquals("example-machine-1", client.start().get(20, TimeUnit.SECONDS).instanceId());
            assertEquals(1, stub.registrations);
            assertEquals(1, host.installed);
            assertEquals(100, stub.lastHeartbeat.get("capacity").getAsInt());
            assertEquals(40, stub.lastHeartbeat.getAsJsonObject("serverStatus").get("maxPlayerCount").getAsInt());
            players.set(7);
            eventually(() -> stub.lastHeartbeat.getAsJsonObject("connectedPlayers").get("count").getAsInt() == 7);
            renamed.set(new ServerStatus("Renamed", "World", 30, 2));
            client.requestStatusRefresh();
            eventually(() -> "Renamed".equals(
                    stub.lastHeartbeat.getAsJsonObject("serverStatus").get("name").getAsString()));
            assertTrue(client.readiness().get(10, TimeUnit.SECONDS).get("routable").getAsBoolean());
            client.rotateMachineKey().get(10, TimeUnit.SECONDS);
            client.drain().get(10, TimeUnit.SECONDS);
            assertTrue(stub.draining);
            client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            FakeTransport restarted = new FakeTransport();
            ProviderClient resumed = new ProviderClient(config, new ProviderStateStore(path), restarted,
                    () -> new ServerStatus("Restarted", "", 50, 1),
                    () -> new ProviderClient.Health(true, 100, SOFTWARE, connected(0)), message -> {
            });
            try {
                assertEquals("example-machine-1",
                        resumed.start().get(20, TimeUnit.SECONDS).instanceId());
                assertEquals(1, stub.registrations);
                assertEquals(2, stub.generation);
            } finally {
                resumed.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void retirementRepliesAndRestartNeverExtendPersistedOriginalCutoff(@TempDir Path path)
            throws Exception {
        class CapturingTransport extends FakeTransport {
            final List<List<AdmissionKey>> snapshots = new CopyOnWriteArrayList<>();

            @Override
            public CompletionStage<Void> installAdmissionKeys(List<AdmissionKey> keys) {
                snapshots.add(List.copyOf(keys));
                return super.installAdmissionKeys(keys);
            }

            List<AdmissionKey> latest() {
                return snapshots.get(snapshots.size() - 1);
            }
        }
        try (var stub = new IndependentProviderStub()) {
            stub.checkInMillis = 3_600_000;
            var config =
                    ProviderClient.Configuration.builder(URI.create(stub.origin))
                            .label("Rotation")
                            .build();
            var firstTransport = new CapturingTransport();
            var first =
                    new ProviderClient(
                            config,
                            new ProviderStateStore(path),
                            firstTransport,
                            () -> null,
                            () -> new ProviderClient.Health(true, 10, SOFTWARE, connected(0)),
                            message -> {});
            long originalCutoff;
            try {
                first.start().get(20, TimeUnit.SECONDS);
                originalCutoff = System.currentTimeMillis() + 300_000;
                JsonObject oldKey = new JsonObject();
                oldKey.addProperty("keyId", "T001");
                oldKey.addProperty("retireAfter", originalCutoff);
                stub.heartbeatRetirements = new JsonArray();
                stub.heartbeatRetirements.add(oldKey);
                first.rotateAdmissionKey().get(10, TimeUnit.SECONDS);
                assertEquals(
                        List.of("T001", "T002"),
                        firstTransport.latest().stream()
                                .map(AdmissionKey::keyId)
                                .toList());
                assertEquals(originalCutoff, firstTransport.latest().get(0).retireAfter());
                assertEquals(Long.MAX_VALUE, firstTransport.latest().get(1).retireAfter());
                // Repeated responses are idempotent; even a provider erroneously rebasing a later
                // response cannot extend the original local retirement bound.
                first.readiness().get(10, TimeUnit.SECONDS);
                JsonArray laterReply = stub.heartbeatRetirements.deepCopy();
                laterReply
                        .get(0)
                        .getAsJsonObject()
                        .addProperty("retireAfter", originalCutoff + 300_000);
                stub.heartbeatRetirements = laterReply;
                first.readiness().get(10, TimeUnit.SECONDS);
                assertEquals(originalCutoff, firstTransport.latest().get(0).retireAfter());
            } finally {
                first.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            var saved =
                    JsonParser.parseString(Files.readString(path.resolve("provider-state.json")))
                            .getAsJsonObject()
                            .getAsJsonArray("ticketKeys");
            assertEquals(
                    originalCutoff, saved.get(0).getAsJsonObject().get("retireAfter").getAsLong());
            var resumedTransport = new CapturingTransport();
            var resumed =
                    new ProviderClient(
                            config,
                            new ProviderStateStore(path),
                            resumedTransport,
                            () -> null,
                            () -> new ProviderClient.Health(true, 10, SOFTWARE, connected(0)),
                            message -> {});
            try {
                resumed.start().get(20, TimeUnit.SECONDS);
                resumed.readiness().get(10, TimeUnit.SECONDS);
                assertEquals(2, stub.generation);
                assertFalse(resumedTransport.snapshots.isEmpty());
                for (var snapshot : resumedTransport.snapshots) {
                    assertEquals(
                            List.of("T001", "T002"),
                            snapshot.stream().map(AdmissionKey::keyId).toList());
                    assertEquals(
                            originalCutoff,
                            snapshot.get(0).retireAfter(),
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
    void failedRefreshRetriesAndCoalescesBursts(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            AtomicInteger players = new AtomicInteger(2);
            FakeTransport host = new FakeTransport();
            var config = ProviderClient.Configuration.builder(URI.create(stub.origin))
                    .label("Example")
                    .build();
            var health =
                    (Supplier<ProviderClient.Health>) () -> new ProviderClient.Health(true, 100, SOFTWARE,
                            connected(Math.max(0, players.get())));
            ProviderClient client = new ProviderClient(config, new ProviderStateStore(path), host, () -> {
                if (players.get() < 0) {
                    throw new IllegalStateException("query unavailable");
                }
                return new ServerStatus("Example", "", 40, 0);
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
                    && stub.lastHeartbeat.getAsJsonObject("connectedPlayers").get("count").getAsInt() == 5);
            assertTrue(stub.heartbeats - before <= 2, "Burst must coalesce within heartbeat cadence");
            client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void outcomeOutageBacksOffWhileHeartbeatsContinue(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            stub.extensionMetadata =
                    JsonParser.parseString(
                                    "{\"dev.opencollab.nxs.connectivity\":{\"version\":1,\"critical\":false,\"data\":{}}}")
                            .getAsJsonObject();
            FakeTransport host = new FakeTransport();
            stub.failOutcomes = true;
            var client = new ProviderClient(
                    ProviderClient.Configuration.builder(URI.create(stub.origin))
                            .label("Example")
                            .build(),
                    new ProviderStateStore(path), host, () -> null,
                    () -> new ProviderClient.Health(true, 10, SOFTWARE, connected(0)), message -> {
            });
            try {
                client.start().get(20, TimeUnit.SECONDS);
                JsonObject observed =
                        JsonParser.parseString(
                                        """
                        {"ticketId":"fixture-ticket","stage":"ticket.data_channels_open",
                         "occurredAt":1788739200000,"reason":"both_channels_open",
                         "remoteAddress":"2001:db8::1234","remotePort":54321,"sdp":"must-not-persist"}
                        """)
                                .getAsJsonObject();
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
    void dropsAPermanentlyRejectedOutcomeBatch(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            FakeTransport host = new FakeTransport();
            stub.rejectOutcomes = 1;
            var client = new ProviderClient(
                    ProviderClient.Configuration.builder(URI.create(stub.origin)).label("Example").build(),
                    new ProviderStateStore(path), host, () -> null,
                    () -> new ProviderClient.Health(true, 10, SOFTWARE, connected(0)), ignored -> {});
            try {
                client.start().get(20, TimeUnit.SECONDS);
                host.events.add(outcome("rejected"));
                eventually(() -> stub.outcomeAttempts == 1);
                host.events.add(outcome("accepted"));
                eventually(() -> stub.events.size() == 1);
                assertEquals("accepted", stub.events.get(0).get("ticketId").getAsString());
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static JsonObject outcome(String ticketId) {
        return JsonParser.parseString("{\"ticketId\":\"" + ticketId
                + "\",\"stage\":\"ticket.data_channels_open\",\"occurredAt\":1788739200000}").getAsJsonObject();
    }

    @Test
    void republishesTheProfileWhenTheProviderHoldsNone(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            var client = new ProviderClient(
                    ProviderClient.Configuration.builder(URI.create(stub.origin)).label("Example").build(),
                    new ProviderStateStore(path), new FakeTransport(), () -> null,
                    () -> new ProviderClient.Health(true, 10, SOFTWARE, connected(0)), ignored -> {});
            try {
                client.start().get(20, TimeUnit.SECONDS);
                client.readiness().get(10, TimeUnit.SECONDS);
                assertFalse(stub.lastHeartbeat.has("hostProfile"));
                int published = stub.profileRevision;
                stub.forgetProfile = true;
                eventually(() -> !stub.forgetProfile && stub.profileRevision == published + 1);
                client.readiness().get(10, TimeUnit.SECONDS);
                assertEquals("example-profile-" + (published + 1),
                        stub.lastHeartbeat.get("hostProfileRevision").getAsString());
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void stopsWithoutDeregisteringWhenAnotherProcessTakesOver(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            FakeTransport host = new FakeTransport();
            List<String> messages = new CopyOnWriteArrayList<>();
            var client = new ProviderClient(
                    ProviderClient.Configuration.builder(URI.create(stub.origin))
                            .label("Example")
                            .deregisterOnShutdown(true)
                            .build(),
                    new ProviderStateStore(path), host, () -> null,
                    () -> new ProviderClient.Health(true, 10, SOFTWARE, connected(0)),
                    message -> messages.add(message.message()));
            client.start().get(20, TimeUnit.SECONDS);
            synchronized (stub) {
                stub.generation++; // Another process recovered this registration
            }
            eventually(() -> host.closed.isDone());
            client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertFalse(stub.operationsSeen.contains("/example/deregister"));
            assertTrue(messages.stream().anyMatch(message -> message.startsWith("Another process took over")));
            try (ProviderStateStore state = new ProviderStateStore(path)) {
                assertTrue(state.read().has("registration"));
            }
        }
    }

    @Test
    void keepsTheActiveEpochWhileDrainingWithoutAProfile(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            FakeTransport host = new FakeTransport();
            var client = new ProviderClient(
                    ProviderClient.Configuration.builder(URI.create(stub.origin)).label("Example").build(),
                    new ProviderStateStore(path), host, () -> null,
                    () -> new ProviderClient.Health(true, 10, SOFTWARE, connected(0)), ignored -> {});
            try {
                client.start().get(20, TimeUnit.SECONDS);
                client.drain().get(10, TimeUnit.SECONDS);
                assertEquals("T001", stub.lastHeartbeat.getAsJsonArray("installedAdmissionKeyIds").get(0).getAsString());
                client.rotateAdmissionKey().get(10, TimeUnit.SECONDS);
                assertEquals("T002", host.ticketKeyId, "The delivered epoch is installed locally");
                assertFalse(stub.lastHeartbeat.has("hostProfile"));
                assertFalse(stub.lastHeartbeat.has("installedAdmissionKeyIds"),
                        "A new active epoch waits for a matching profile");
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void reportsGameOutcomesOnlyWhenDeclared(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            var builder = ProviderClient.Configuration.builder(URI.create(stub.origin)).label("Example");
            var undeclared = new ProviderClient(builder.build(), new ProviderStateStore(path.resolve("off")),
                    new FakeTransport(), () -> null, () -> new ProviderClient.Health(true, 10, SOFTWARE, connected(0)),
                    message -> { });
            assertThrows(IllegalStateException.class,
                    () -> undeclared.reportGameOutcome("ticket", ProviderClient.GameOutcome.JOINED, null));
            undeclared.close();
            var client = new ProviderClient(builder.gameOutcomes(true).build(),
                    new ProviderStateStore(path.resolve("on")), new FakeTransport(), () -> null,
                    () -> new ProviderClient.Health(true, 10, SOFTWARE, connected(0)), message -> { });
            try {
                client.start().get(20, TimeUnit.SECONDS);
                assertEquals("available", stub.lastHeartbeat.get("gameOutcomes").getAsString());
                long before = System.currentTimeMillis();
                assertTrue(client.reportGameOutcome("fixture-ticket", ProviderClient.GameOutcome.REJECTED,
                        "unsupported_version"));
                eventually(() -> !stub.events.isEmpty());
                JsonObject event = stub.events.get(0);
                assertEquals("ticket.game_rejected", event.get("stage").getAsString());
                assertEquals("fixture-ticket", event.get("ticketId").getAsString());
                assertEquals("unsupported_version", event.get("reason").getAsString());
                assertTrue(event.get("occurredAt").getAsLong() >= before);
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            assertFalse(client.reportGameOutcome("late", ProviderClient.GameOutcome.JOINED, null));
        }
    }

    @Test
    void outcomesOmitUnadvertisedConnectivityMetadata(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            FakeTransport host = new FakeTransport();
            var client =
                    new ProviderClient(
                            ProviderClient.Configuration.builder(URI.create(stub.origin))
                                    .label("Example")
                                    .build(),
                            new ProviderStateStore(path),
                            host,
                            () -> null,
                            () -> new ProviderClient.Health(true, 10, SOFTWARE, connected(0)),
                            ignored -> {});
            try {
                client.start().get(20, TimeUnit.SECONDS);
                client.readiness().get(10, TimeUnit.SECONDS);
                host.events.add(
                        JsonParser.parseString(
                                        """
                        {"ticketId":"fixture-ticket","stage":"ticket.data_channels_open",
                         "occurredAt":1788739200000,"remoteAddress":"8.8.8.8","remotePort":54321}
                        """)
                                .getAsJsonObject());
                eventually(() -> !stub.events.isEmpty());
                assertEquals(
                        Set.of("ticketId", "stage", "occurredAt"), stub.events.get(0).keySet());
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void deliversAnEventBurstInBoundedBatches(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            FakeTransport host = new FakeTransport();
            var client = new ProviderClient(
                    ProviderClient.Configuration.builder(URI.create(stub.origin))
                            .label("Example")
                            .build(),
                    new ProviderStateStore(path), host, () -> null,
                    () -> new ProviderClient.Health(true, 10, SOFTWARE, connected(0)), ignored -> {});
            try {
                client.start().get(20, TimeUnit.SECONDS);
                client.readiness().get(10, TimeUnit.SECONDS);
                for (int i = 0; i < 250; i++) {
                    host.events.add(JsonParser.parseString("{\"ticketId\":\"burst-" + i
                            + "\",\"stage\":\"ticket.data_channels_open\",\"occurredAt\":1788739200000}")
                            .getAsJsonObject());
                }
                eventually(() -> stub.events.size() == 250);
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void localPersistenceFailureStopsPublicationAndClosesTransport(@TempDir Path path) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            FakeTransport host = new FakeTransport();
            var client = new ProviderClient(
                    ProviderClient.Configuration.builder(URI.create(stub.origin))
                            .label("Example")
                            .build(),
                    new ProviderStateStore(path), host, () -> null,
                    () -> new ProviderClient.Health(true, 10, SOFTWARE, connected(0)), message -> {
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
