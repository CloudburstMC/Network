package org.cloudburstmc.netty.channel.raknet;

import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramChannel;
import org.cloudburstmc.netty.channel.ProxyChannel;
import org.cloudburstmc.netty.channel.raknet.config.DefaultRakClientConfig;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelConfig;
import org.cloudburstmc.netty.handler.codec.client.RakClientRouteHandler;
import org.cloudburstmc.netty.handler.codec.common.*;

public class RakClientChannel extends ProxyChannel<DatagramChannel> implements RakChannel {

    /**
     * Implementation of simple RakClient which is able to connect to only one server during lifetime.
     */
    private final RakChannelConfig config;
    private final ChannelPromise connectPromise;

    public RakClientChannel(DatagramChannel channel) {
        super(channel);
        this.config = new DefaultRakClientConfig(this);
        this.pipeline().addLast(RakClientRouteHandler.NAME, new RakClientRouteHandler(this));
        // Encodes to buffer and sends RakPing.
        this.pipeline().addLast(UnconnectedPingEncoder.NAME, UnconnectedPingEncoder.INSTANCE);
        // Decodes received unconnected pong to RakPong.
        this.pipeline().addLast(UnconnectedPongDecoder.NAME, UnconnectedPongDecoder.INSTANCE);

        this.connectPromise = this.newPromise();
        this.connectPromise.addListener(future -> {
            if (future.isSuccess()) {
                this.onConnectionEstablished();
            } else {
                this.close();
            }
        });
    }

    /**
     * Setup online phase handlers
     */
    private void onConnectionEstablished() {
        RakSessionCodec sessionCodec = this.pipeline().get(RakSessionCodec.class);
        this.pipeline().addLast(ConnectedPingHandler.NAME, new ConnectedPingHandler());
        this.pipeline().addLast(ConnectedPongHandler.NAME, new ConnectedPongHandler(sessionCodec));
        this.pipeline().addLast(DisconnectNotificationHandler.NAME, DisconnectNotificationHandler.INSTANCE);
        this.pipeline().fireChannelActive();
    }

    @Override
    public RakChannelConfig config() {
        return this.config;
    }

    public ChannelPromise getConnectPromise() {
        return this.connectPromise;
    }

    @Override
    public boolean isActive() {
        return super.isActive() && this.connectPromise.isSuccess();
    }

    @Override
    public ChannelPipeline rakPipeline() {
        return null;
    }
}
