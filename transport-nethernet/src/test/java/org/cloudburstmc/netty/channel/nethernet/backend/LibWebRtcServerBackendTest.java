package org.cloudburstmc.netty.channel.nethernet.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LibWebRtcServerBackendTest {

    @Test
    void stripsIdentityAttributesOnly() {
        String offer = "v=0\r\n"
                + "o=- 1 2 IN IP4 127.0.0.1\r\n"
                + "a=fingerprint:sha-256 AA:BB\r\n"
                + "a=identity:eyJpZHAiOnsiZG9tYWluIjoiYXV0aC5taW5lY3JhZnQub3JnIn19\r\n"
                + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
                + "a=max-message-size:262144\r\n";
        String stripped = LibWebRtcServerBackend.stripIdentityAttributes(offer);
        assertEquals("v=0\r\n"
                + "o=- 1 2 IN IP4 127.0.0.1\r\n"
                + "a=fingerprint:sha-256 AA:BB\r\n"
                + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
                + "a=max-message-size:262144\r\n", stripped);
        // No identity present: unchanged.
        assertEquals(stripped, LibWebRtcServerBackend.stripIdentityAttributes(stripped));
    }
}
