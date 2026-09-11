package org.cloudburstmc.netty.util.nethernet;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpRangeSetTest {

    private static boolean contains(IpRangeSet set, String address) throws Exception {
        return set.contains(InetAddress.getByName(address));
    }

    @Test
    void matchesInsideAnIpv4Range() throws Exception {
        IpRangeSet set = IpRangeSet.parse(List.of("10.0.0.0/8"));

        assertTrue(contains(set, "10.0.0.1"));
        assertTrue(contains(set, "10.255.255.254"));
        assertFalse(contains(set, "11.0.0.1"));
    }

    @Test
    void matchesInsideAnIpv6Range() throws Exception {
        IpRangeSet set = IpRangeSet.parse(List.of("2001:db8::/32"));

        assertTrue(contains(set, "2001:db8::1"));
        assertTrue(contains(set, "2001:db8:dead:beef::5"));
        assertFalse(contains(set, "2001:db9::1"));
    }

    @Test
    void aBareAddressCoversOnlyItself() throws Exception {
        IpRangeSet set = IpRangeSet.parse(List.of("192.168.1.5"));

        assertTrue(contains(set, "192.168.1.5"));
        assertFalse(contains(set, "192.168.1.6"));
    }

    @Test
    void combinesEntriesAndSkipsBrokenOnes() throws Exception {
        IpRangeSet set = IpRangeSet.parse(List.of("10.0.0.0/8", "not-an-address", "192.168.1.5", ""));

        assertTrue(contains(set, "10.1.2.3"));
        assertTrue(contains(set, "192.168.1.5"));
        assertFalse(contains(set, "172.16.0.1"));
        assertFalse(set.isEmpty());
    }

    @Test
    void anEmptySetMatchesNothing() throws Exception {
        assertTrue(IpRangeSet.parse(List.of()).isEmpty());
        assertTrue(IpRangeSet.empty().isEmpty());
        assertFalse(contains(IpRangeSet.empty(), "10.0.0.1"));
    }
}
