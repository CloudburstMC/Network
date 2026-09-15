package org.cloudburstmc.netty.signaling;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ControlledProviderClientTest {
    @Test void durableControlledMarkerRefusesLegacyBeforeDiscovery(@TempDir Path directory) throws Exception {
        var requests = new AtomicInteger(); var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close(); }); server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            try (var store = new ProviderStateStore(directory)) {
                ControlledProviderStateTest.seed(store, origin);
                try (var state = ControlledProviderState.open(store, ControlledProviderStateTest.config(origin))) { }
            }
            var client = client(new ProviderClient.Configuration(URI.create(origin), "nxs-admission-v1", "fixture"), new ProviderStateStore(directory), new Transport());
            try { assertThrows(ExecutionException.class, () -> client.start().get(2, TimeUnit.SECONDS)); assertEquals(0, requests.get()); }
            finally { client.stop().toCompletableFuture().get(2, TimeUnit.SECONDS); }
        } finally { server.stop(0); }
    }
    @Test void configuredControlRefusesLegacyTransportAndDoesNotEnroll(@TempDir Path directory) throws Exception {
        String origin = "http://127.0.0.1:1"; var control = ControlledProviderStateTest.config(origin);
        var config = new ProviderClient.Configuration(URI.create(origin), "nxs-admission-v1", "fixture", ProviderClient.NEW_SERVICE,
                ProviderClient.ANONYMOUS_PROOF_OF_WORK, null, null, null, Map.of(), control);
        var transport = new Transport(); var client = client(config, new ProviderStateStore(directory), transport);
        try {
            var error = assertThrows(ExecutionException.class, () -> client.start().get(2, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("admission staging")); assertEquals(0, transport.installs);
            assertThrows(ExecutionException.class, () -> client.rotateMachineKey().get(2, TimeUnit.SECONDS));
        } finally { client.stop().toCompletableFuture().get(2, TimeUnit.SECONDS); }
    }
    private static ProviderClient client(ProviderClient.Configuration config, ProviderStateStore store, ProviderTransport transport) {
        return new ProviderClient(config, store, transport, () -> null, () -> new ProviderClient.Health(true, false, 1, 0, "fixture", null), ignored -> { });
    }
    static final class Transport implements ProviderTransport {
        int installs;
        @Override public CompletionStage<JsonObject> hostProfile() { return CompletableFuture.failedFuture(new AssertionError()); }
        @Override public CompletionStage<Void> installTicketKeys(List<TicketKey> keys) { installs++; return CompletableFuture.failedFuture(new AssertionError()); }
        @Override public CompletionStage<ApplyResult> applyState(String state) { return CompletableFuture.completedFuture(ApplyResult.REJECTED); }
        @Override public List<JsonObject> pollEvents() { return List.of(); }
        @Override public CompletionStage<Void> drain() { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> close() { return CompletableFuture.completedFuture(null); }
    }
}
