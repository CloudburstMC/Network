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
     * Creates a NetherNetAddress from a string Network ID.
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
     * Tries to parse the Network ID as a long.
     *
     * @return the long value
     * @throws NumberFormatException if the ID is not a valid unsigned long string (e.g. Realms ID).
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
