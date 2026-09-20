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

import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherChannelConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import tel.schich.libdatachannel.PeerConnection;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class NetherNetChildChannel extends NetherNetChannel {

    /**
     * The signaling connection ID this channel was accepted for.
     * <p>
     * A child arrives on the server pipeline carrying nothing that ties it back to the offer that
     * produced it, and arrival order does not follow the order answers were produced in.
     */
    public static final AttributeKey<String> CONNECTION_ID =
            AttributeKey.valueOf(NetherNetChildChannel.class, "connectionId");

    /**
     * The peer whose validated identity assertion opened this channel, when the signaling that
     * accepted it validates one. Signaling that carries no identity leaves it unset, so treat an
     * absent value as an unauthenticated transport rather than an authenticated one.
     * <p>
     * Whatever identity your protocol presents after the transport is up is not tied to this one.
     * Compare the two, through {@link PlayerInfo#clientPublicKey()}, or a peer can replay an
     * identity it captured from somewhere else.
     */
    public static final AttributeKey<PlayerInfo> PLAYER_INFO =
            AttributeKey.valueOf(NetherNetChildChannel.class, "playerInfo");

    public NetherNetChildChannel(Channel parent, PeerConnection peerConnection, InetSocketAddress remote,
                                 InetSocketAddress local) {
        super(parent, remote, local);
        this.peerConnection = peerConnection;
        this.config = new DefaultNetherChannelConfig(this);
        registerMetrics(peerConnection);
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new AbstractUnsafe() {
            @Override
            public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
                promise.setFailure(new UnsupportedOperationException("Child channel cannot connect"));
            }
        };
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        throw new UnsupportedOperationException("Child channel cannot be bound");
    }
}
