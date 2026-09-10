package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.backend.LibWebRtcServerBackend;
import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcServerBackend;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetClientSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import io.github.sendablemetatype.webrtc.PeerConnectionFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;

import java.util.List;
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
     * Creates a NetherNet Server Channel Factory backed by a pool of
     * PeerConnectionFactory instances. Each native factory carries exactly one
     * network, worker, and signaling thread shared by every peer connection it
     * creates, so a pool multiplies the threads available to the data plane:
     * incoming connections are assigned round robin across the pool.
     *
     * @param factories The PeerConnectionFactory pool. Must not be empty. The
     *                  resulting server channel takes ownership and disposes
     *                  every factory on close.
     * @param signaling The NetherNetServerSignaling instance for signaling.
     * @return A ChannelFactory for NetherNetServerChannel.
     */
    public static ChannelFactory<NetherNetServerChannel> server(List<PeerConnectionFactory> factories, NetherNetServerSignaling signaling) {
        return new NetherNetChannelFactory<>(() -> new NetherNetServerChannel(factories, signaling));
    }

    /**
     * Creates a NetherNet Server Channel Factory whose PeerConnectionFactory
     * pool is created lazily, only after the channel's signaling endpoint
     * bound successfully. A bind that fails (a taken TCP port, a refused
     * websocket) therefore never creates native engine state; there is
     * nothing to tear down on that path.
     *
     * @param factoriesSupplier Invoked once per channel after its signaling
     *                          bind succeeded; must return a non empty pool.
     *                          The channel takes ownership and disposes every
     *                          factory on close.
     * @param signaling         The NetherNetServerSignaling instance for signaling.
     * @return A ChannelFactory for NetherNetServerChannel.
     */
    public static ChannelFactory<NetherNetServerChannel> server(Supplier<List<PeerConnectionFactory>> factoriesSupplier, NetherNetServerSignaling signaling) {
        return new NetherNetChannelFactory<>(() -> new NetherNetServerChannel(
                () -> new LibWebRtcServerBackend(factoriesSupplier.get()), signaling));
    }

    /**
     * Creates a NetherNet Server Channel Factory over an explicit WebRTC
     * backend. The resulting server channel takes ownership of the backend
     * and closes it on close.
     *
     * @param backend   The backend negotiating and carrying connections.
     * @param signaling The NetherNetServerSignaling instance for signaling.
     * @return A ChannelFactory for NetherNetServerChannel.
     */
    public static ChannelFactory<NetherNetServerChannel> server(WebRtcServerBackend backend, NetherNetServerSignaling signaling) {
        return new NetherNetChannelFactory<>(() -> new NetherNetServerChannel(backend, signaling));
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
