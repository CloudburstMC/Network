package org.cloudburstmc.netty.channel.nethernet;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NetherNetChannelPathTest {

    private static final String SDP = "v=0\r\n"
            + "a=candidate:1 1 UDP 2130706431 192.168.1.20 50000 typ host\r\n"
            + "a=candidate:2 1 UDP 1694498815 203.0.113.9 61000 typ srflx raddr 192.168.1.20 rport 50000\r\n"
            + "a=candidate:3 1 UDP 16777215 198.51.100.7 3478 typ relay raddr 203.0.113.9 rport 61000\r\n"
            + "a=candidate:4 1 UDP 2130706431 2001:db8::1 50001 typ host\r\n"
            + "a=end-of-candidates\r\n";

    @Test
    void findsTheTypeOfTheCandidateAtAnAddress() {
        assertEquals("host", NetherNetChannel.candidateType(SDP, new InetSocketAddress("192.168.1.20", 50000)));
        assertEquals("srflx", NetherNetChannel.candidateType(SDP, new InetSocketAddress("203.0.113.9", 61000)));
        assertEquals("relay", NetherNetChannel.candidateType(SDP, new InetSocketAddress("198.51.100.7", 3478)));
        assertEquals("host", NetherNetChannel.candidateType(SDP, new InetSocketAddress("2001:db8:0:0:0:0:0:1", 50001)));
    }

    @Test
    void portMustMatchToo() {
        assertNull(NetherNetChannel.candidateType(SDP, new InetSocketAddress("192.168.1.20", 50001)));
    }

    @Test
    void toleratesMissingInput() {
        assertNull(NetherNetChannel.candidateType(null, new InetSocketAddress("192.168.1.20", 50000)));
        assertNull(NetherNetChannel.candidateType(SDP, null));
        assertNull(NetherNetChannel.candidateType("a=candidate:broken\r\n", new InetSocketAddress("192.168.1.20", 50000)));
    }
}
