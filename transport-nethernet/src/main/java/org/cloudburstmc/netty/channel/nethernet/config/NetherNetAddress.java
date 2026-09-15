package org.cloudburstmc.netty.channel.nethernet.config;

import java.net.SocketAddress;

/**
 * The address of a NetherNet peer, which is its NetworkID.
 * <p>
 * A NetworkID is an opaque string. It is currently a 64-bit unsigned integer written in decimal,
 * but nothing may depend on that: the length, character set and range are all free to change, and
 * a Realms peer already uses a different shape.
 *
 * @see <a href="https://github.com/Mojang/bedrock-protocol-docs/blob/main/NetherNetOnboardingGuide.md#9-networkid">NetherNet onboarding guide, section 9</a>
 */
public class NetherNetAddress extends SocketAddress {
    private final String networkId;

    /**
     * @param networkId The peer's NetworkID, as the signaling reported it
     */
    public NetherNetAddress(String networkId) {
        this.networkId = networkId;
    }

    /**
     * @return The NetworkID
     */
    public String getNetworkId() {
        return networkId;
    }

    @Override
    public String toString() {
        return networkId;
    }
}
