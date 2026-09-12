package org.cloudburstmc.netty.signalling.provider;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProviderEndpointTest {
    private static InetAddress address(String value) throws Exception { return InetAddress.getByName(value); }
    private static InetSocketAddress endpoint(String value, int port) { return new InetSocketAddress(value, port); }

    @Test void concreteBindingPublishesOnlyItsAddressPlusConfiguredForwarding() throws Exception {
        var bind = endpoint("192.168.1.5", 19133);
        var external = endpoint("8.8.8.8", 29133);
        var result = ProviderEndpoint.resolve(bind, List.of(external), false, List.of(address("10.0.0.2")));
        assertEquals(List.of(external, bind), result.advertised());
        assertTrue(result.warnings().isEmpty(), "Public forwarding avoids a misleading private-only warning");
    }

    @Test void wildcardEnumeratesMultipleBoundInterfacesAndDeduplicates() throws Exception {
        var result = ProviderEndpoint.resolve(endpoint("0.0.0.0", 19133), List.of(endpoint("8.8.8.8", 29133)), false,
            List.of(address("127.0.0.1"), address("169.254.1.1"), address("192.168.1.5"), address("100.117.6.116"), address("8.8.8.8"), address("8.8.8.8"), address("fd00::1")));
        assertEquals(List.of(endpoint("8.8.8.8", 19133), endpoint("8.8.8.8", 29133), endpoint("100.117.6.116", 19133), endpoint("192.168.1.5", 19133)), result.advertised());
    }

    @Test void ipv6WildcardDiscoversBothFamiliesButConcreteIpv6DoesNot() throws Exception {
        var interfaces = List.of(address("8.8.8.8"), address("2606:4700:4700::1111"), address("fd00::1"), address("10.0.0.1"));
        var result = ProviderEndpoint.resolve(endpoint("::", 19133), List.of(), false, interfaces);
        assertEquals(List.of(endpoint("2606:4700:4700::1111", 19133), endpoint("8.8.8.8", 19133), endpoint("fd00::1", 19133), endpoint("10.0.0.1", 19133)), result.advertised());
        var concrete = ProviderEndpoint.resolve(endpoint("fd00::1", 19133), List.of(), false, interfaces);
        assertEquals(List.of(endpoint("fd00::1", 19133)), concrete.advertised());
        assertEquals(2, concrete.warnings().size());
        assertTrue(concrete.warnings().get(0).contains("does not relay"));
        assertTrue(concrete.warnings().get(1).contains("IPv6"));
    }

    @Test void explicitForwardersMayTranslateAddressFamilies() throws Exception {
        var result = ProviderEndpoint.resolve(endpoint("fd00::1", 19133), List.of(endpoint("8.8.8.8", 29133)), false, List.of());
        assertTrue(result.warnings().isEmpty());
        assertEquals(2, result.advertised().size());
    }

    @Test void explicitForwardersMayTargetLoopbackWithoutPublishingLoopback() throws Exception {
        var bind = endpoint("127.0.0.1", 19133);
        var external = endpoint("8.8.8.8", 29133);
        var result = ProviderEndpoint.resolve(bind, List.of(external), false, List.of());
        assertEquals(bind, result.bind());
        assertEquals(List.of(external), result.advertised());
        assertTrue(result.warnings().isEmpty());
    }

    @Test void unusableOrEmptySnapshotsNeverBecomeProfiles() throws Exception {
        var bind = endpoint("0.0.0.0", 19133);
        for (String ip : List.of("0.0.0.0", "::", "169.254.1.1", "fe80::1", "127.0.0.1", "224.0.0.1", "203.0.113.1"))
            assertThrows(IOException.class, () -> ProviderEndpoint.resolve(bind, List.of(endpoint(ip, 19133)), false, List.of()), ip);
        assertThrows(IOException.class, () -> ProviderEndpoint.resolve(bind, List.of(), false, List.of()));
        assertThrows(IOException.class, () -> ProviderEndpoint.resolve(endpoint("127.0.0.1", 19133), List.of(), false, List.of()));
        assertEquals(List.of(endpoint("127.0.0.1", 19133)), ProviderEndpoint.resolve(endpoint("127.0.0.1", 19133), List.of(), true, List.of()).advertised());
    }
}
