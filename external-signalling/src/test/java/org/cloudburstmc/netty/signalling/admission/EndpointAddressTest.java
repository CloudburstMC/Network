package org.cloudburstmc.netty.signalling.admission;

import org.junit.jupiter.api.Test;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import static org.junit.jupiter.api.Assertions.*;

class EndpointAddressTest {
    @Test void classifiesPrivateSharedAndPublicBoundaries() throws Exception {
        for (String ip : new String[]{"10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1", "100.64.0.1", "100.127.255.255", "fc00::1", "fd7a:115c:a1e0::1"})
            assertEquals(EndpointAddress.Scope.PRIVATE, EndpointAddress.scope(EndpointAddress.parse(ip)), ip);
        for (String ip : new String[]{"8.8.8.8", "172.15.255.255", "172.32.0.0", "100.63.255.255", "100.128.0.0", "192.0.0.9", "2606:4700:4700::1111", "2001:4860::1"})
            assertEquals(EndpointAddress.Scope.PUBLIC, EndpointAddress.scope(EndpointAddress.parse(ip)), ip);
    }
    @Test void excludesUnusableAddressesAndOnlyAllowsFixturesExplicitly() throws Exception {
        for (String ip : new String[]{"0.0.0.0", "169.254.1.1", "224.0.0.1", "255.255.255.255", "198.18.0.1", "::", "fe80::1", "fec0::1", "ff02::1", "64:ff9b::808:808", "2001:2::1", "2002:808:808::1"})
            assertFalse(EndpointAddress.advertisable(EndpointAddress.parse(ip), true), ip);
        for (String ip : new String[]{"127.0.0.1", "::1", "192.0.2.1", "2001:db8::1", "3fff::1"}) {
            assertFalse(EndpointAddress.advertisable(EndpointAddress.parse(ip), false), ip);
            assertTrue(EndpointAddress.advertisable(EndpointAddress.parse(ip), true), ip);
        }
    }
    @Test void normalizesMappedIpv4AndRejectsDnsAndAmbiguousLiterals() throws Exception {
        var mapped = EndpointAddress.parse("::ffff:192.168.1.2");
        assertInstanceOf(Inet4Address.class, mapped);
        assertEquals(EndpointAddress.Scope.PRIVATE, EndpointAddress.scope(mapped));
        for (String ip : new String[]{"localhost", "game.example", "127.1", "010.0.0.1", "256.0.0.1", "fe80::1%eth0", "[::1]", "2001:::1", "::ffff:192.168.001.1", "8.8.8.8\n"})
            assertThrows(UnknownHostException.class, () -> EndpointAddress.parse(ip), ip);
    }
}
