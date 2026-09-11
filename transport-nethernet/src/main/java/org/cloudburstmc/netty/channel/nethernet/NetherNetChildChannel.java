package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherChannelConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;
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
    public static final AttributeKey<Long> CONNECTION_ID =
            AttributeKey.valueOf(NetherNetChildChannel.class, "connectionId");

    public NetherNetChildChannel(Channel parent, PeerConnection peerConnection, InetSocketAddress remote,
                                 InetSocketAddress local) {
        super(parent, remote, local);
        this.peerConnection = peerConnection;
        this.config = new DefaultNetherChannelConfig(this);
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
