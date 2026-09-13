package org.cloudburstmc.netty.util.nethernet;

import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.cloudburstmc.netty.util.nethernet.ClientAssertionFixtures.CLIENT_KEY;
import static org.cloudburstmc.netty.util.nethernet.ClientAssertionFixtures.keyPair;
import static org.junit.jupiter.api.Assertions.*;

class ClientIdentityTest {
    @Test
    void exposesPlayerClaimsOnlyWhenTheyAreNonEmptyStrings() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("xid", "1234567890");
        claims.put("xname", "Player");
        ClientIdentity identity = new ClientIdentity(CLIENT_KEY.getPublic(), claims);
        assertEquals("1234567890", identity.getXuid());
        assertEquals("Player", identity.getDisplayName());

        claims.put("xid", 1234567890L);
        claims.put("xname", "");
        ClientIdentity untyped = new ClientIdentity(CLIENT_KEY.getPublic(), claims);
        assertNull(untyped.getXuid());
        assertNull(untyped.getDisplayName());
        assertNull(new ClientIdentity(CLIENT_KEY.getPublic(), Map.of()).getXuid());
    }

    @Test
    void bindsTheLoginChainKeyToTheTransportKey() throws Exception {
        ClientIdentity identity = new ClientIdentity(CLIENT_KEY.getPublic(), Map.of());
        KeyPair other = keyPair("EC", "secp384r1");
        assertNull(identity.loginKeyMismatch(CLIENT_KEY.getPublic()));
        assertNotNull(identity.loginKeyMismatch(other.getPublic()));
        assertNotNull(identity.loginKeyMismatch(null));

        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            assertNotNull(NetherNetChildChannel.loginKeyMismatch(channel, CLIENT_KEY.getPublic()));
            channel.attr(NetherNetChildChannel.CLIENT_IDENTITY).set(identity);
            assertNull(NetherNetChildChannel.loginKeyMismatch(channel, CLIENT_KEY.getPublic()));
            assertNotNull(NetherNetChildChannel.loginKeyMismatch(channel, other.getPublic()));
            assertNotNull(NetherNetChildChannel.loginKeyMismatch(null, CLIENT_KEY.getPublic()));
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
