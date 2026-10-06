/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.channel.nethernet.config;

import tel.schich.libdatachannel.IceState;
import tel.schich.libdatachannel.PeerState;

/**
 * Per connection counters and events. Called from the transport's threads, not one of your own: any
 * of them may fire on the native callback thread or on the channel's event loop, so an
 * implementation is thread safe or hands off.
 */
public interface NetherChannelMetrics {

    default void bytesIn(int count) {
    }

    default void bytesOut(int count) {
    }

    /** Whole messages, however many frames each took. */
    default void messagesIn(int count) {
    }

    /** Whole messages, however many frames each took. */
    default void messagesOut(int count) {
    }

    /** Frames carrying part of a split message other than the last, which counts in {@link #messagesIn}. */
    default void fragmentsIn(int count) {
    }

    /** Frames carrying part of a split message other than the last, which counts in {@link #messagesOut}. */
    default void fragmentsOut(int count) {
    }

    /**
     * Inbound messages that cannot be delivered: one abandoned because fragments went missing or the
     * next began first, and an empty frame or message.
     */
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

