package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ProviderJourneysTest {
    private static ProviderClient client(IndependentProviderStub stub, Path directory,
                                         ProviderClient.Configuration configuration,
                                         ProviderClientTest.FakeTransport transport) throws Exception {
        return new ProviderClient(configuration, new ProviderStateStore(directory), transport,
                () -> new ServerStatus("Independent host", "world", 20, 0),
                () -> new ProviderClient.Health(true, 100, ProviderClientTest.SOFTWARE, ProviderClientTest.connected(0)), message -> {
        });
    }

    @Test
    void allFourOperatorJourneysUseOneNeutralLifecycle(@TempDir Path directory) throws Exception {
        String[] journeys =
                {"anonymous-standalone", "token-new-service", "token-fleet-attachment", "custom-host-provider"};
        for (String journey : journeys) {
            try (IndependentProviderStub stub = new IndependentProviderStub()) {
                boolean bearer = !journey.equals("anonymous-standalone");
                boolean attach = journey.equals("token-fleet-attachment");
                stub.attachToken = attach;
                var configuration =
                        ProviderClient.Configuration.builder(URI.create(stub.origin))
                                .label(journey)
                                .authorizationToken(bearer ? "independent-provider-token" : null)
                                .build();
                var transport = new ProviderClientTest.FakeTransport();
                ProviderClient instance = client(stub, directory.resolve(journey), configuration, transport);
                try {
                    instance.start().get(20, TimeUnit.SECONDS);
                    assertTrue(instance.extensions().get(10, TimeUnit.SECONDS).isEmpty(),
                            "A provider needs no product extension");
                    assertEquals(bearer ? 0 : 2, stub.challengeDifficulty);
                    assertTrue(stub.keyAcknowledgements > 0);
                    assertTrue(instance.readiness().get(10, TimeUnit.SECONDS).get("routable").getAsBoolean());
                    JsonObject event = new JsonObject();
                    event.addProperty("stage", "ticket.data_channels_open");
                    event.addProperty("ticketId", "opaque-correlation");
                    event.addProperty("occurredAt", System.currentTimeMillis());
                    event.addProperty("reason", "connected");
                    event.addProperty("privatePayload", "must-not-be-persisted");
                    transport.events.add(event);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    while (stub.events.isEmpty() && System.nanoTime() < deadline) {
                        Thread.sleep(25);
                    }
                    assertEquals(1, stub.events.size());
                    assertFalse(stub.events.get(0).has("privatePayload"));
                    instance.deregister().get(10, TimeUnit.SECONDS);
                    assertTrue(stub.draining);
                } finally {
                    instance.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void rejectsUnknownModesAndAnonymousAttachment(@TempDir Path directory) throws Exception {
        for (String selected : new String[]{"automatic", "unknown", "attach-instance"}) {
            try (IndependentProviderStub stub = new IndependentProviderStub()) {
                stub.selectedMode = selected;
                var configuration =
                        ProviderClient.Configuration.builder(URI.create(stub.origin))
                                .label("Mode test")
                                .build();
                ProviderClient instance = client(stub, directory.resolve(selected), configuration,
                        new ProviderClientTest.FakeTransport());
                try {
                    assertThrows(ExecutionException.class,
                            () -> instance.start().get(10, TimeUnit.SECONDS));
                    assertFalse(stub.operationsSeen.contains("/example/complete"));
                } finally {
                    instance.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void olderStateKeepsDurableIdentityAndAssignedIds(@TempDir Path directory) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            var configuration =
                    ProviderClient.Configuration.builder(URI.create(stub.origin))
                            .label("Migration")
                            .build();
            ProviderClient first = client(stub, directory, configuration, new ProviderClientTest.FakeTransport());
            ProviderClient.Registration registration;
            try {
                registration = first.start().get(20, TimeUnit.SECONDS);
            } finally {
                first.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            JsonObject previous;
            try (ProviderStateStore store = new ProviderStateStore(directory)) {
                previous = store.read();
                JsonObject legacy = previous.deepCopy();
                legacy.remove("protocol");
                legacy.remove("profile");
                legacy.getAsJsonObject("registration").addProperty("protocol", "legacy-protocol-fixture");
                legacy.getAsJsonObject("registration").addProperty("profile", "legacy-profile-fixture");
                store.write(legacy);
            }
            ProviderClient resumed = client(stub, directory, configuration, new ProviderClientTest.FakeTransport());
            try {
                var migrated = resumed.start().get(20, TimeUnit.SECONDS);
                assertEquals(registration.instanceId(), migrated.instanceId());
                assertEquals(registration.serviceId(), migrated.serviceId());
                assertEquals(registration.registrationId(), migrated.registrationId());
                assertEquals(registration.keyId(), migrated.keyId());
                assertEquals(1, stub.registrations);
                assertEquals(2, stub.generation);
            } finally {
                resumed.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            try (ProviderStateStore store = new ProviderStateStore(directory)) {
                JsonObject current = store.read();
                assertEquals(previous.get("privateKey"), current.get("privateKey"));
                assertEquals(previous.get("publicKeyJwk"), current.get("publicKeyJwk"));
            }
        }
    }

    @Test
    void requiredUnknownExtensionFailsBeforeCredentialTransmission(@TempDir Path directory) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            stub.extensionMetadata =
                    JsonParser.parseString("{\"org.example.required\":{\"version\":1,\"critical\":true,\"data\":{}}}")
                            .getAsJsonObject();
            var configuration = ProviderClient.Configuration.builder(URI.create(stub.origin))
                    .label("Example")
                    .authorizationToken("independent-provider-token")
                    .build();
            ProviderClient instance = client(stub, directory, configuration, new ProviderClientTest.FakeTransport());
            try {
                assertThrows(ExecutionException.class,
                        () -> instance.start().get(20, TimeUnit.SECONDS));
                assertNull(stub.challengeAuthorization);
                assertEquals(0, stub.registrations);
            } finally {
                instance.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void recoversCommittedRegistrationWhenCompletionResponseIsLost(@TempDir Path directory) throws Exception {
        // Exercise bearer attachment too: a recovery challenge intentionally has no bearer
        // authorization, while the recovered registration retains the attachment.
        for (boolean attach : new boolean[]{false, true}) {
            try (IndependentProviderStub stub = new IndependentProviderStub()) {
                stub.attachToken = attach;
                Path statePath = directory.resolve(attach ? "attached" : "standalone");
                var configuration =
                        ProviderClient.Configuration.builder(URI.create(stub.origin))
                                .label("Lost completion")
                                .authorizationToken(attach ? "independent-provider-token" : null)
                                .build();
                stub.loseCompletionResponse = true;
                ProviderClient first = client(stub, statePath, configuration, new ProviderClientTest.FakeTransport());
                try {
                    // The retried completion finds the challenge consumed and recovers inline
                    String registrationId = first.start().get(20, TimeUnit.SECONDS).registrationId();
                    assertEquals(stub.registration.get("registrationId").getAsString(), registrationId);
                    assertNotEquals(stub.initialChallengeId, registrationId, "The recovered registration keeps its own ID");
                    assertEquals(1, stub.registrations, "The provider committed despite the lost HTTP response");
                    assertEquals(2, stub.generation);
                    assertTrue(first.readiness().get(10, TimeUnit.SECONDS).get("routable").getAsBoolean());
                    assertTrue(stub.keyAcknowledgements > 0, "Lost one-time key material is freshly provisioned");
                } finally {
                    first.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
                }
                try (ProviderStateStore state = new ProviderStateStore(statePath)) {
                    JsonObject after = state.read();
                    assertFalse(after.has("challenge"));
                    assertTrue(after.has("registration"));
                }
            }
        }
    }

    @Test
    void expiredUnfinishedChallengeIsReplaced(@TempDir Path directory) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            var configuration = ProviderClient.Configuration.builder(URI.create(stub.origin)).label("Expired").build();
            stub.challengeLifetimeMillis = 0;
            ProviderClient first = client(stub, directory, configuration, new ProviderClientTest.FakeTransport());
            try {
                assertThrows(ExecutionException.class, () -> first.start().get(20, TimeUnit.SECONDS));
            } finally {
                first.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            stub.challengeLifetimeMillis = 60000;
            ProviderClient resumed = client(stub, directory, configuration, new ProviderClientTest.FakeTransport());
            try {
                resumed.start().get(20, TimeUnit.SECONDS);
                assertEquals(1, stub.registrations);
            } finally {
                resumed.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void forgottenRegistrationRegistersAgainWithAFreshKey(@TempDir Path directory) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            var configuration = ProviderClient.Configuration.builder(URI.create(stub.origin)).label("Forgotten").build();
            ProviderClient first = client(stub, directory, configuration, new ProviderClientTest.FakeTransport());
            String before;
            try {
                before = first.start().get(20, TimeUnit.SECONDS).registrationId();
            } finally {
                first.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            String oldKey = JsonParser.parseString(Files.readString(directory.resolve("provider-state.json")))
                    .getAsJsonObject().get("privateKey").getAsString();
            stub.registration = null;
            var diagnostics = new CopyOnWriteArrayList<ProviderDiagnostic>();
            ProviderClient resumed = new ProviderClient(configuration, new ProviderStateStore(directory),
                    new ProviderClientTest.FakeTransport(), () -> null,
                    () -> new ProviderClient.Health(true, 100, ProviderClientTest.SOFTWARE, ProviderClientTest.connected(0)), diagnostics::add);
            try {
                assertNotEquals(before, resumed.start().get(20, TimeUnit.SECONDS).registrationId());
                assertEquals(2, stub.registrations);
                assertTrue(diagnostics.stream().anyMatch(d -> d.level() == ProviderDiagnostic.Level.WARN
                        && d.message().contains("example-machine-1")));
                String newKey = JsonParser.parseString(Files.readString(directory.resolve("provider-state.json")))
                        .getAsJsonObject().get("privateKey").getAsString();
                assertNotEquals(oldKey, newKey, "A new instance gets a fresh machine key");
            } finally {
                resumed.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void deregisteredClientStopsReporting(@TempDir Path directory) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            var configuration = ProviderClient.Configuration.builder(URI.create(stub.origin)).label("Gone").build();
            var transport = new ProviderClientTest.FakeTransport();
            ProviderClient client = client(stub, directory, configuration, transport);
            try {
                client.start().get(20, TimeUnit.SECONDS);
                client.deregister().get(10, TimeUnit.SECONDS);
                JsonObject event = new JsonObject();
                event.addProperty("stage", "ticket.data_channels_open");
                event.addProperty("ticketId", "late-ticket");
                event.addProperty("occurredAt", System.currentTimeMillis());
                transport.events.add(event);
                Thread.sleep(2500);
                assertEquals(0, stub.outcomeAttempts);
                assertEquals(new JsonObject(),
                        JsonParser.parseString(Files.readString(directory.resolve("provider-state.json"))));
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void failedPlayersWarnUnlessAssistanceAlreadyDid(@TempDir Path directory) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            var configuration = ProviderClient.Configuration.builder(URI.create(stub.origin)).label("Failures").build();
            var transport = new ProviderClientTest.FakeTransport();
            var diagnostics = new CopyOnWriteArrayList<ProviderDiagnostic>();
            ProviderClient client = new ProviderClient(configuration, new ProviderStateStore(directory), transport,
                    () -> null, () -> new ProviderClient.Health(true, 100, ProviderClientTest.SOFTWARE, ProviderClientTest.connected(0)),
                    diagnostics::add);
            try {
                client.start().get(20, TimeUnit.SECONDS);
                for (String reason : new String[]{"timeout", "assisted_failed"}) {
                    JsonObject event = new JsonObject();
                    event.addProperty("stage", "ticket.failed");
                    event.addProperty("ticketId", reason);
                    event.addProperty("occurredAt", System.currentTimeMillis());
                    event.addProperty("reason", reason);
                    transport.events.add(event);
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (stub.events.size() < 2 && System.nanoTime() < deadline) {
                    Thread.sleep(25);
                }
                assertEquals(2, stub.events.size());
                assertEquals(1, diagnostics.stream().filter(d -> d.level() == ProviderDiagnostic.Level.WARN
                        && d.message().equals("A player could not connect to the server.")).count());
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void operatorActionsComeFromCompletionThenOnRequest(@TempDir Path directory) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            stub.operatorActions = true;
            var configuration = ProviderClient.Configuration.builder(URI.create(stub.origin)).label("Action").build();
            ProviderClient client = client(stub, directory, configuration, new ProviderClientTest.FakeTransport());
            try {
                client.start().get(20, TimeUnit.SECONDS);
                var first = client.operatorAction().get(10, TimeUnit.SECONDS).orElseThrow();
                assertEquals(URI.create("https://panel.example/claim/first"), first.url());
                assertFalse(first.toString().contains("claim"), "The link stays out of logs");
                assertEquals(0, stub.operatorActionRequests, "An unexpired action is reused");
                assertFalse(Files.readString(directory.resolve("provider-state.json")).contains("panel.example"));
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            ProviderClient resumed = client(stub, directory, configuration, new ProviderClientTest.FakeTransport());
            try {
                resumed.start().get(20, TimeUnit.SECONDS);
                var fresh = resumed.operatorAction().get(10, TimeUnit.SECONDS).orElseThrow();
                assertEquals(URI.create("https://panel.example/claim/1"), fresh.url());
                assertEquals(1, stub.operatorActionRequests);
                stub.operatorActions = false;
                resumed.readiness().get(10, TimeUnit.SECONDS);
                assertTrue(resumed.operatorAction().get(10, TimeUnit.SECONDS).isPresent(), "A current action is kept");
            } finally {
                resumed.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void locationIsSentWithEachProfileAndNullClearsIt(@TempDir Path directory) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            var located = ProviderClient.Configuration.builder(URI.create(stub.origin)).label("Located")
                    .location(ProviderClient.Location.of(java.util.Map.of("country", " gb ", "latitude", "51.5", "longitude", "-0.12")))
                    .build();
            ProviderClient client = client(stub, directory, located, new ProviderClientTest.FakeTransport());
            try {
                client.start().get(20, TimeUnit.SECONDS);
                assertEquals(JsonParser.parseString("{\"country\":\"GB\",\"latitude\":51.5,\"longitude\":-0.12}"),
                        stub.lastHeartbeat.get("location"));
                client.readiness().get(10, TimeUnit.SECONDS);
                assertFalse(stub.lastHeartbeat.has("location"), "An unchanged location is not repeated");
            } finally {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            var undeclared = ProviderClient.Configuration.builder(URI.create(stub.origin)).label("Located").build();
            ProviderClient resumed = client(stub, directory, undeclared, new ProviderClientTest.FakeTransport());
            try {
                resumed.start().get(20, TimeUnit.SECONDS);
                assertTrue(stub.lastHeartbeat.get("location").isJsonNull(), "Dropping the setting clears the declaration");
            } finally {
                resumed.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
        for (var invalid : java.util.List.of(java.util.Map.of("city", "London"), java.util.Map.of("latitude", "0"),
                java.util.Map.of("country", "GBR"), java.util.Map.of("latitude", "91", "longitude", "0"),
                java.util.Map.of("latitude", "NaN", "longitude", "0"), java.util.Map.of("country", "GB", "extra", "x"))) {
            assertThrows(IllegalArgumentException.class, () -> ProviderClient.Location.of(invalid), invalid.toString());
        }
        assertNull(ProviderClient.Location.of(java.util.Map.of()));
    }
}
