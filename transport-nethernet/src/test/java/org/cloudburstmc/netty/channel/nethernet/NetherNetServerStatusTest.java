package org.cloudburstmc.netty.channel.nethernet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the wire form of the server status document against the shapes
 * observed from vanilla 1.26.50.
 */
class NetherNetServerStatusTest {

    /** Exactly what a vanilla dedicated server answers, key order included. */
    private static final String VANILLA_PUBLIC = "{\"name\":\"Dedicated Server\",\"protocol\":2181,"
            + "\"version\":\"1.26.50\",\"level\":\"Bedrock level\",\"players\":0,\"maxPlayers\":10,\"gameType\":0}";

    /** The full document, recovered from vanilla by widening the context mask. */
    private static final String VANILLA_FULL = "{\"dataVersion\":7,\"name\":\"Dedicated Server\",\"protocol\":2181,"
            + "\"version\":\"1.26.50\",\"level\":\"Bedrock level\",\"players\":0,\"maxPlayers\":10,\"gameType\":0,"
            + "\"editor\":false,\"hardcore\":false,\"onlineAuth\":true,\"selfSignedAuth\":false,"
            + "\"nonce\":\"aa394c0f96eb55b8\",\"connection\":4}";

    private static NetherNetServerStatus.Builder vanillaPublic() {
        return NetherNetServerStatus.builder()
                .name("Dedicated Server")
                .protocol(2181)
                .version("1.26.50")
                .level("Bedrock level")
                .players(0)
                .maxPlayers(10)
                .gameType(NetherNetServerStatus.GAME_TYPE_SURVIVAL);
    }

    @Test
    void publicMembersMatchVanillaByteForByte() {
        assertEquals(VANILLA_PUBLIC, vanillaPublic().build().toJson());
    }

    @Test
    void extendedMembersMatchVanillaInRegistrationOrder() {
        String json = vanillaPublic()
                .dataVersion(7)
                .editor(false)
                .hardcore(false)
                .onlineAuth(true)
                .selfSignedAuth(false)
                .nonce("aa394c0f96eb55b8")
                .connection(NetherNetServerStatus.CONNECTION_LAN_WEBRTC_SIGNALING)
                .build()
                .toJson();
        assertEquals(VANILLA_FULL, json);
    }

    @Test
    void unsetFlagIsOmittedWhileExplicitFalseIsEmitted() {
        // Absent and false are different signals, which is why the flags are
        // boxed rather than plain booleans.
        assertFalse(vanillaPublic().build().toJson().contains("hardcore"));
        assertTrue(vanillaPublic().hardcore(false).build().toJson().contains("\"hardcore\":false"));
        assertTrue(vanillaPublic().hardcore(true).build().toJson().contains("\"hardcore\":true"));
    }

    @Test
    void everyMemberIsIndependentlyOptional() {
        // No member is privileged: setting one never drags in any other, and
        // an empty status emits an empty document.
        assertEquals("{}", NetherNetServerStatus.builder().build().toJson());
        assertEquals("{\"connection\":4}", NetherNetServerStatus.builder()
                .connection(NetherNetServerStatus.CONNECTION_LAN_WEBRTC_SIGNALING)
                .build()
                .toJson());
        assertEquals("{\"players\":0}", NetherNetServerStatus.builder().players(0).build().toJson());
    }

    @Test
    void registrationOrderHoldsForSparseDocuments() {
        // dataVersion leads the schema even though vanilla filters it out, so
        // a sparse document still emits in registration order, not set order.
        assertEquals("{\"dataVersion\":7,\"name\":\"x\",\"nonce\":\"ff\"}", NetherNetServerStatus.builder()
                .nonce("ff")
                .name("x")
                .dataVersion(7)
                .build()
                .toJson());
    }

    @Test
    void nonceIsUnpaddedLowercaseHex() {
        for (int i = 0; i < 200; i++) {
            String nonce = NetherNetServerStatus.randomNonce();
            assertTrue(nonce.matches("[0-9a-f]{1,16}"), "unexpected nonce: " + nonce);
            assertFalse(nonce.length() > 1 && nonce.charAt(0) == '0', "zero padded nonce: " + nonce);
        }
    }
}
