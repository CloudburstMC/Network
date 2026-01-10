package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetClientSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import dev.kastle.webrtc.PeerConnectionFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;

import java.util.function.Supplier;

public class NetherNetChannelFactory<T extends Channel> implements ChannelFactory<T> {

    private final Supplier<T> channelCreator;

    private NetherNetChannelFactory(Supplier<T> channelCreator) {
        this.channelCreator = channelCreator;
    }

    @Override
    public T newChannel() {
        return channelCreator.get();
    }

    /**
     * Creates a NetherNet Server Channel Factory.
     * 
     * @param factory The PeerConnectionFactory to use for creating peer connections. Should be reused where possible.
     * @param signaling The NetherNetServerSignaling instance for signaling.
     * @return A ChannelFactory for NetherNetServerChannel.
     */
    public static ChannelFactory<NetherNetServerChannel> server(PeerConnectionFactory factory, NetherNetServerSignaling signaling) {
        return new NetherNetChannelFactory<>(() -> new NetherNetServerChannel(factory, signaling));
    }

    /**
     * Creates a NetherNet Client Channel Factory.
     * 
     * @param factory The PeerConnectionFactory to use for creating peer connections. Should be reused where possible.
     * @param signaling The NetherNetClientSignaling instance for signaling.
     * @return A ChannelFactory for NetherNetClientChannel.
     */
    public static ChannelFactory<NetherNetClientChannel> client(PeerConnectionFactory factory, NetherNetClientSignaling signaling) {
        return new NetherNetChannelFactory<>(() -> new NetherNetClientChannel(factory, signaling));
    }
}