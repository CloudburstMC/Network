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

package org.cloudburstmc.netty.channel.nethernet;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.SessionDescriptionType;

/**
 * Applies the remote side's candidates to one peer connection.
 */
final class RemoteCandidates {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RemoteCandidates.class);

    private final PeerConnection peer;
    private final String connectionId;

    RemoteCandidates(PeerConnection peer, String connectionId) {
        this.peer = peer;
        this.connectionId = connectionId;
    }

    /**
     * Applies the remote description.
     */
    void setDescription(String sdp, SessionDescriptionType type) {
        this.peer.setRemoteDescription(sdp, type);
    }

    /**
     * Applies a candidate the remote side trickled.
     */
    void trickle(String candidate) {
        this.add(candidate);
    }

    /**
     * Applies a candidate, logging rather than throwing once the peer is gone.
     */
    void add(String candidate) {
        log.trace("Applying remote candidate for {}: {}", this.connectionId, candidate);
        try {
            this.peer.addRemoteCandidate(candidate);
        } catch (Exception e) {
            log.debug("Failed to apply remote candidate for {} (connection likely closed): {}",
                    this.connectionId, e.toString());
        }
    }
}