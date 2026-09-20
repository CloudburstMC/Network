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

import io.netty.channel.ChannelOption;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import tel.schich.libdatachannel.PeerConnectionConfiguration;

public class NetherChannelOption<T> extends ChannelOption<T> {

    /**
     * The {@link PeerConnectionConfiguration} used for the underlying peer connections.
     */
    public static final ChannelOption<PeerConnectionConfiguration> NETHER_PEER_CONNECTION_CONFIG =
            valueOf(NetherChannelOption.class, "NETHER_PEER_CONNECTION_CONFIG");

    /**
     * The timeout in seconds for completing the WebRTC handshake on the client before retrying.
     */
    public static final ChannelOption<Integer> NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS =
            valueOf(NetherChannelOption.class, "NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS");

    /**
     * The maximum number of handshake attempts before giving up on connecting.
     */
    public static final ChannelOption<Integer> NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS =
            valueOf(NetherChannelOption.class, "NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS");

    /**
     * The identity a client presents in its offer, derived for the player it connects on behalf of
     * with {@link OperatorIdentity#forPlayer}. Unset, the offer carries no assertion, which a server
     * that validates identities refuses.
     */
    public static final ChannelOption<OperatorIdentity> NETHER_CLIENT_IDENTITY =
            valueOf(NetherChannelOption.class, "NETHER_CLIENT_IDENTITY");

    /**
     * The timeout in seconds for completing the WebRTC handshake on the server side before automatically closing the connection.
     */
    public static final ChannelOption<Integer> NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS =
            valueOf(NetherChannelOption.class, "NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS");

    /**
     * Whether to check the address a peer signaled from when its offer holds nothing routable.
     * Defaults to true, and does nothing for a host that gathered a routable candidate of its own.
     *
     * @see org.cloudburstmc.netty.util.nethernet.SdpUtil#inferredPeerCandidates
     */
    public static final ChannelOption<Boolean> NETHER_INFER_PEER_CANDIDATES =
            valueOf(NetherChannelOption.class, "NETHER_INFER_PEER_CANDIDATES");

    /**
     * The {@link NetherChannelMetrics} to report per-channel events to. Unset by default, in which
     * case nothing is reported.
     */
    public static final ChannelOption<NetherChannelMetrics> NETHER_METRICS =
            valueOf(NetherChannelOption.class, "NETHER_METRICS");

    /**
     * The {@link NetherServerMetrics} to report listener wide events to, set on the server channel.
     * Unset by default, in which case nothing is reported.
     */
    public static final ChannelOption<NetherServerMetrics> NETHER_SERVER_METRICS =
            valueOf(NetherChannelOption.class, "NETHER_SERVER_METRICS");

    @SuppressWarnings("deprecation")
    protected NetherChannelOption(String name) {
        super(name);
    }
}
