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

