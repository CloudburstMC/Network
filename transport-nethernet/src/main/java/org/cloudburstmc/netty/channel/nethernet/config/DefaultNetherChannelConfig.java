package org.cloudburstmc.netty.channel.nethernet.config;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;
import tel.schich.libdatachannel.PeerConnectionConfiguration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultNetherChannelConfig extends DefaultChannelConfig {
    private final Map<ChannelOption<?>, Object> options = new ConcurrentHashMap<>();

    private volatile PeerConnectionConfiguration peerConnectionConfig = PeerConnectionConfiguration.DEFAULT
            .withMaxMessageSize(NetherNetConstants.MAX_ADVERTISED_MESSAGE_SIZE);

    public DefaultNetherChannelConfig(Channel channel) {
        super(channel);
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return this.getOptions(
                super.getOptions(),
                NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG
        );
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(ChannelOption<T> option) {

        if (option == NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG) {
            return (T) this.peerConnectionConfig;
        } else if (options.containsKey(option)) {
            return (T) options.get(option);
        }

        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        if (option == NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG) {
            this.setPeerConnectionConfig((PeerConnectionConfiguration) value);
            return true;
        } else if (super.setOption(option, value)) {
            return true;
        } else {
            options.put(option, value);
            return true;
        }
    }

    void setPeerConnectionConfig(PeerConnectionConfiguration peerConnectionConfig) {
        this.peerConnectionConfig = peerConnectionConfig;
    }
}
