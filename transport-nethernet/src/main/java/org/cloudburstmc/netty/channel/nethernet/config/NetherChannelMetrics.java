package org.cloudburstmc.netty.channel.nethernet.config;

import tel.schich.libdatachannel.IceState;
import tel.schich.libdatachannel.PeerState;

public interface NetherChannelMetrics {

    default void bytesIn(int count) {
    }

    default void bytesOut(int count) {
    }

    default void messagesIn(int count) {
    }

    default void messagesOut(int count) {
    }

    default void decodeFail(int count) {
    }

    default void peerStateChange(PeerState state) {
    }

    default void iceStateChange(IceState state) {
    }

    /** ICE candidate types, {@code host}, {@code srflx}, {@code prflx} or {@code relay}, either null. */
    default void pathSelected(String localType, String remoteType) {
    }

    /** At most once per attempt, so a client that retries reports one per attempt. */
    default void connectionFailed(NetherConnectionFailure reason) {
    }

    default void handshakeRetry() {
    }
}

