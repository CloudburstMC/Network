package org.cloudburstmc.netty.signaling;

import org.cloudburstmc.netty.signaling.admission.AdmissionKey;
import com.google.gson.*;

import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Loopback-only Java-to-Worker workflow. It intentionally proves no native gameplay.
 */
public final class ProviderBench {
    public static void main(String[] args) throws Exception {
        URI provider = URI.create(System.getProperty("providerOrigin", "http://127.0.0.1:8787"));
        if (!Set.of("127.0.0.1", "localhost", "[::1]").contains(provider.getHost())) {
            throw new IllegalArgumentException("Fixture transport is loopback only");
        }
        Path state = Path.of(System.getProperty("providerState"));
        if (System.getProperty("providerMode", "once").equals("identity")) {
            try (var store = new ProviderStateStore(state)) {
                System.out.println(ProviderIdentity.initialize(store, provider));
            }
            return;
        }
        String token = System.getProperty("providerToken");
        ProviderTransport transport = new ProviderTransport() {
            String keyId;
            final String incarnation = UUID.randomUUID().toString().replace("-", "");

            public CompletionStage<Void> installAdmissionKeys(List<AdmissionKey> keys) {
                keyId = keys.get(keys.size() - 1).keyId();
                return CompletableFuture.completedFuture(null);
            }

            public CompletionStage<HostProfileSnapshot> captureHostProfile() {
                return hostProfile().thenApply(profile -> new HostProfileSnapshot(profile, () -> {}));
            }

            CompletionStage<JsonObject> hostProfile() {
                JsonObject p = new JsonObject();
                p.addProperty("admissionKeyId", keyId);
                p.addProperty("dtlsFingerprint", "sha-256 " + String.join(":", Collections.nCopies(32, "11")));
                p.addProperty("sctpPort", 5000);
                p.addProperty("maxMessageSize", 262144);
                JsonObject c = new JsonObject();
                c.addProperty("address", "127.0.0.1");
                c.addProperty("port", 19133);
                c.addProperty("foundation", "fixture");
                c.addProperty("priority", 100);
                c.addProperty("type", "host");
                JsonArray candidates = new JsonArray();
                candidates.add(c);
                p.add("candidates", candidates);
                JsonObject capability = new JsonObject();
                capability.addProperty("capability", "nethernet.stateless-admission.v1");
                capability.addProperty("incarnation", incarnation);
                p.add("statelessAdmission", capability);
                return CompletableFuture.completedFuture(p);
            }

            public List<JsonObject> pollEvents(int maximum) {
                return List.of();
            }

            public CompletionStage<Void> drain() {
                return CompletableFuture.completedFuture(null);
            }

            public CompletionStage<Void> close() {
                return CompletableFuture.completedFuture(null);
            }
        };
        var config =
                ProviderClient.Configuration.builder(provider)
                        .label("Java conformance backend")
                        .authorizationToken(token)
                        .controlTransport(ProviderClient.ControlTransport.valueOf(
                                System.getProperty("providerControlTransport", "HTTP").toUpperCase(Locale.ROOT)))
                        .build();
        var client =
                new ProviderClient(
                        config,
                        new ProviderStateStore(state),
                        transport,
                        () -> new ServerStatus("Java bench", "Fixture", 2, 50, 0),
                        () -> new ProviderClient.Health(true, 100, new ProviderClient.Software("Java conformance bench", "1.0"), null),
                        diagnostic ->
                                System.err.println(
                                        diagnostic.level() + ": " + diagnostic.message()));
        try {
            var registration = client.start().get(30, TimeUnit.SECONDS);
            String operatorActionFile = System.getProperty("providerOperatorActionFile");
            if (operatorActionFile != null) {
                OperatorActionFile.write(Path.of(operatorActionFile), client.operatorAction().get(10, TimeUnit.SECONDS));
            }
            System.out.println("instance=" + registration.instanceId() + " service=" + (
                    registration.serviceId() != null ? registration.serviceId() : "unassigned"));
            JsonObject readiness = client.readiness().get(10, TimeUnit.SECONDS);
            readiness.remove("extensions");
            readiness.addProperty("controlCarrier", client.lastControlCarrier());
            readiness.addProperty("instanceId", registration.instanceId());
            readiness.addProperty("leaseGeneration", registration.leaseGeneration());
            if (registration.serviceId() != null) {
                readiness.addProperty("serviceId", registration.serviceId());
            }
            System.out.println(readiness);
            long hold = Long.parseLong(System.getProperty("providerHoldSeconds", "0"));
            String stopFile = System.getProperty("providerStopFile");
            if (stopFile != null) {
                long until = System.currentTimeMillis() + 180000;
                while (!Files.exists(Path.of(stopFile)) && System.currentTimeMillis() < until) {
                    Thread.sleep(100);
                }
            } else if (hold > 0) {
                Thread.sleep(hold * 1000);
            }
        } finally {
            client.stop().toCompletableFuture().get(20, TimeUnit.SECONDS);
        }
    }
}
