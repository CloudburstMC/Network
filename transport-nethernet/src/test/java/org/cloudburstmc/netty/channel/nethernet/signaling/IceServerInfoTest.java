package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling.IceServerInfo;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IceServerInfoTest {

    @Test
    void carriesTheCredentialsIntoTheUri() {
        List<URI> uris = new IceServerInfo("relayuser", "s3cret", List.of("turn:turn.example:3478")).toUris();

        // libdatachannel reads them out of the authority
        assertEquals(List.of(URI.create("turn:relayuser:s3cret@turn.example:3478")), uris);
    }

    @Test
    void keepsTheSecretOutOfItsOwnForm() {
        String printed = new IceServerInfo("relayuser", "s3cret",
                List.of("turn:turn.example:3478", "turn:someone:hunter2@relay.example:3478")).toString();

        assertFalse(printed.contains("s3cret"));
        assertFalse(printed.contains("hunter2"));
        assertFalse(printed.contains("relayuser"));
        assertFalse(printed.contains("someone"));
        // Still worth reading
        assertTrue(printed.contains("turn.example:3478"));
        assertTrue(printed.contains("relay.example:3478"));
    }

    @Test
    void leavesAStunServerAlone() {
        // No credentials to lose, and the host is the whole point of the line
        assertEquals("IceServerInfo[urls=[stun:stun.example:3478], username=]",
                new IceServerInfo("", "", List.of("stun:stun.example:3478")).toString());
    }
}
