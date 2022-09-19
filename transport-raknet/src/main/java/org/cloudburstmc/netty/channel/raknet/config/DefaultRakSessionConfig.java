package org.cloudburstmc.netty.channel.raknet.config;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;

import java.util.Map;

/**
 * The default {@link RakChannelConfig} implementation for RakNet server child channel or client channel.
 */
public class DefaultRakSessionConfig extends DefaultChannelConfig implements RakChannelConfig {

    private volatile long guid;
    private volatile int mtu;
    private volatile int protocolVersion;
    private volatile int orderingChannels = 16;
    private volatile RakMetrics metrics;

    public DefaultRakSessionConfig(Channel channel) {
        super(channel);
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return this.getOptions(
                super.getOptions(),
                RakChannelOption.RAK_GUID, RakChannelOption.RAK_MAX_CHANNELS, RakChannelOption.RAK_MTU,
                RakChannelOption.RAK_PROTOCOL_VERSION);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(ChannelOption<T> option) {
        if (option == RakChannelOption.RAK_GUID) {
            return (T) Long.valueOf(this.getGuid());
        }
        if (option == RakChannelOption.RAK_MTU) {
            return (T) Integer.valueOf(this.getMtu());
        }
        if (option == RakChannelOption.RAK_PROTOCOL_VERSION) {
            return (T) Integer.valueOf(this.getProtocolVersion());
        }
        if (option == RakChannelOption.RAK_ORDERING_CHANNELS) {
            return (T) Integer.valueOf(this.getOrderingChannels());
        }
        if (option == RakChannelOption.RAK_METRICS) {
            return (T) this.getMetrics();
        }
        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        this.validate(option, value);

        if (option == RakChannelOption.RAK_GUID) {
            this.setGuid((Long) value);
        } else if (option == RakChannelOption.RAK_MTU) {
            this.setMtu((Integer) value);
        } else if (option == RakChannelOption.RAK_PROTOCOL_VERSION) {
            this.setProtocolVersion((Integer) value);
        } else if (option == RakChannelOption.RAK_ORDERING_CHANNELS) {
            this.setOrderingChannels((Integer) value);
        } else if (option == RakChannelOption.RAK_METRICS) {
            this.setMetrics((RakMetrics) value);
        } else {
            return super.setOption(option, value);
        }

        return true;
    }

    @Override
    public long getGuid() {
        return this.guid;
    }

    @Override
    public RakChannelConfig setGuid(long guid) {
        this.guid = guid;
        return this;
    }

    @Override
    public int getMtu() {
        return this.mtu;
    }

    @Override
    public RakChannelConfig setMtu(int mtu) {
        this.mtu = mtu;
        return this;
    }

    @Override
    public int getProtocolVersion() {
        return this.protocolVersion;
    }

    @Override
    public RakChannelConfig setProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion;
        return this;
    }

    @Override
    public int getOrderingChannels() {
        return this.orderingChannels;
    }

    @Override
    public RakChannelConfig setOrderingChannels(int orderingChannels) {
        this.orderingChannels = orderingChannels;
        return this;
    }

    @Override
    public RakMetrics getMetrics() {
        return this.metrics;
    }

    @Override
    public RakChannelConfig setMetrics(RakMetrics metrics) {
        this.metrics = metrics;
        return this;
    }
}
