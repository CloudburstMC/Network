package org.cloudburstmc.netty.signaling.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import org.cloudburstmc.netty.signaling.ProviderClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Resolving what a host configured into what the provider client is given. Uses a neutral provider
 * rather than Warden, which is only a default a host may carry.
 */
class ProviderRuntimeConfigurationTest {

    @Test void assistedOptInUsesExistingAdapterAndRequiresAuto(@TempDir Path directory) throws Exception {
        var settings = new ProviderRuntimeConfiguration.Settings("https://signal.example.net", "", List.of(), Map.of(),
                ProviderClient.ControlTransport.AUTO, false, true, true);
        assertTrue(ProviderRuntimeConfiguration.resolve(settings,directory,"0.0.0.0",19132,10,"test").clientConfiguration().assistedJoins());
        var disabled = new ProviderRuntimeConfiguration.Settings("https://signal.example.net", "",List.of(),Map.of());
        assertFalse(ProviderRuntimeConfiguration.resolve(disabled,directory,"0.0.0.0",19132,10,"test").clientConfiguration().assistedJoins());
        var http = new ProviderRuntimeConfiguration.Settings("https://signal.example.net", "", List.of(), Map.of(),
                ProviderClient.ControlTransport.HTTP, false, true, true);
        assertTrue(assertThrows(IOException.class, () -> ProviderRuntimeConfiguration.resolve(http,directory,"0.0.0.0",19132,10,"test")).getMessage().contains("control-transport=auto"));
    }

    private static final String PROVIDER = "https://signal.example.net";

    private static ProviderRuntimeConfiguration.Settings settings(String endpoint, String token,
                                                                  List<String> advertise,
                                                                  Map<String, String> data) {
        return new ProviderRuntimeConfiguration.Settings(endpoint, token, advertise, data);
    }

    private static ProviderRuntimeConfiguration runtime(Path dir, ProviderRuntimeConfiguration.Settings settings)
            throws IOException {
        return ProviderRuntimeConfiguration.resolve(settings, dir, "::", 20000, 40, "Host");
    }

    private static ProviderRuntimeConfiguration runtime(Path dir) throws IOException {
        return runtime(dir, settings(PROVIDER, "", List.of(), Map.of()));
    }

    @Test
    void inheritsTheListenerAndCapacityFromTheHost(@TempDir Path dir) throws Exception {
        ProviderRuntimeConfiguration result = runtime(dir);

        assertEquals(PROVIDER, result.origin().toString());
        assertEquals("::", result.bindAddress());
        assertEquals(20000, result.udpPort());
        assertEquals(40, result.capacity());
        assertEquals("Host", result.label());
        assertEquals(dir.resolve("provider-state"), result.stateDirectory());
        assertEquals("automatic", result.clientConfiguration().registrationMode());
        assertEquals("anonymous-proof-of-work", result.clientConfiguration().authorizationScheme());
        assertEquals(org.cloudburstmc.netty.signaling.ProviderClient.ControlTransport.HTTP, result.clientConfiguration().controlTransport());
        var automatic = runtime(dir, new ProviderRuntimeConfiguration.Settings(PROVIDER, "", List.of(), Map.of(),
                org.cloudburstmc.netty.signaling.ProviderClient.ControlTransport.AUTO));
        assertEquals(org.cloudburstmc.netty.signaling.ProviderClient.ControlTransport.AUTO, automatic.clientConfiguration().controlTransport());
    }

    @Test
    void diagnosticOptInAndMethodComeFromOrdinaryLocalSettings(@TempDir Path dir) throws Exception {
        assertEquals(false, runtime(dir).clientConfiguration().diagnosticAdmission());
        for (var endpoints : List.of(List.<String>of(), List.of("1.1.1.1:19132"))) {
            var settings = new ProviderRuntimeConfiguration.Settings(PROVIDER, "", endpoints, Map.of(),
                    org.cloudburstmc.netty.signaling.ProviderClient.ControlTransport.HTTP, true);
            var configured = runtime(dir, settings).clientConfiguration();
            assertEquals(true, configured.diagnosticAdmission());
            assertEquals(endpoints.isEmpty() ? "discovered" : "defined", configured.connectivityMethod());
        }
    }

    @Test
    void readsTheSettingsWithoutLeakingTheToken(@TempDir Path dir) throws Exception {
        ProviderRuntimeConfiguration result = runtime(dir, settings(PROVIDER, "configured-secret",
                List.of("1.1.1.1:29133", "[2606:4700:4700::1111]:39133", "1.1.1.1:29133"),
                Map.of("region", "EU", "pool", "proxy", "location", "london")));

        assertEquals("configured-secret", result.authorizationToken());
        assertEquals("bearer-token", result.clientConfiguration().authorizationScheme());
        assertEquals("EU", result.region());
        assertEquals("proxy", result.pool());
        assertEquals(Map.of("location", "london"), result.tags());
        // The duplicate endpoint collapses
        assertEquals(2, result.advertisedEndpoints().size());
        assertFalse(result.toString().contains("configured-secret"));
        assertFalse(result.clientConfiguration().toString().contains("configured-secret"));
    }

    @Test
    void fillsInThePlacementWhenOnlyPartOfItIsConfigured(@TempDir Path dir) throws Exception {
        ProviderRuntimeConfiguration result = runtime(dir,
                settings(PROVIDER, "", List.of(), Map.of("region", "EU", "role", "proxy")));

        assertEquals("EU", result.region());
        assertEquals("default", result.pool());
        assertEquals(Map.of("role", "proxy"), result.tags());
        assertNull(result.authorizationToken());
    }

    @Test
    void readsTokenFilesWithoutLeakingTheirContents(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("token"), "file-secret\n");

        for (String source : List.of("file:token", "./token", dir.resolve("token").toString())) {
            assertEquals("file-secret",
                    runtime(dir, settings(PROVIDER, source, List.of(), Map.of())).authorizationToken());
        }
        for (String value : List.of("file:missing", "file:", "bad secret")) {
            IOException failure = assertThrows(IOException.class,
                    () -> runtime(dir, settings(PROVIDER, value, List.of(), Map.of())));
            assertFalse(failure.toString().contains("bad secret"));
        }

        // A file holding only a line terminator carries no token
        Files.writeString(dir.resolve("token"), "\n");
        assertThrows(IOException.class, () -> runtime(dir, settings(PROVIDER, "file:token", List.of(), Map.of())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://signal.example.net", "signal.example.net",
            "https://signal.example.net/path", "https://user@signal.example.net"})
    void refusesAnInsecureOrMalformedProvider(String endpoint, @TempDir Path dir) {
        assertThrows(IOException.class, () -> runtime(dir, settings(endpoint, "", List.of(), Map.of())));
    }

    @Test
    void allowsPlainHttpOnLoopbackForLocalDevelopment(@TempDir Path dir) throws Exception {
        assertEquals("http://127.0.0.1:8080",
                runtime(dir, settings("http://127.0.0.1:8080", "", List.of(), Map.of())).origin().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com:19133", "::1:19133", "[::]:19133", "1.1.1.1:0", "1.1.1.1:65536",
            "224.0.0.1:19133", "1.1.1.1:1.5", "[fe80::1]:19133", "203.0.113.1:19133", "[2001:db8::1]:19133",
            "255.255.255.255:19133", "198.18.0.1:19133"})
    void refusesAnAdvertisedEndpointAPeerCouldNotUse(String endpoint, @TempDir Path dir) {
        assertThrows(IOException.class, () -> runtime(dir, settings(PROVIDER, "", List.of(endpoint), Map.of())));
    }

    @Test
    void needsAFixedUdpPort(@TempDir Path dir) throws Exception {
        ProviderRuntimeConfiguration.Settings settings = settings(PROVIDER, "", List.of(), Map.of());

        // 0 would be ephemeral, and the provider hands the port out
        assertThrows(IOException.class,
                () -> ProviderRuntimeConfiguration.resolve(settings, dir, "0.0.0.0", 0, 20, "Host"));
        assertEquals(65535,
                ProviderRuntimeConfiguration.resolve(settings, dir, "0.0.0.0", 65535, 20, "Host").udpPort());
    }

    @Test void explicitAssistanceReachesClientAndNativeHost(@TempDir Path dir) throws Exception {
        for (boolean assisted : List.of(false, true)) {
            var settings = new ProviderRuntimeConfiguration.Settings(PROVIDER, "", List.of(), Map.of(),
                    ProviderClient.ControlTransport.AUTO, true, true, assisted);
            var selected = runtime(dir, settings);
            assertEquals(assisted, selected.clientConfiguration().assistedJoins());
            assertEquals(Boolean.toString(assisted), selected.nativeHostOptions().get("assistedJoins"));
        }
    }
    @Test void maintainedSettingsLeaveStunConfigurationToProviderDiscovery(@TempDir Path dir) throws Exception {
        for (var endpoints : List.of(List.<String>of(), List.of("8.8.8.8:19133"))) {
            var settings = new ProviderRuntimeConfiguration.Settings(PROVIDER, "", endpoints, Map.of(),
                    ProviderClient.ControlTransport.HTTP, true, true);
            var selected = runtime(dir, settings);
            assertEquals("maintained-v1", selected.nativeHostOptions().get("candidatePublication"));
            assertFalse(selected.nativeHostOptions().containsKey("stunServers"));
        }
    }
    @Test void disabledWarmingKeepsPublicationAndRecoveryPolicy(@TempDir Path dir) throws Exception {
        var settings = new ProviderRuntimeConfiguration.Settings(PROVIDER, "", List.of(), Map.of(),
            ProviderClient.ControlTransport.HTTP, true, false, false);
        var selected = runtime(dir, settings);
        assertEquals("maintained-v1", selected.nativeHostOptions().get("candidatePublication"));
        assertEquals("false", selected.nativeHostOptions().get("stunWarming"));
    }

}
