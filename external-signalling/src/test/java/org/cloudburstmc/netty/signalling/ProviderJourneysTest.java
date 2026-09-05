package org.cloudburstmc.netty.signalling;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ProviderJourneysTest {
    private static ProviderClient client(IndependentProviderStub stub, Path directory, ProviderClient.Configuration configuration,
                                         ProviderClientTest.FakeTransport transport) throws Exception {
        return new ProviderClient(configuration, new ProviderStateStore(directory), transport,
            () -> new ServerStatus("Independent host", 1000, "conformance", "world", 1, 20, 0),
            () -> new ProviderClient.Health(true, 100, .01, "nethernet", "fixture"), message -> {});
    }

    @Test void allFourOperatorJourneysUseOneNeutralLifecycle(@TempDir Path directory) throws Exception {
        String[] journeys = {"anonymous-standalone", "token-new-service", "token-fleet-attachment", "custom-host-provider"};
        for (String journey : journeys) {
            try (IndependentProviderStub stub = new IndependentProviderStub()) {
                boolean bearer = !journey.equals("anonymous-standalone");
                boolean attach = journey.equals("token-fleet-attachment");
                var configuration = new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", journey,
                    attach ? ProviderClient.ATTACH_INSTANCE : ProviderClient.NEW_SERVICE,
                    bearer ? ProviderClient.BEARER_TOKEN : ProviderClient.ANONYMOUS_PROOF_OF_WORK,
                    bearer ? "independent-provider-token" : null, attach ? "EU" : null, attach ? "proxy" : null,
                    attach ? Map.of("location", "london", "role", "proxy") : Map.of());
                var transport = new ProviderClientTest.FakeTransport();
                ProviderClient instance = client(stub, directory.resolve(journey), configuration, transport);
                try {
                    JsonObject result = instance.start().get(20, TimeUnit.SECONDS);
                    assertEquals("nethernet-external-signalling-v1", result.get("protocol").getAsString());
                    assertEquals("nxs-admission-v1", result.get("profile").getAsString());
                    assertFalse(result.has("extensions"), "A provider needs no product extension");
                    assertEquals(bearer ? 0 : 2, stub.challengeDifficulty);
                    assertTrue(stub.keyAcknowledgements > 0);
                    assertTrue(instance.readiness().get(10, TimeUnit.SECONDS).get("routable").getAsBoolean());
                    if (attach) assertEquals("london", result.getAsJsonObject("placement").getAsJsonObject("tags").get("location").getAsString());
                    JsonObject event = new JsonObject(); event.addProperty("stage", "ticket.transport_established");
                    event.addProperty("ticketId", "opaque-correlation"); event.addProperty("occurredAt", java.time.Instant.now().toString());
                    event.addProperty("reason", "connected"); event.addProperty("privatePayload", "must-not-be-persisted"); transport.events.add(event);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (stub.events.isEmpty() && System.nanoTime() < deadline) Thread.sleep(25);
                    assertEquals(1, stub.events.size()); assertFalse(stub.events.getFirst().has("privatePayload"));
                    assertEquals(0, transport.admissions, "Control-plane delivery cannot stage individual clients");
                    instance.deregister().get(10, TimeUnit.SECONDS); assertTrue(stub.draining);
                } finally { instance.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
            }
        }
    }

    @Test void profileMigrationPreservesDurableIdentityAndAssignedIds(@TempDir Path directory) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            var configuration = new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Migration");
            ProviderClient first = client(stub, directory, configuration, new ProviderClientTest.FakeTransport());
            JsonObject registration;
            try { registration = first.start().get(20, TimeUnit.SECONDS); }
            finally { first.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
            JsonObject previous;
            try (ProviderStateStore store = new ProviderStateStore(directory)) {
                previous = store.read(); JsonObject legacy = previous.deepCopy();
                legacy.remove("protocol"); legacy.remove("profile");
                legacy.getAsJsonObject("registration").addProperty("protocol", "legacy-protocol-fixture");
                legacy.getAsJsonObject("registration").addProperty("profile", "legacy-profile-fixture");
                store.write(legacy);
            }
            ProviderClient resumed = client(stub, directory, configuration, new ProviderClientTest.FakeTransport());
            try {
                JsonObject migrated = resumed.start().get(20, TimeUnit.SECONDS);
                for (String field : new String[]{"instanceId", "serviceId", "registrationId", "keyId"})
                    assertEquals(registration.get(field), migrated.get(field));
                assertEquals(1, stub.registrations); assertEquals(2, stub.generation);
            } finally { resumed.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
            try (ProviderStateStore store = new ProviderStateStore(directory)) {
                JsonObject current = store.read();
                assertEquals(previous.get("privateKey"), current.get("privateKey"));
                assertEquals(previous.get("publicKeyJwk"), current.get("publicKeyJwk"));
                assertEquals("nxs-admission-v1", current.get("profile").getAsString());
            }
        }
    }

    @Test void requiredUnknownExtensionFailsBeforeCredentialTransmission(@TempDir Path directory) throws Exception {
        try (IndependentProviderStub stub = new IndependentProviderStub()) {
            stub.extensionMetadata = JsonParser.parseString("{\"org.example.required\":{\"version\":1,\"critical\":true,\"data\":{}}}").getAsJsonObject();
            var configuration = new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Example",
                ProviderClient.NEW_SERVICE, ProviderClient.BEARER_TOKEN, "independent-provider-token", null, null, Map.of());
            ProviderClient instance = client(stub, directory, configuration, new ProviderClientTest.FakeTransport());
            try {
                assertThrows(java.util.concurrent.ExecutionException.class, () -> instance.start().get(20, TimeUnit.SECONDS));
                assertNull(stub.challengeAuthorization); assertEquals(0, stub.registrations);
            } finally { instance.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        }
    }

    @Test void recoversCommittedRegistrationWhenCompletionResponseIsLost(@TempDir Path directory) throws Exception {
        // Exercise bearer attachment too: a recovery challenge intentionally has neither enrollment
        // placement nor bearer authorization, while the recovered registration retains both bindings.
        for (boolean attach : new boolean[]{false, true}) try (IndependentProviderStub stub = new IndependentProviderStub()) {
            Path statePath = directory.resolve(attach ? "attached" : "standalone");
            var configuration = new ProviderClient.Configuration(URI.create(stub.origin), "nxs-admission-v1", "Lost completion",
                attach ? ProviderClient.ATTACH_INSTANCE : ProviderClient.NEW_SERVICE,
                attach ? ProviderClient.BEARER_TOKEN : ProviderClient.ANONYMOUS_PROOF_OF_WORK,
                attach ? "independent-provider-token" : null, attach ? "EU" : null, attach ? "proxy" : null,
                attach ? Map.of("location", "london") : Map.of());
            stub.loseCompletionResponse = true;
            ProviderClient first = client(stub, statePath, configuration, new ProviderClientTest.FakeTransport());
            try { assertThrows(java.util.concurrent.ExecutionException.class, () -> first.start().get(20, TimeUnit.SECONDS)); }
            finally { first.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
            JsonObject before;
            try (ProviderStateStore state = new ProviderStateStore(statePath)) {
                before = state.read(); assertTrue(before.has("challenge")); assertFalse(before.has("registration"));
            }
            assertEquals(1, stub.registrations, "The provider committed despite the lost HTTP response");
            ProviderClient resumed = client(stub, statePath, configuration, new ProviderClientTest.FakeTransport());
            try {
                JsonObject registration = resumed.start().get(20, TimeUnit.SECONDS);
                assertEquals(stub.registration.get("registrationId"), registration.get("registrationId"));
                assertEquals(1, stub.registrations); assertEquals(1, stub.generation);
                assertTrue(resumed.readiness().get(10, TimeUnit.SECONDS).get("routable").getAsBoolean());
                assertTrue(stub.keyAcknowledgements > 0, "Lost one-time key material is freshly provisioned");
            } finally { resumed.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
            try (ProviderStateStore state = new ProviderStateStore(statePath)) {
                JsonObject after = state.read(); assertFalse(after.has("challenge"));
                assertEquals(before.get("privateKey"), after.get("privateKey"));
            }
        }
    }
}
