package org.cloudburstmc.netty.channel.raknet;

import io.netty.channel.*;
import org.cloudburstmc.netty.channel.raknet.config.DefaultRakSessionConfig;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelConfig;
import org.cloudburstmc.netty.handler.codec.common.*;
import org.cloudburstmc.netty.handler.codec.server.RakChildDatagramHandler;
import org.cloudburstmc.netty.handler.codec.server.RakServerOnlineInitialHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class RakChildChannel extends AbstractChannel {

    private static final ChannelMetadata metadata = new ChannelMetadata(false);

    private final RakChannelConfig config;
    private final InetSocketAddress remoteAddress;
    private volatile boolean open = true;

    public RakChildChannel(InetSocketAddress remoteAddress, RakServerChannel parent) {
        super(parent);
        this.remoteAddress = remoteAddress;
        this.config = new DefaultRakSessionConfig(this);
        this.pipeline().addLast(RakChildDatagramHandler.NAME, new RakChildDatagramHandler(this));

        // Setup session/online phase
        RakSessionCodec sessionCodec = null; // TODO: new session here
        this.pipeline().addLast(RakDatagramCodec.NAME, new RakDatagramCodec());
        this.pipeline().addLast(RakAcknowledgeHandler.NAME, new RakAcknowledgeHandler(sessionCodec));
        this.pipeline().addLast(RakSessionCodec.NAME, sessionCodec);
        this.pipeline().addLast(RakServerOnlineInitialHandler.NAME, RakServerOnlineInitialHandler.INSTANCE); // Will be removed
        this.pipeline().addLast(ConnectedPingHandler.NAME, new ConnectedPingHandler());
        this.pipeline().addLast(ConnectedPongHandler.NAME, new ConnectedPongHandler(sessionCodec));
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
