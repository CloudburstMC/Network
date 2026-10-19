package org.cloudburstmc.netty.signaling.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.cloudburstmc.netty.signaling.ProviderClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Resolving what a host configured into what the provider client is given. Uses a neutral provider
 * rather than Warden, which is only a default a host may carry.
 */
class ProviderRuntimeConfigurationTest {

    @Test
    void onlyAssistedJoinsUseTheWebSocket(@TempDir Path directory) throws Exception {
        var settings =
                ProviderRuntimeConfiguration.Settings.builder("https://signal.example.net")
                        .stunDiscovery(true)
                        .assistedJoins(true)
                        .build();
        var assisted = ProviderRuntimeConfiguration.resolve(settings, directory, "0.0.0.0", 19132, 10)
                .clientConfiguration();
        assertTrue(assisted.assistedJoins());
        assertEquals(ProviderClient.ControlTransport.AUTO, assisted.controlTransport());
        var disabled = ProviderRuntimeConfiguration.Settings.builder("https://signal.example.net").build();
        var plain = ProviderRuntimeConfiguration.resolve(disabled, directory, "0.0.0.0", 19132, 10)
                .clientConfiguration();
        assertFalse(plain.assistedJoins());
        assertEquals(ProviderClient.ControlTransport.HTTP, plain.controlTransport());
    }

    private static final String PROVIDER = "https://signal.example.net";

    private static ProviderRuntimeConfiguration.Settings settings(String endpoint, String token,
                                                                  List<String> advertise,
                                                                  String label) {
        return ProviderRuntimeConfiguration.Settings.builder(endpoint)
                .token(token)
                .advertiseAddresses(advertise)
                .label(label)
                .build();
    }

    private static ProviderRuntimeConfiguration runtime(Path dir, ProviderRuntimeConfiguration.Settings settings)
            throws IOException {
        return ProviderRuntimeConfiguration.resolve(settings, dir, "::", 20000, 40);
    }

    private static ProviderRuntimeConfiguration runtime(Path dir) throws IOException {
        return runtime(dir, settings(PROVIDER, "", List.of(), "Host"));
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
        assertNull(result.clientConfiguration().authorizationToken());
    }

    @Test
    void connectionChecksAndMethodComeFromOrdinaryLocalSettings(@TempDir Path dir) throws Exception {
        assertEquals(false, runtime(dir).clientConfiguration().diagnosticAdmission());
        for (var endpoints : List.of(List.<String>of(), List.of("1.1.1.1:19132"))) {
            var settings =
                    ProviderRuntimeConfiguration.Settings.builder(PROVIDER)
                            .advertiseAddresses(endpoints)
                            .connectionChecks(true)
                            .build();
            var configured = runtime(dir, settings).clientConfiguration();
            assertEquals(true, configured.diagnosticAdmission());
            assertEquals(
                    endpoints.isEmpty() ? "discovered" : "defined",
                    configured.connectivityMethod());
        }
    }

    @Test
    void readsTheSettingsWithoutLeakingTheToken(@TempDir Path dir) throws Exception {
        ProviderRuntimeConfiguration result = runtime(dir, settings(PROVIDER, "configured-secret",
                List.of("1.1.1.1:29133", "[2606:4700:4700::1111]:39133", "1.1.1.1:29133"), "lobby-0"));

        assertEquals("configured-secret", result.authorizationToken());
        assertEquals("configured-secret", result.clientConfiguration().authorizationToken());
        assertEquals("lobby-0", result.clientConfiguration().label());
        // The duplicate endpoint collapses
        assertEquals(2, result.advertisedEndpoints().size());
        assertFalse(result.toString().contains("configured-secret"));
        assertFalse(result.clientConfiguration().toString().contains("configured-secret"));
    }

    @Test
    void namesTheInstanceAfterTheHostUnlessALabelIsConfigured(@TempDir Path dir) throws Exception {
        assertEquals("lobby-0", runtime(dir, settings(PROVIDER, "", List.of(), " lobby-0 ")).label());

        for (String unset : new String[]{null, " "}) {
            String fallback = runtime(dir, settings(PROVIDER, "", List.of(), unset)).label();
            if (fallback != null) {
                assertFalse(fallback.isBlank());
                assertTrue(fallback.length() <= 128);
            }
        }

        assertThrows(IOException.class, () -> runtime(dir, settings(PROVIDER, "", List.of(), "x".repeat(129))));
    }

    @Test
    void readsTokenFilesWithoutLeakingTheirContents(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("token"), "file-secret\n");

        for (String source : List.of("file:token", "./token", dir.resolve("token").toString())) {
            assertEquals("file-secret",
                    runtime(dir, settings(PROVIDER, source, List.of(), null)).authorizationToken());
        }
        for (String value : List.of("file:missing", "file:", "bad secret")) {
            IOException failure = assertThrows(IOException.class,
                    () -> runtime(dir, settings(PROVIDER, value, List.of(), null)));
            assertFalse(failure.toString().contains("bad secret"));
        }

        // A file holding only a line terminator carries no token
        Files.writeString(dir.resolve("token"), "\n");
        assertThrows(IOException.class, () -> runtime(dir, settings(PROVIDER, "file:token", List.of(), null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://signal.example.net", "signal.example.net",
            "https://signal.example.net/path", "https://user@signal.example.net",
            "https://xn--bcher-kva.example", "https://[2001:DB8:0:0::1]", "https://010.0.0.1"})
    void refusesAnInsecureOrMalformedProvider(String endpoint, @TempDir Path dir) {
        assertThrows(IOException.class, () -> runtime(dir, settings(endpoint, "", List.of(), null)));
    }

    @Test
    void allowsPlainHttpOnLoopbackForLocalDevelopment(@TempDir Path dir) throws Exception {
        assertEquals("http://127.0.0.1:8080",
                runtime(dir, settings("http://127.0.0.1:8080", "", List.of(), null)).origin().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"nothing.invalid:19133", "::1:19133", "[::]:19133", "1.1.1.1:0", "1.1.1.1:65536",
            "224.0.0.1:19133", "1.1.1.1:1.5", "[fe80::1]:19133", "203.0.113.1:19133", "[2001:db8::1]:19133",
            "255.255.255.255:19133", "198.18.0.1:19133"})
    void refusesAnAdvertisedEndpointAPeerCouldNotUse(String endpoint, @TempDir Path dir) {
        assertThrows(IOException.class, () -> runtime(dir, settings(PROVIDER, "", List.of(endpoint), null)));
    }

    @Test
    void readsNamesAndTakesTheUdpPortWhenAnEntryNamesNone(@TempDir Path dir) throws Exception {
        var endpoints = runtime(dir, settings(PROVIDER, "", List.of("1.1.1.1", "localhost:29133"), null))
                .advertisedEndpoints();

        assertEquals(new InetSocketAddress(InetAddress.getByName("1.1.1.1"), 20000), endpoints.get(0));
        assertTrue(endpoints.contains(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 29133)),
                endpoints.toString());
    }

    @Test
    void needsAFixedUdpPort(@TempDir Path dir) throws Exception {
        ProviderRuntimeConfiguration.Settings settings = settings(PROVIDER, "", List.of(), null);

        // 0 would be ephemeral, and the provider hands the port out
        assertThrows(IOException.class,
                () -> ProviderRuntimeConfiguration.resolve(settings, dir, "0.0.0.0", 0, 20));
        assertEquals(65535,
                ProviderRuntimeConfiguration.resolve(settings, dir, "0.0.0.0", 65535, 20).udpPort());
    }

    @Test
    void explicitAssistanceReachesClientAndNativeHost(@TempDir Path dir) throws Exception {
        for (boolean assisted : List.of(false, true)) {
            var settings =
                    ProviderRuntimeConfiguration.Settings.builder(PROVIDER)
                            .connectionChecks(true)
                            .stunDiscovery(true)
                            .assistedJoins(assisted)
                            .build();
            var selected = runtime(dir, settings);
            assertEquals(assisted, selected.clientConfiguration().assistedJoins());
            assertEquals(
                    Boolean.toString(assisted),
                    NativeProviderHostFactory.options(selected).get("assistedJoins"));
        }
    }

    @Test
    void stunDiscoveryLeavesStunServersToProviderDiscovery(@TempDir Path dir)
            throws Exception {
        for (var endpoints : List.of(List.<String>of(), List.of("8.8.8.8:19133"))) {
            var settings =
                    ProviderRuntimeConfiguration.Settings.builder(PROVIDER)
                            .advertiseAddresses(endpoints)
                            .connectionChecks(true)
                            .stunDiscovery(true)
                            .build();
            var options = NativeProviderHostFactory.options(runtime(dir, settings));
            assertEquals("maintained-v1", options.get("candidatePublication"));
            assertFalse(options.containsKey("stunServers"));
        }
    }

    @Test
    void defaultHostsAdvertiseTheirGameplayListener(@TempDir Path dir) throws Exception {
        var options = NativeProviderHostFactory.options(runtime(dir));
        assertFalse(options.containsKey("candidatePublication"));
        assertEquals("false", options.get("localDevelopment"));
        assertEquals("true", NativeProviderHostFactory.options(
                runtime(dir, settings("http://127.0.0.1:8080", "", List.of(), null))).get("localDevelopment"));
    }

    @Test
    void assistanceWithoutWarmingKeepsMaintainedPublication(@TempDir Path dir) throws Exception {
        var settings = ProviderRuntimeConfiguration.Settings.builder(PROVIDER)
                .assistedJoins(true)
                .build();
        var options = NativeProviderHostFactory.options(runtime(dir, settings));
        assertEquals("maintained-v1", options.get("candidatePublication"));
        assertEquals("false", options.get("stunWarming"));
    }

    @Test
    void refusesMoreThan32AdvertisedEndpoints(@TempDir Path dir) {
        var endpoints = java.util.stream.IntStream.rangeClosed(1, 33).mapToObj(i -> "8.8.8.8:" + (20000 + i)).toList();
        assertThrows(IOException.class, () -> runtime(dir, settings(PROVIDER, "", endpoints, null)));
    }
}
