package org.cloudburstmc.netty.channel.nethernet.config;

/**
 * Why a connection that got as far as a peer connection never carried traffic. A join refused at
 * signaling never reaches this point, and is reported by {@link NetherServerMetrics#joinRefused}.
 */
public enum NetherConnectionFailure {
    /** The data channels were not both open in time, which usually means ICE found no path. */
    HANDSHAKE_TIMEOUT,
    /** The peer gave up on its side and said so over signaling. */
    CONNECT_ERROR,
    /** ICE finished its checks without a usable candidate pair. */
    PEER_FAILED,
    /** The peer connection closed before the channel became active. */
    PEER_CLOSED
}
