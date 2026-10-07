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
import org.cloudburstmc.netty.util.nethernet.SdpUtil;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.SessionDescriptionType;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Applies the remote side's candidates to one peer connection.
 */
final class RemoteCandidates {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RemoteCandidates.class);
    /** Taken from the description, and again from trickling, far more than a peer gathers. */
    static final int MAX_CANDIDATES = 32;

    private final PeerConnection peer;
    private final String connectionId;
    private final boolean ipOnly;
    private final AtomicInteger trickled = new AtomicInteger();

    private RemoteCandidates(PeerConnection peer, String connectionId, boolean ipOnly) {
        this.peer = peer;
        this.connectionId = connectionId;
        this.ipOnly = ipOnly;
    }

    /**
     * For a server, which takes only IP literals, at most {@link #MAX_CANDIDATES} of them from the offer and again
     * from trickling.
     */
    static RemoteCandidates server(PeerConnection peer, String connectionId) {
        return new RemoteCandidates(peer, connectionId, true);
    }

    /**
     * For a client, which takes what the server sends as it is. A server may name a host inside its own network, such
     * as a cluster DNS name, for libdatachannel to resolve.
     */
    static RemoteCandidates client(PeerConnection peer, String connectionId) {
        return new RemoteCandidates(peer, connectionId, false);
    }

    /**
     * Applies the remote description, keeping the candidates {@link #trickle} would take.
     */
    void setDescription(String sdp, SessionDescriptionType type) {
        this.peer.setRemoteDescription(this.ipOnly ? SdpUtil.withIpCandidates(sdp, MAX_CANDIDATES) : sdp, type);
    }

    /**
     * Applies a candidate the remote side trickled.
     */
    void trickle(String candidate) {
        if (this.ipOnly && (!SdpUtil.hasIpAddress(candidate) || this.trickled.incrementAndGet() > MAX_CANDIDATES)) {
            log.debug("Ignoring remote candidate for {}: {}", this.connectionId, candidate);
            return;
        }
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