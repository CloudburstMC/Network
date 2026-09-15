package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticHostPolicy;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.*;
import java.net.URI;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real ordinary HTTPS heartbeat; the transport seam only observes local policy application. */
class ProviderDiagnosticsTest {
    @TempDir Path directory;
    static final String NAMESPACE = "org.nethernet.connectivity";

    static final class Transport extends ProviderClientTest.FakeTransport {
        final List<DiagnosticHostPolicy> policies = new CopyOnWriteArrayList<>();
        volatile long revision = 1, publicationVersion = 1, deadline, expiry;
        volatile int port = 19133;
        volatile boolean empty;
        final List<List<ProviderTransport.ConnectivityCheck>> feedback = new CopyOnWriteArrayList<>();
        volatile int disabled;
        volatile boolean srflx;
        List<ProviderTransport.TicketKey> keys;
        public boolean supportsDiagnosticAdmission() { return true; }
        @Override public CompletionStage<JsonObject> hostProfile() {
            return super.hostProfile().thenApply(profile -> {
                var candidate = profile.getAsJsonArray("candidates").get(0).getAsJsonObject();
                candidate.addProperty("port", port);
                if (srflx) {
                    candidate.addProperty("type", "srflx");
                    if (expiry > 0) candidate.addProperty("expiresAt", expiry);
                }
                if (empty) profile.add("candidates", new JsonArray());
                return profile;
            });
        }
        @Override public CompletionStage<HostProfileSnapshot> captureHostProfile() {
            long captured = revision, originalExpiry = expiry, version = publicationVersion;
            return hostProfile().thenApply(profile -> new HostProfileSnapshot(profile, captured, version, () -> {
                if (revision != captured || closed.isDone() || originalExpiry > 0 && System.currentTimeMillis() >= originalExpiry) throw new IllegalStateException("stale snapshot");
            }));
        }
        public long candidatePublicationVersion() { return publicationVersion; }
        public CompletionStage<Void> reportConnectivityChecks(long revision, List<ConnectivityCheck> checks) {
            assertEquals(this.revision, revision);
            feedback.add(List.copyOf(checks));
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> installTicketKeys(List<TicketKey> keys) {
            this.keys = List.copyOf(keys);
            return super.installTicketKeys(keys);
        }
        public CompletionStage<Void> configureDiagnostics(DiagnosticHostPolicy policy, long deadlineNanos) {
            if (policy.endpoints().stream().anyMatch(endpoint -> endpoint.candidateRevision() != revision))
                return CompletableFuture.failedFuture(new IllegalStateException("stale installation"));
            assertTrue(deadlineNanos - System.nanoTime() > 0);
            assertTrue(deadlineNanos - System.nanoTime() <= TimeUnit.MINUTES.toNanos(5));
            deadline = deadlineNanos;
            policies.add(policy);
            return CompletableFuture.completedFuture(null);
        }
        public CompletionStage<Void> disableDiagnostics() {
            disabled++;
            return CompletableFuture.completedFuture(null);
        }
    }

    static final class Fixture implements AutoCloseable {
        final SelfSignedCertificate certificate = new SelfSignedCertificate("localhost");
        final SSLContext previous = SSLContext.getDefault();
        final IndependentProviderStub provider;
        Fixture() throws Exception {
            KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, null);
            store.setKeyEntry("server", certificate.key(), "test".toCharArray(), new java.security.cert.Certificate[]{certificate.cert()});
            var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keys.init(store, "test".toCharArray());
            var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
            SSLContext tls = SSLContext.getInstance("TLS"); tls.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
            SSLContext.setDefault(tls);
            provider = new IndependentProviderStub(tls);
        }
        ProviderClient client(Path directory, Transport transport, boolean enabled, String method) throws Exception {
            var config = new ProviderClient.Configuration(URI.create(provider.origin), "nxs-admission-v1", "Diagnostics",
                    ProviderClient.NEW_SERVICE, ProviderClient.BEARER_TOKEN, "independent-provider-token", null, null,
                    Map.of(), ProviderClient.ControlTransport.HTTP, enabled, method);
            return new ProviderClient(config, new ProviderStateStore(directory), transport, () -> null,
                    () -> new ProviderClient.Health(true, true, 10, 0, "nethernet", "fixture"), ignored -> { });
        }
        public void close() {
            provider.close(); SSLContext.setDefault(previous); certificate.delete();
        }
    }

    @Test @Timeout(30) void successfulHeartbeatUsesExistingKeysAndProfileWithoutInstallationProtocol() throws Exception {
        try (var f = new Fixture()) {
            var transport = new Transport();
            var client = f.client(directory, transport, true, "defined");
            try {
                var observed = new CopyOnWriteArrayList<Boolean>();
                f.provider.heartbeatResponseHook = () -> {
                    boolean advertised = f.provider.lastHeartbeat.has("extensions");
                    if (advertised) assertFalse(transport.policies.isEmpty(), "Opt-in preceded actual local installation");
                    observed.add(advertised);
                };
                long before = System.currentTimeMillis();
                var registration = client.start().get(20, TimeUnit.SECONDS);
                assertFalse(observed.get(0));
                assertTrue(observed.contains(true));
                var policy = transport.policies.get(transport.policies.size() - 1);
                assertEquals(registration.get("instanceId").getAsString(), policy.context().hostId());
                assertEquals(registration.get("leaseGeneration").getAsLong(), policy.context().generation());
                assertEquals(f.provider.origin, policy.context().providerOrigin());
                assertTrue(policy.expiresAt() >= before && policy.expiresAt() <= System.currentTimeMillis() + 300000);
                assertEquals(transport.keys.get(0).secret(), policy.keys().get(0).secret());
                assertEquals(9007199254740991L, policy.keys().get(0).retireAt());
                var extension = f.provider.lastHeartbeat.getAsJsonObject("extensions").getAsJsonObject(NAMESPACE);
                assertEquals(Set.of("version", "critical", "data"), extension.keySet());
                var data = extension.getAsJsonObject("data");
                assertEquals(Set.of("diagnostics", "candidateRevision", "method"), data.keySet());
                assertEquals("defined", data.get("method").getAsString());
                assertEquals(1, data.get("candidateRevision").getAsLong());
                int applications = transport.policies.size();
                client.readiness().get(10, TimeUnit.SECONDS);
                assertTrue(transport.policies.size() > applications);
                assertEquals(policy.keys(), transport.policies.get(transport.policies.size() - 1).keys());
                int disables = transport.disabled;
                client.drain().get(10, TimeUnit.SECONDS);
                assertTrue(transport.disabled > disables);
                assertFalse(f.provider.lastHeartbeat.has("extensions"));
                assertEquals("draining", f.provider.lastHeartbeat.get("state").getAsString());
            } finally { client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        }
    }

    @Test @Timeout(30) void failedHeartbeatDoesNotRenewPolicyAndNamespaceCannotBeForged() throws Exception {
        try (var f = new Fixture()) {
            var transport = new Transport(); var client = f.client(directory, transport, true, "discovered");
            try {
                client.start().get(20, TimeUnit.SECONDS);
                int installed = transport.policies.size(); long deadline = transport.deadline;
                f.provider.failHeartbeats = 100;
                assertThrows(ExecutionException.class, () -> client.readiness().get(10, TimeUnit.SECONDS));
                assertEquals(installed, transport.policies.size()); assertEquals(deadline, transport.deadline);
                var forged = JsonParser.parseString("{\"org.nethernet.connectivity\":{\"version\":1,\"critical\":false,\"data\":{}}}").getAsJsonObject();
                assertThrows(IllegalArgumentException.class, () -> client.updateHeartbeatExtensions(forged));
            } finally { f.provider.failHeartbeats = 0; client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        }
    }

    @Test @Timeout(30) void candidateReplacementDuringHeartbeatCannotInstallOldPolicy() throws Exception {
        try (var f = new Fixture()) {
            var transport = new Transport(); var client = f.client(directory, transport, true, "defined");
            f.provider.heartbeatResponseHook = () -> transport.revision++;
            try {
                assertThrows(ExecutionException.class, () -> client.start().get(20, TimeUnit.SECONDS));
                assertTrue(transport.policies.isEmpty()); assertTrue(transport.disabled > 0);
            } finally { client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        }
    }

    @Test @Timeout(30) void disabledOptInAndUnboundedReflexiveCandidatesDoNotAdvertiseOrInstall() throws Exception {
        for (boolean enabled : List.of(false, true)) {
            try (var f = new Fixture()) {
                var transport = new Transport(); transport.srflx = enabled;
                var client = f.client(directory.resolve(Boolean.toString(enabled)), transport, enabled, "discovered");
                try {
                    client.start().get(20, TimeUnit.SECONDS);
                    assertTrue(transport.policies.isEmpty()); assertFalse(f.provider.lastHeartbeat.has("extensions"));
                } finally { client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
            }
        }
    }
    @Test @Timeout(30) void maintainedExpiryAndMethodUseTheOriginalNativeObservation() throws Exception {
        try (var f = new Fixture()) {
            var transport = new Transport(); transport.srflx = true;
            long original = System.currentTimeMillis() + 180000; transport.expiry = original;
            var client = f.client(directory, transport, true, "discovered");
            try {
                client.start().get(20, TimeUnit.SECONDS);
                var data = f.provider.lastHeartbeat.getAsJsonObject("extensions").getAsJsonObject(NAMESPACE).getAsJsonObject("data");
                assertEquals("warm_stun", data.get("method").getAsString());
                client.readiness().get(10, TimeUnit.SECONDS);
                for (var policy : transport.policies) {
                    assertEquals(Set.of(original), new HashSet<>(policy.endpointExpiries().values()));
                    assertTrue(policy.expiresAt() >= original);
                }
            } finally { client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        }
    }

    @Test @Timeout(30) void materialReplacementAndWithdrawalWakeLongHeartbeatWithoutDiagnostics() throws Exception {
        try (var f = new Fixture()) {
            f.provider.checkInMillis = 120000;
            var transport = new Transport(); var client = f.client(directory, transport, false, "discovered");
            try {
                client.start().get(20, TimeUnit.SECONDS);
                int before = f.provider.heartbeats;
                transport.port = 19200; transport.revision++; transport.publicationVersion++;
                await(() -> f.provider.heartbeats > before);
                assertEquals(19200, f.provider.lastHeartbeat.getAsJsonObject("hostProfile").getAsJsonArray("candidates").get(0).getAsJsonObject().get("port").getAsInt());
                int replaced = f.provider.heartbeats;
                transport.empty = true; transport.revision++; transport.publicationVersion++;
                await(() -> f.provider.heartbeats > replaced);
                assertTrue(f.provider.lastHeartbeat.getAsJsonObject("hostProfile").getAsJsonArray("candidates").isEmpty());
                assertEquals("serving", f.provider.lastHeartbeat.get("state").getAsString());
                assertTrue(f.provider.lastHeartbeat.get("acceptingPlayers").getAsBoolean());
                assertEquals(0, transport.drains);
            } finally { client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        }
    }

    @Test @Timeout(30) void freshnessOnlyChangesAreCoalescedButRetainTheirNewOriginalExpiry() throws Exception {
        try (var f = new Fixture()) {
            f.provider.checkInMillis = 120000;
            var transport = new Transport(); transport.srflx = true; transport.expiry = System.currentTimeMillis() + 180000;
            var client = f.client(directory, transport, false, "discovered");
            try {
                client.start().get(20, TimeUnit.SECONDS);
                int before = f.provider.heartbeats;
                transport.expiry += 10000; transport.publicationVersion++;
                Thread.sleep(2200);
                assertEquals(before, f.provider.heartbeats, "A fresh same-mapping observation must not send every timer tick");
                client.readiness().get(10, TimeUnit.SECONDS);
                assertEquals(transport.expiry, f.provider.lastHeartbeat.getAsJsonObject("hostProfile").getAsJsonArray("candidates").get(0).getAsJsonObject().get("expiresAt").getAsLong());
            } finally { client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        }
    }

    @Test @Timeout(30) void feedbackKeepsOriginalTimesAndIgnoresExpiredFutureAndWrongRevisionChecks() throws Exception {
        try (var f = new Fixture()) {
            var transport = new Transport(); var client = f.client(directory, transport, false, "discovered");
            try {
                client.start().get(20, TimeUnit.SECONDS);
                long now = System.currentTimeMillis();
                var data = new JsonObject(); data.addProperty("method", "discovered"); data.addProperty("candidateRevision", 1);
                var checks = new JsonArray();
                for (long checked : List.of(now - 1000, now - 300000, now + 10000)) {
                    var check = new JsonObject(); check.addProperty("region", "fixture"); check.addProperty("family", 4);
                    check.addProperty("outcome", "not-established"); check.addProperty("checkedAt", checked); check.addProperty("expiresAt", checked + 60000); checks.add(check);
                }
                data.add("checks", checks);
                var extension = new JsonObject(); extension.addProperty("version", 1); extension.addProperty("critical", false); extension.add("data", data);
                f.provider.extensionMetadata = new JsonObject(); f.provider.extensionMetadata.add(NAMESPACE, extension);
                client.readiness().get(10, TimeUnit.SECONDS);
                assertEquals(List.of(new ProviderTransport.ConnectivityCheck(4, ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED, now - 1000, now + 59000)), transport.feedback.get(0));
                data.addProperty("candidateRevision", 2);
                client.readiness().get(10, TimeUnit.SECONDS);
                assertEquals(1, transport.feedback.size());
            } finally { client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        }
    }

    @Test @Timeout(30) void aRetryCannotSendAProfileWhoseNativeMappingChanged() throws Exception {
        try (var f = new Fixture()) {
            var transport = new Transport(); var client = f.client(directory, transport, false, "discovered");
            try {
                client.start().get(20, TimeUnit.SECONDS);
                var attempts = new java.util.concurrent.atomic.AtomicInteger();
                f.provider.failHeartbeats = 1;
                f.provider.heartbeatAttemptHook = () -> { attempts.incrementAndGet(); transport.port++; transport.revision++; transport.publicationVersion++; };
                assertThrows(ExecutionException.class, () -> client.readiness().get(10, TimeUnit.SECONDS));
                assertEquals(1, attempts.get(), "No second send may reuse the retired capture");
                f.provider.heartbeatAttemptHook = () -> { };
                client.readiness().get(10, TimeUnit.SECONDS);
                assertEquals(transport.port, f.provider.lastHeartbeat.getAsJsonObject("hostProfile").getAsJsonArray("candidates").get(0).getAsJsonObject().get("port").getAsInt());
            } finally { f.provider.heartbeatAttemptHook = () -> { }; client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        }
    }

    @Test @Timeout(30) void shortDiagnosticLeaseDoesNotTurnTheTimerIntoAHeartbeatLoop() throws Exception {
        try (var f = new Fixture()) {
            f.provider.checkInMillis = 10000; // The fixture's lease is only 40s, less than a fixed 60s renewal lead.
            var transport = new Transport(); var client = f.client(directory, transport, true, "defined");
            try {
                client.start().get(20, TimeUnit.SECONDS);
                int before = f.provider.heartbeats;
                Thread.sleep(2200);
                assertEquals(before, f.provider.heartbeats);
            } finally { client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "Expected automatic candidate publication");
    }

}
