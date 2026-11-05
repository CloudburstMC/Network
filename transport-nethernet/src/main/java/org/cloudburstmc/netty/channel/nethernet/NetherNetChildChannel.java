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
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import org.jspecify.annotations.Nullable;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.PeerConnection;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class NetherNetChildChannel extends NetherNetChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetChildChannel.class);

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

    /**
     * @param signaled Where signaling met the peer, see {@link #signaledAddress()}, or null when it never did
     */
    public NetherNetChildChannel(Channel parent, PeerConnection peerConnection, @Nullable SourcedAddress signaled,
                                 InetSocketAddress local) {
        // The wildcard stands in until ICE names the peer
        super(parent, signaled == null ? SourcedAddress.unknown() : signaled, local);
        this.peerConnection = peerConnection;
        this.config = new DefaultNetherChannelConfig(this);
        registerMetrics(peerConnection);
    }

    /** The data channels the remote opened so far. Guarded by this. */
    private DataChannel acceptedReliable;
    private DataChannel acceptedUnreliable;

    /**
     * Takes a data channel the remote opened, reading it from now on and activating this channel once both are
     * in. An unknown or duplicate one is closed, and the connection carries on without it. Safe to call from a
     * libdatachannel callback, also before this channel is registered.
     *
     * @param channel The data channel the remote opened
     * @return Whether this completed the pair
     */
    public boolean acceptDataChannel(DataChannel channel) {
        String label = channel.label();
        boolean reliable = NetherNetConstants.RELIABLE_CHANNEL_LABEL.equals(label);
        boolean taken = false;
        DataChannel pairedReliable = null;
        DataChannel pairedUnreliable = null;
        synchronized (this) {
            if (reliable ? acceptedReliable == null
                    : NetherNetConstants.UNRELIABLE_CHANNEL_LABEL.equals(label) && acceptedUnreliable == null) {
                try {
                    readDataChannel(channel, reliable);
                    taken = true;
                } catch (IllegalStateException closed) {
                    // This channel closed meanwhile
                }
            }
            if (taken) {
                if (reliable) {
                    acceptedReliable = channel;
                } else {
                    acceptedUnreliable = channel;
                }
                pairedReliable = acceptedReliable;
                pairedUnreliable = acceptedUnreliable;
            }
        }
        if (!taken) {
            // Outside the monitor, as a native close can wait for callbacks that take it
            log.debug("Closing data channel {} on {}, which it cannot take", label, this);
            channel.close();
            return false;
        }
        if (pairedReliable == null || pairedUnreliable == null) {
            return false;
        }
        activate(pairedReliable, pairedUnreliable);
        return true;
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
