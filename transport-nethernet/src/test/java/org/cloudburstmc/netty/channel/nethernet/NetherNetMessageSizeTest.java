package org.cloudburstmc.netty.channel.nethernet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetherNetMessageSizeTest {
    private static final String MEDIA = "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";

    @ParameterizedTest
    @CsvSource({"0, 262144", "2, 2", "10000, 10000", "65536, 65536", "262144, 262144",
            "4194304, 262144", "2147483648, 262144", "999999999999999999999999999999, 262144"})
    void respectsPeerLimitsWithoutOverflow(String advertised, int expected) {
        assertEquals(expected, NetherNetConstants.parseMaxMessageSize(MEDIA + "a=max-message-size:" + advertised));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "-1", "+65536", "garbage", "65536.0", "999999999999999999x"})
    void rejectsExplicitUnusableLimits(String advertised) {
        assertThrows(IllegalArgumentException.class,
                () -> NetherNetConstants.parseMaxMessageSize(MEDIA + "a=max-message-size:" + advertised));
    }

    @Test
    void defaultsOnlyWhenTheAttributeIsAbsent() {
        assertEquals(65536, NetherNetConstants.parseMaxMessageSize(null));
        assertEquals(65536, NetherNetConstants.parseMaxMessageSize(MEDIA));
        assertEquals(10000, NetherNetConstants.parseMaxMessageSize(MEDIA, 10000));
        assertThrows(IllegalArgumentException.class,
                () -> NetherNetConstants.parseMaxMessageSize(MEDIA + "a=max-message-size"));
    }

    @Test
    void ignoresSessionAudioAndRejectedMediaAttributes() {
        String sdp = "v=0\r\na=max-message-size:garbage\r\n"
                + "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=max-message-size:8\r\n"
                + MEDIA + "a=max-message-size:262144\r\n"
                + "m=application 0 UDP/DTLS/SCTP webrtc-datachannel\r\na=max-message-size:1\r\n";
        assertEquals(262144, NetherNetConstants.parseMaxMessageSize(sdp));
    }

    @Test
    void respectsAllActiveSctpLimitsIncludingMissingAttributes() {
        assertEquals(65536, NetherNetConstants.parseMaxMessageSize(
                MEDIA + "a=max-message-size:262144\r\n" + MEDIA));
        assertEquals(10000, NetherNetConstants.parseMaxMessageSize(
                MEDIA + "a=max-message-size:0\r\na=max-message-size:10000\r\n"));
        assertEquals(10000, NetherNetConstants.parseMaxMessageSize(
                "m=application 9 DTLS/SCTP 5000\na=max-message-size:10000\n"));
    }
}
