package org.cloudburstmc.netty.util.nethernet;

import org.junit.jupiter.api.Test;

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
    void announcesEverythingWhenNothingIsConfigured() {
        assertEquals(SDP, SdpUtil.withAdvertisedCandidates(SDP, Set.of()));
    }

    @Test
    void keepsEveryCandidateRatherThanLeavingNone() {
        // No candidate matches, and a description with no candidates can never connect
        assertEquals(SDP, SdpUtil.withAdvertisedCandidates(SDP, Set.of("198.51.100.1")));
    }

    @Test
    void neverLeavesATrailingBlankLine() {
        String filtered = SdpUtil.withAdvertisedCandidates(SDP + "\r\n", Set.of("203.0.113.10"));

        // libwebrtc rejects a description that ends in an empty line
        assertFalse(filtered.endsWith("\r\n\r\n"));
    }
}
