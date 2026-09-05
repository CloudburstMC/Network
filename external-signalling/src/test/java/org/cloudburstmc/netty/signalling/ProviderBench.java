package org.cloudburstmc.netty.signalling;

import com.google.gson.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only Java-to-Worker workflow. It intentionally proves no native gameplay. */
public final class ProviderBench {
    public static void main(String[] args) throws Exception {
        URI provider = URI.create(System.getProperty("providerOrigin", "http://127.0.0.1:8787"));
        if (!Set.of("127.0.0.1", "localhost", "[::1]").contains(provider.getHost())) throw new IllegalArgumentException("Fixture transport is loopback only");
        Path state = Path.of(System.getProperty("providerState"));
        if (System.getProperty("providerMode", "once").equals("identity")) { try (var store = new ProviderStateStore(state)) { System.out.println(ProviderIdentity.initialize(store, provider)); } return; }
        String token = System.getProperty("providerToken");
        ProviderTransport transport = new ProviderTransport() {
            String keyId; final String incarnation = UUID.randomUUID().toString().replace("-", "");
            public CompletionStage<Void> installTicketKeys(List<TicketKey> keys) { keyId = keys.getLast().keyId(); return CompletableFuture.completedFuture(null); }
            public CompletionStage<JsonObject> hostProfile() {
                JsonObject p = new JsonObject(); p.addProperty("credentialKeyId", keyId); p.addProperty("dtlsFingerprint", "sha-256 " + String.join(":", Collections.nCopies(32, "11"))); p.addProperty("sctpPort", 5000); p.addProperty("maxMessageSize", 262144);
                JsonObject c = new JsonObject(); c.addProperty("address", "127.0.0.1"); c.addProperty("port", 19133); c.addProperty("foundation", "fixture"); c.addProperty("component", 1); c.addProperty("priority", 100); c.addProperty("protocol", "udp"); c.addProperty("type", "host"); JsonArray candidates = new JsonArray(); candidates.add(c); p.add("candidates", candidates); JsonObject capability = new JsonObject(); capability.addProperty("capability", "nethernet.stateless-admission.v1"); capability.addProperty("incarnation", incarnation); p.add("statelessAdmission", capability); return CompletableFuture.completedFuture(p);
            }
            public CompletionStage<ApplyResult> applyControl(JsonObject c) { return CompletableFuture.completedFuture(ApplyResult.REJECTED); }
            public List<JsonObject> pollEvents() { return List.of(); }
            public CompletionStage<Void> drain() { return CompletableFuture.completedFuture(null); }
            public CompletionStage<Void> close() { return CompletableFuture.completedFuture(null); }
        };
        String registrationMode = System.getProperty("providerRegistrationMode", token == null ? ProviderClient.NEW_SERVICE : ProviderClient.ATTACH_INSTANCE);
        String authorization = token == null ? ProviderClient.ANONYMOUS_PROOF_OF_WORK : ProviderClient.BEARER_TOKEN;
        Map<String, String> tags = new TreeMap<>();
        JsonObject configuredTags = JsonParser.parseString(System.getProperty("providerTags", "{}")).getAsJsonObject();
        for (var entry : configuredTags.entrySet()) tags.put(entry.getKey(), entry.getValue().getAsString());
        String region = System.getProperty("providerRegion", registrationMode.equals(ProviderClient.ATTACH_INSTANCE) ? "EU" : null);
        String pool = System.getProperty("providerPool", registrationMode.equals(ProviderClient.ATTACH_INSTANCE) ? "proxy" : null);
        var config = new ProviderClient.Configuration(provider, "nxs-admission-v1", "Java conformance backend", registrationMode, authorization, token, region, pool, tags);
        var client = new ProviderClient(config, new ProviderStateStore(state), transport, () -> new ServerStatus("Java bench", 1234, "fixture-only", "Fixture", 2, 50, 0), () -> new ProviderClient.Health(true, 100, 0.02, "nethernet", "java-conformance"), System.err::println);
        try {
            JsonObject registration = client.start().get(30, TimeUnit.SECONDS);
            String extensionsFile = System.getProperty("providerExtensionsFile");
            if (extensionsFile != null) ExtensionFixtureFile.write(Path.of(extensionsFile), client.extensions().get(10, TimeUnit.SECONDS));
            System.out.println("instance=" + registration.get("instanceId").getAsString() + " service=" + registration.get("serviceId").getAsString());
            JsonObject readiness = client.readiness().get(10, TimeUnit.SECONDS); readiness.remove("extensions"); System.out.println(readiness);
            long hold = Long.parseLong(System.getProperty("providerHoldSeconds", "0"));
            String stopFile = System.getProperty("providerStopFile");
            if (stopFile != null) { long until = System.currentTimeMillis() + 180000; while (!Files.exists(Path.of(stopFile)) && System.currentTimeMillis() < until) Thread.sleep(100); }
            else if (hold > 0) Thread.sleep(hold * 1000);
        } finally { client.stop().toCompletableFuture().get(20, TimeUnit.SECONDS); }
    }
}
