package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.channel.nethernet.config.NetherNetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetherNetNetworkIdTest {
    @ParameterizedTest
    @ValueSource(strings = {"00042", "peer /?#+%", ".", "..", "\u73a9\u5bb6\ud83c\udfae"})
    void xboxUriPreservesTheIdAsOnePathSegment(String id) {
        NetherNetXboxSignaling signaling = new NetherNetXboxSignaling(id, "MCToken unused");
        try {
            String prefix = "/ws/v1.0/signaling/";
            assertEquals(id, signaling.getLocalNetworkId());
            assertEquals("wss", signaling.uri.getScheme());
            assertEquals("signal.franchise.minecraft-services.net", signaling.uri.getHost());
            assertEquals(prefix + id, signaling.uri.getPath());
            assertFalse(signaling.uri.getRawPath().substring(prefix.length()).contains("/"));
            assertNull(signaling.uri.getRawQuery());
            assertNull(signaling.uri.getRawFragment());
            assertEquals(signaling.uri, signaling.uri.normalize());
        } finally {
            signaling.eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            signaling.close();
        }
    }

    @Test
    void addressPreservesOpaqueIdsAndRetainsExplicitNumericConversion() {
        String opaque = "peer /?#+%";
        NetherNetAddress address = new NetherNetAddress(opaque);
        assertEquals(opaque, address.getNetworkId());
        assertEquals(opaque, address.toString());
        assertThrows(NumberFormatException.class, address::getNetworkIdAsLong);

        NetherNetAddress numeric = new NetherNetAddress("00042");
        assertEquals("00042", numeric.getNetworkId());
        assertEquals(42, numeric.getNetworkIdAsLong());
        assertEquals("18446744073709551615", new NetherNetAddress(-1L).getNetworkId());
    }
}
