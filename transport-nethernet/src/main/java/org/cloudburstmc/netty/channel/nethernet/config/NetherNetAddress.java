package org.cloudburstmc.netty.channel.nethernet.config;

import java.net.SocketAddress;

public class NetherNetAddress extends SocketAddress {
    private final String networkId;

    /**
     * Creates a NetherNetAddress from a numeric Network ID.
     *
     * @param networkId The numeric Network ID.
     */
    public NetherNetAddress(long networkId) {
        this.networkId = Long.toUnsignedString(networkId);
    }

    /**
     * Creates a NetherNetAddress from an opaque Network ID, preserving its value.
     *
     * @param networkId The string Network ID.
     */
    public NetherNetAddress(String networkId) {
        this.networkId = networkId;
    }

    /**
     * Gets the Network ID as a String.
     *
     * @return the Network ID
     */
    public String getNetworkId() {
        return networkId;
    }

    /**
     * Converts a numeric Network ID to its unsigned 64-bit representation.
     * Opaque IDs should be read with {@link #getNetworkId()} instead.
     *
     * @return the long value
     * @throws NumberFormatException if the ID is not an unsigned decimal 64-bit integer.
     */
    public long getNetworkIdAsLong() {
        return Long.parseUnsignedLong(networkId);
    }

    /**
     * Returns the string representation of the Network ID.
     *
     * @return the Network ID as a string
     */
    @Override
    public String toString() {
        return networkId;
    }
}
