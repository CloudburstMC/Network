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
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import tel.schich.libdatachannel.PeerConnectionConfiguration;

import java.net.InetSocketAddress;

public class NetherChannelOption<T> extends ChannelOption<T> {

    /**
     * The {@link PeerConnectionConfiguration} used for the underlying peer connections. The ICE
     * servers the signaling hands out are added to the ones set here, and the transport's message
     * size limit applies unless the configuration names its own.
     */
    public static final ChannelOption<PeerConnectionConfiguration> NETHER_PEER_CONNECTION_CONFIG =
            valueOf(NetherChannelOption.class, "NETHER_PEER_CONNECTION_CONFIG");

    /**
     * How long, in milliseconds, one client attempt has to finish signaling, ICE and DTLS before
     * the attempt is given up. Defaults to 3000, which suits LAN discovery; a connection across
     * the internet with HTTP signaling wants the same order as a connect timeout.
     */
    public static final ChannelOption<Integer> NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS =
            valueOf(NetherChannelOption.class, "NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS");

    /**
     * How many attempts a client connect gets before it fails, the first one included. Defaults
     * to 3. Signaling that sends the offer in one piece, such as HTTP, gets one attempt whatever
     * this says: the server would refuse a repeat as a duplicate join while the first is pending.
     */
    public static final ChannelOption<Integer> NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS =
            valueOf(NetherChannelOption.class, "NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS");

    /**
     * The identity a client presents in its offer, derived for the player it connects on behalf of
     * with {@link OperatorIdentity#forPlayer}. Derive it per connection: its token expires after an
     * hour. Unset, the offer carries no assertion, which a server that validates identities
     * refuses. A server accepts an operator signed identity only with
     * {@link org.cloudburstmc.netty.util.nethernet.TokenTrust#ANY}, since no auth service issued it.
     */
    public static final ChannelOption<OperatorIdentity> NETHER_CLIENT_IDENTITY =
            valueOf(NetherChannelOption.class, "NETHER_CLIENT_IDENTITY");

    /**
     * How a client trusts the identity the server answers with. Unset, the answer's identity is
     * not checked, which suits a hop that TLS on the signaling already protects. Set it to
     * {@link TokenTrust#pinnedTo} with the server's public key to confirm the server is the one
     * holding that identity; the connect fails otherwise. The other policies do not fit an answer:
     * a dedicated server's token is issued by its own operator identity and carries no expiry, so
     * {@link TokenTrust#MINECRAFT_AUTH} cannot verify it and {@link TokenTrust#ANY} refuses it.
     */
    public static final ChannelOption<TokenTrust> NETHER_CLIENT_SERVER_TRUST =
            valueOf(NetherChannelOption.class, "NETHER_CLIENT_SERVER_TRUST");

    /**
     * The timeout in seconds for completing the WebRTC handshake on the server side before
     * automatically closing the connection.
     */
    public static final ChannelOption<Integer> NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS =
            valueOf(NetherChannelOption.class, "NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS");

    /**
     * Where ICE binds on the server, for a media port other than the signaling port. A wildcard
     * host gathers on every interface and port 0 leaves the port ephemeral. Unset, ICE uses the
     * bound address when the signaling allows it and ephemeral ports otherwise.
     */
    public static final ChannelOption<InetSocketAddress> NETHER_SERVER_ICE_ADDRESS =
            valueOf(NetherChannelOption.class, "NETHER_SERVER_ICE_ADDRESS");

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
     * case nothing is reported, and null clears it again. A child option on a server, since it is
     * the accepted connection that reports; an option on a client.
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
