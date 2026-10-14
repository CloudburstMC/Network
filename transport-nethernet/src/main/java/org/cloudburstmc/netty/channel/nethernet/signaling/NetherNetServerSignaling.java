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

package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.channel.nethernet.config.NetherServerMetrics;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import org.jspecify.annotations.Nullable;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import io.netty.channel.EventLoop;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;

public interface NetherNetServerSignaling extends NetherNetSignaling {
    /**
     * Binds the signaling medium to listen for incoming connections (Server mode).
     *
     * @param localAddress The local address to bind to.
     * @param eventLoop    The owning channel's event loop, where new connections are handed over.
     * @throws ConnectException If the signaling cannot bind
     */
    void bind(SocketAddress localAddress, EventLoop eventLoop) throws ConnectException;

    /**
     * Sets the handler a new connection is handed to, see {@link NewConnectionHandler#onConnect}.
     * The server channel installs its own on bind.
     *
     * @param handler The handler for new connections
     */
    void setNewConnectionHandler(NewConnectionHandler handler);

    /**
     * Sets what this host advertises: the LAN pong for discovery, the status document for HTTP
     * signaling, and nothing for signaling that advertises elsewhere.
     *
     * @param pongData The advertisement.
     */
    void setAdvertisementData(PongData pongData);

    /**
     * Sets where to report joins this signaling turns away. Signaling that refuses nothing of its
     * own ignores it.
     *
     * @param metrics The metrics to report to, or null to report nothing.
     */
    default void setMetrics(@Nullable NetherServerMetrics metrics) {
    }

    /**
     * Functional interface for new connection handling.
     */
    @FunctionalInterface
    interface NewConnectionHandler {
        /**
         * Called when a new connection is initiated by a remote peer, on the signaling's event
         * loop, which for HTTP signaling is the server channel's own.
         *
         * @param connectionId    The connection ID the peer chose, an opaque token echoed back to it.
         * @param remoteNetworkId The Network ID of the remote peer.
         * @param payload         The initial signaling payload from the remote peer.
         * @param clientAddress   The address the peer signaled from, seeding the child channel
         *                        before ICE settles, or null if the signaling cannot tell.
         * @param player          The peer's validated identity, or null if this signaling does
         *                        not validate one.
         */
        void onConnect(String connectionId, String remoteNetworkId, String payload,
                       @Nullable InetSocketAddress clientAddress, @Nullable PlayerInfo player);
    }

    /**
     * Returns the ICE servers (STUN/TURN) obtained from the signaling handshake.
     * Returns empty list if none available or not applicable.
     */
    default List<IceServerInfo> getIceServers() {
        return Collections.emptyList();
    }

    /**
     * Returns the identity used to sign SDP answers, or null to have each server channel generate
     * one of its own under the domain {@code self}, which is then what players see in the trust
     * prompt and which changes on every start.
     *
     * @return The server identity, or null if this signaling has none
     */
    default OperatorIdentity serverIdentity() {
        return null;
    }

    /**
     * Whether ICE may bind to the address the channel was bound to, instead of an ephemeral port.
     *
     * @return true if ICE should be pinned to the bound address
     */
    default boolean allowsIceOnLocalPort() {
        return true;
    }
}
