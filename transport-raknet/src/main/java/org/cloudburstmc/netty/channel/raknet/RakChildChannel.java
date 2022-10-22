/*
 * Copyright 2022 CloudburstMC
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

package org.cloudburstmc.netty.channel.raknet;

import io.netty.channel.*;
import org.cloudburstmc.netty.channel.raknet.config.DefaultRakSessionConfig;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelConfig;
import org.cloudburstmc.netty.handler.codec.raknet.common.*;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakChildDatagramHandler;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakChildTailHandler;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerOnlineInitialHandler;
import org.cloudburstmc.netty.util.RakUtils;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class RakChildChannel extends AbstractChannel implements RakChannel {

    private static final ChannelMetadata metadata = new ChannelMetadata(false);

    private final RakChannelConfig config;
    private final InetSocketAddress remoteAddress;
    private final DefaultChannelPipeline rakPipeline;
    private volatile boolean open = true;

    public RakChildChannel(InetSocketAddress remoteAddress, RakServerChannel parent) {
        super(parent);
        this.remoteAddress = remoteAddress;
        this.config = new DefaultRakSessionConfig(this);
        // Create an internal pipeline for RakNet session logic to take place. We use the parent channel to ensure
        // this all occurs on the parent event loop so the connection is not slowed down by any user code.
        // (compression, encryption, etc.)
        this.rakPipeline = RakUtils.newChannelPipeline(parent);
        this.rakPipeline.addLast(RakChildDatagramHandler.NAME, new RakChildDatagramHandler(this));

        // Setup session/online phase
        RakSessionCodec sessionCodec = new RakSessionCodec(this);
        this.rakPipeline.addLast(RakDatagramCodec.NAME, new RakDatagramCodec());
        this.rakPipeline.addLast(RakAcknowledgeHandler.NAME, new RakAcknowledgeHandler(sessionCodec));
        this.rakPipeline.addLast(RakSessionCodec.NAME, sessionCodec);
        // This handler auto-removes once ConnectionRequest is received
        this.rakPipeline.addLast(ConnectedPingHandler.NAME, new ConnectedPingHandler());
        this.rakPipeline.addLast(ConnectedPongHandler.NAME, new ConnectedPongHandler(sessionCodec));
        this.rakPipeline.addLast(DisconnectNotificationHandler.NAME, DisconnectNotificationHandler.INSTANCE);
        this.rakPipeline.addLast(RakServerOnlineInitialHandler.NAME, new RakServerOnlineInitialHandler(this));
        this.rakPipeline.addLast(RakChildTailHandler.NAME, new RakChildTailHandler(this));
        this.rakPipeline.fireChannelRegistered();
        this.rakPipeline.fireChannelActive();
    }

    @Override
    public ChannelPipeline rakPipeline() {
        return rakPipeline;
    }

    @Override
    public SocketAddress localAddress0() {
        return this.parent().localAddress();
    }

    @Override
    public SocketAddress remoteAddress0() {
        return this.remoteAddress;
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) super.remoteAddress();
    }

    @Override
    public RakChannelConfig config() {
        return this.config;
    }

    @Override
    public ChannelMetadata metadata() {
        return metadata;
    }

    @Override
    protected void doBind(SocketAddress socketAddress) throws Exception {
        throw new UnsupportedOperationException("Can not bind child channel!");
    }

    @Override
    protected void doBeginRead() throws Exception {
        // Ignore
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer channelOutboundBuffer) throws Exception {
        throw new UnsupportedOperationException("Can not write on child channel! This should be forwarded to parent!");
    }

    @Override
    protected void doDisconnect() throws Exception {
        this.close();
    }

    @Override
    protected void doClose() throws Exception {
        this.open = false;
    }

    @Override
    public boolean isActive() {
        return this.isOpen() && this.parent().isActive();
    }

    @Override
    public boolean isOpen() {
        return this.open;
    }

    @Override
    protected boolean isCompatible(EventLoop eventLoop) {
        return true;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new AbstractUnsafe() {
            @Override
            public void connect(SocketAddress socketAddress, SocketAddress socketAddress1, ChannelPromise channelPromise) {
                throw new UnsupportedOperationException("Can not connect child channel!");
            }
        };
    }
}
