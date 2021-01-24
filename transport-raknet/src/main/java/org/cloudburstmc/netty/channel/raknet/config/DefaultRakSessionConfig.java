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
}
