package org.cloudburstmc.netty.signaling.provider;

import io.netty.bootstrap.ServerBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class NativeProviderHostFactoryTest {
    @Test void diagnosticOptionRequiresExplicitControlledV2SelectionBeforeIdentity(@TempDir Path directory) {
        for (Map<String, String> selected : List.of(Map.of("diagnosticAdmission", "true"), Map.of("diagnosticAdmission", "install-v1"),
                Map.of("diagnosticAdmission", "install-v1", "controlMode", "nethernet-control-v1"))) {
            var options = new HashMap<>(selected); options.put("stateDirectory", directory.resolve("identity").toString());
            assertThrows(CompletionException.class, () -> new NativeProviderHostFactory().open(new ServerBootstrap(), endpoint("127.0.0.1", 19133), options).toCompletableFuture().join());
            assertFalse(Files.exists(directory.resolve("identity")));
        }
    }
    @Test void maintainedPathAllowsEmptyBindFirstAndConfiguredEndpointsSuppressStunParsing() throws Exception {
        var empty = NativeProviderHostFactory.maintainedSelection(endpoint("10.0.0.1", 19133), strict("[]"));
        assertTrue(empty.candidates().isEmpty());
        var options = new HashMap<>(strict("[{\"address\":\"2606:4700:4700::1111\",\"port\":39133}]"));
        options.put("stunServers", "not even JSON: configured endpoints suppress this unused input");
        var explicit = NativeProviderHostFactory.maintainedSelection(endpoint("0.0.0.0", 19133), options);
        assertEquals(List.of(endpoint("2606:4700:4700::1111", 39133)), explicit.candidates().stream().map(c -> c.endpoint()).toList());
        assertTrue(NativeProviderHostFactory.stunServers(explicit, options).isEmpty());
    }

    @Test void maintainedNumericServersAreStrictPerFamilyAndDirectPrecedenceIsIndependent() throws Exception {
        var selection = NativeProviderHostFactory.maintainedSelection(endpoint("10.0.0.1", 19133), strict("[]"));
        for (String servers : List.of("[{\"address\":\"stun.example\",\"port\":3478}]", "[{\"address\":\"1.1.1.1\",\"port\":1.5}]",
                "[{\"address\":\"1.1.1.1\",\"port\":3478},{\"address\":\"1.0.0.1\",\"port\":3478}]"))
            assertThrows(IllegalArgumentException.class, () -> NativeProviderHostFactory.stunServers(selection, Map.of("stunServers", servers)));
        String both = "[{\"address\":\"1.1.1.1\",\"port\":3478},{\"address\":\"2606:4700:4700::1111\",\"port\":3478}]";
        assertEquals(1, NativeProviderHostFactory.stunServers(selection, Map.of("stunServers", both)).size());
        var direct = NativeProviderHostFactory.maintainedSelection(endpoint("8.8.8.8", 19133), strict("[]"));
        assertTrue(NativeProviderHostFactory.stunServers(direct, Map.of("stunServers", both)).isEmpty());
    }
    private static Map<String, String> strict(String endpoints) {
        return Map.of("endpointPolicy", NativeProviderHostFactory.EXPLICIT_OR_PUBLIC_LOCAL, "advertisedEndpoints", endpoints);
    }
    private static InetSocketAddress endpoint(String address, int port) { return new InetSocketAddress(address, port); }

    @Test void configuredFamiliesAreCompleteAndForwardedPortsSurvive() throws Exception {
        var v4 = "[{\"address\":\"8.8.8.8\",\"port\":29133}]";
        var v6 = "[{\"address\":\"2606:4700:4700::1111\",\"port\":39133}]";
        assertEquals(List.of(endpoint("8.8.8.8", 29133)), NativeProviderHostFactory.endpointSource(endpoint("::", 19133), strict(v4)).get().advertised());
        assertEquals(List.of(endpoint("2606:4700:4700::1111", 39133)), NativeProviderHostFactory.endpointSource(endpoint("0.0.0.0", 19133), strict(v6)).get().advertised());
        var both = "[{\"address\":\"8.8.8.8\",\"port\":29133},{\"address\":\"2606:4700:4700::1111\",\"port\":39133}]";
        assertEquals(List.of(endpoint("8.8.8.8", 29133), endpoint("2606:4700:4700::1111", 39133)),
                NativeProviderHostFactory.endpointSource(endpoint("1.1.1.1", 19133), strict(both)).get().advertised());
    }

    @Test void refreshedSupplierRetainsExclusiveParsedConfiguration() throws Exception {
        var options = new HashMap<>(strict("[{\"address\":\"8.8.8.8\",\"port\":29133}]"));
        var source = NativeProviderHostFactory.endpointSource(endpoint("1.1.1.1", 19133), options);
        assertEquals(List.of(endpoint("8.8.8.8", 29133)), source.get().advertised());
        options.remove("endpointPolicy"); options.put("advertisedEndpoints", "[]");
        assertEquals(List.of(endpoint("8.8.8.8", 29133)), source.get().advertised(), "A profile refresh must not add a public bind or switch to discovery");
    }

    @Test void automaticSelectionUsesPublicBindAndRealGameplayPort() throws Exception {
        for (String address : List.of("1.1.1.1", "2606:4700:4700::1111")) {
            var bind = endpoint(address, 19133);
            assertEquals(List.of(bind), NativeProviderHostFactory.endpointSource(bind, strict("[]")).get().advertised());
        }
    }

    @Test void emptyStrictDiscoveryFailsBeforeIdentityOrNativeOpening(@TempDir Path directory) {
        var options = new HashMap<>(strict("[]")); var identity = directory.resolve("identity"); options.put("stateDirectory", identity.toString());
        var failure = assertThrows(CompletionException.class, () -> new NativeProviderHostFactory().open(new ServerBootstrap(), endpoint("10.0.0.1", 19133), options).toCompletableFuture().join());
        assertInstanceOf(IOException.class, failure.getCause()); assertTrue(failure.getCause().getMessage().contains("No public local UDP endpoints"));
        assertFalse(Files.exists(identity));
    }

    @Test void invalidConfiguredValueCannotFallBackToPublicBind(@TempDir Path directory) {
        for (String address : List.of("0.0.0.0", "::", "127.0.0.1", "fe80::1", "not-an-ip")) {
            var options = new HashMap<>(strict("[{\"address\":\"" + address + "\",\"port\":19133}]"));
            var identity = directory.resolve("identity"); options.put("stateDirectory", identity.toString());
            var failure = assertThrows(CompletionException.class, () -> new NativeProviderHostFactory().open(new ServerBootstrap(), endpoint("1.1.1.1", 19133), options).toCompletableFuture().join());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause()); assertFalse(Files.exists(identity));
        }
    }

    @Test void strictPortsAndConfiguredCountAreValidatedBeforeFallback(@TempDir Path directory) {
        for (String port : List.of("1.5", "\"1.5\"", "2147483648", "4294967297", "0", "65536", "-1", "\"19133\"")) {
            var options = new HashMap<>(strict("[{\"address\":\"8.8.8.8\",\"port\":" + port + "}]"));
            var identity = directory.resolve("identity"); options.put("stateDirectory", identity.toString());
            var failure = assertThrows(CompletionException.class, () -> new NativeProviderHostFactory().open(new ServerBootstrap(), endpoint("1.1.1.1", 19133), options).toCompletableFuture().join());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause()); assertFalse(Files.exists(identity));
        }
        String tooMany = "[" + String.join(",", java.util.Collections.nCopies(33, "{\"address\":\"8.8.8.8\",\"port\":19133}")) + "]";
        assertThrows(IllegalArgumentException.class, () -> NativeProviderHostFactory.endpointSource(endpoint("1.1.1.1", 19133), strict(tooMany)));
    }

    @Test void rejectsUnknownPolicyBeforeIdentityOrNativeOpening(@TempDir Path directory) {
        for (String policy : List.of("", "automatic", "explicit-or-public-loca")) {
            var identity = directory.resolve("identity");
            var options = Map.of("stateDirectory", identity.toString(), "endpointPolicy", policy);
            var failure = assertThrows(CompletionException.class, () -> new NativeProviderHostFactory().open(new ServerBootstrap(), endpoint("1.1.1.1", 19133), options).toCompletableFuture().join());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause()); assertFalse(Files.exists(identity));
        }
    }

    @Test void policyIsIndependentOfControlModeAndAbsentPolicyPreservesAdditiveBehavior() throws Exception {
        var endpointJson = "[{\"address\":\"8.8.8.8\",\"port\":29133}]";
        var bind = endpoint("1.1.1.1", 19133);
        var options = new HashMap<>(strict(endpointJson)); options.put("controlMode", "nethernet-control-v1");
        assertEquals(List.of(endpoint("8.8.8.8", 29133)), NativeProviderHostFactory.endpointSource(bind, options).get().advertised());
        var legacy = NativeProviderHostFactory.endpointSource(bind, Map.of("advertisedEndpoints", endpointJson)).get().advertised();
        assertEquals(2, legacy.size()); assertTrue(legacy.contains(bind)); assertTrue(legacy.contains(endpoint("8.8.8.8", 29133)));
    }
}
