package org.cloudburstmc.netty.util.nethernet;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdpUtilTest {

    private static final String SDP = "v=0\r\n"
            + "o=- 1 2 IN IP4 127.0.0.1\r\n"
            + "a=candidate:1 1 udp 2130706431 203.0.113.10 19191 typ host\r\n"
            + "a=candidate:2 1 udp 2130706431 172.17.0.2 19191 typ host\r\n"
            + "a=candidate:3 1 udp 2130706431 10.88.0.4 19191 typ host\r\n"
            + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";

    @Test
    void readsTheAddressOutOfACandidate() {
        assertEquals("203.0.113.10",
                SdpUtil.candidateAddress("a=candidate:1 1 udp 2130706431 203.0.113.10 19191 typ host"));
    }

    @Test
    void returnsNullForATruncatedCandidate() {
        assertNull(SdpUtil.candidateAddress("a=candidate:1 1 udp"));
    }

    @Test
    void keepsOnlyTheAdvertisedCandidates() {
        String filtered = SdpUtil.withAdvertisedCandidates(SDP, Set.of("203.0.113.10"));

        assertTrue(filtered.contains("203.0.113.10"));
        assertFalse(filtered.contains("172.17.0.2"));
        assertFalse(filtered.contains("10.88.0.4"));
        // Everything that is not a candidate survives
        assertTrue(filtered.contains("m=application"));
        assertTrue(filtered.contains("o=- 1 2 IN IP4 127.0.0.1"));
    }

    @Test
    void matchesTheSameAddressWrittenTwoWays() {
        String sdp = "v=0\r\n"
                + "a=candidate:1 1 udp 2130706431 2001:db8::1 5000 typ host\r\n"
                + "a=candidate:2 1 udp 2130706431 2001:db8::2 5000 typ host\r\n";

        // The advertised form is expanded, the candidate is compressed, both are the same address
        String filtered = SdpUtil.withAdvertisedCandidates(sdp, Set.of("2001:0db8:0000:0000:0000:0000:0000:0001"));

        assertTrue(filtered.contains("2001:db8::1"));
        assertFalse(filtered.contains("2001:db8::2"));
    }

    @Test
    void leavesMdnsCandidatesToTheFilterRatherThanResolvingThem() {
        String sdp = "v=0\r\n"
                + "a=candidate:1 1 udp 2130706431 a1b2c3d4.local 5000 typ host\r\n"
                + "a=candidate:2 1 udp 2130706431 203.0.113.10 5000 typ host\r\n";

        String filtered = SdpUtil.withAdvertisedCandidates(sdp, Set.of("203.0.113.10"));

        assertTrue(filtered.contains("203.0.113.10"));
        assertFalse(filtered.contains("a1b2c3d4.local"));
    }

    @Test
    void announcesEverythingWhenNothingIsConfigured() {
        assertEquals(SDP, SdpUtil.withAdvertisedCandidates(SDP, Set.of()));
    }

    @Test
    void keepsEveryCandidateRatherThanLeavingNone() {
        // No candidate matches, and a description with no candidates can never connect
        assertEquals(SDP, SdpUtil.withAdvertisedCandidates(SDP, Set.of("198.51.100.1")));
    }

    private static final String HOST_ONLY_OFFER = "v=0\r\n"
            + "a=candidate:1 1 udp 2122194687 192.168.1.76 55473 typ host\r\n"
            + "a=candidate:2 1 udp 2122129151 100.108.10.95 55472 typ host\r\n"
            + "a=candidate:3 1 tcp 2122063615 10.7.0.2 9 typ host tcptype active\r\n"
            + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";

    @Test
    void seesAHostCandidateAPeerElsewhereCouldReach() {
        assertTrue(SdpUtil.hasRoutableHostCandidate(
                "a=candidate:1 1 udp 2122194687 203.0.113.9 19135 typ host\r\n"));
    }

    @Test
    void doesNotCountAnAddressThatOnlyWorksOnTheLocalNetwork() {
        // Private, carrier grade NAT and an IPv6 unique local address are all equally unreachable
        assertFalse(SdpUtil.hasRoutableHostCandidate(
                "a=candidate:1 1 udp 2122194687 192.168.1.76 55473 typ host\r\n"
                        + "a=candidate:2 1 udp 2122129151 10.7.0.2 55471 typ host\r\n"
                        + "a=candidate:3 1 udp 2122129151 100.108.10.95 55472 typ host\r\n"
                        + "a=candidate:4 1 udp 2122265343 fd7a:115c:a1e0::bf3a:a60 55476 typ host\r\n"));
    }

    @Test
    void doesNotCountAReflexiveCandidateAsBeingReachable() {
        // The address is public but it is a hole in a NAT, not an address this host holds
        assertFalse(SdpUtil.hasRoutableHostCandidate(
                "a=candidate:4 1 UDP 2114977023 192.168.1.2 19135 typ host\r\n"
                        + "a=candidate:13 1 UDP 1678767103 213.14.155.171 19135 typ srflx raddr 0.0.0.0 rport 0\r\n"));
    }

    @Test
    void infersACandidateForEveryPortAPeerGathered() {
        List<String> inferred = SdpUtil.inferredPeerCandidates(HOST_ONLY_OFFER,
                new InetSocketAddress("203.0.113.9", 44321));

        // One per UDP port, at the address the offer arrived from, TCP left alone
        assertEquals(2, inferred.size());
        assertTrue(inferred.get(0).contains("203.0.113.9 55473 typ srflx"));
        assertTrue(inferred.get(1).contains("203.0.113.9 55472 typ srflx"));
    }

    @Test
    void guessesAtNoMorePortsThanAPeerCouldPlausiblyHold() {
        StringBuilder sdp = new StringBuilder("v=0\r\n");
        for (int i = 0; i < 200; i++) {
            sdp.append("a=candidate:").append(i).append(" 1 udp 2122194687 192.168.1.76 ")
                    .append(50000 + i).append(" typ host\r\n");
        }

        // The peer decides how many candidates it sends, so it must not decide how many packets leave
        assertEquals(8, SdpUtil.inferredPeerCandidates(sdp.toString(),
                new InetSocketAddress("203.0.113.9", 44321)).size());
    }

    @Test
    void infersNothingForAPeerThatAlreadyHasAReflexiveCandidate() {
        String offer = HOST_ONLY_OFFER.replace("m=application",
                "a=candidate:4 1 udp 1678767103 198.51.100.4 55474 typ srflx raddr 0.0.0.0 rport 0\r\n"
                        + "m=application");

        assertTrue(SdpUtil.inferredPeerCandidates(offer, new InetSocketAddress("203.0.113.9", 44321)).isEmpty());
    }

    @Test
    void infersNothingWhenThePeerSignalledFromThisNetwork() {
        // A path already exists, and the guess would point back into our own network
        assertTrue(SdpUtil.inferredPeerCandidates(HOST_ONLY_OFFER,
                new InetSocketAddress("192.168.1.5", 44321)).isEmpty());
        assertTrue(SdpUtil.inferredPeerCandidates(HOST_ONLY_OFFER,
                new InetSocketAddress("127.0.0.1", 44321)).isEmpty());
    }

    @Test
    void infersNothingWithoutAKnownAddress() {
        assertTrue(SdpUtil.inferredPeerCandidates(HOST_ONLY_OFFER, null).isEmpty());
    }

    @Test
    void neverLeavesATrailingBlankLine() {
        String filtered = SdpUtil.withAdvertisedCandidates(SDP + "\r\n", Set.of("203.0.113.10"));

        // libwebrtc rejects a description that ends in an empty line
        assertFalse(filtered.endsWith("\r\n\r\n"));
    }
}
