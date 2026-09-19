package org.cloudburstmc.netty.channel.nethernet.config;

import java.net.InetSocketAddress;

/**
 * Events that belong to the listener rather than to one connection, which is the only place a join
 * that never became a channel can be seen. Anything that happens once a connection exists belongs
 * on its channel instead.
 *
 * @see NetherChannelMetrics for the per connection counterpart
 */
public interface NetherServerMetrics {

    default void connectionAccepted(String networkId, boolean verifiedIdentity) {
    }

    /** The status names the reason, and is what the peer was refused with. */
    default void joinRefused(int httpStatus) {
    }

    /** The address already holds as many signaling connections as it may. */
    default void addressRefused(InetSocketAddress address) {
    }

    /** A request arrived without TLS where it is required. */
    default void plaintextRefused(InetSocketAddress address) {
    }
}
