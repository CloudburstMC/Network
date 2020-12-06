package org.cloudburstmc.netty.channel.raknet;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;

import java.util.Map;

public class DefaultRakChannelConfig extends DefaultChannelConfig implements RakChannelConfig {

    private volatile int maxChannels;
    private volatile long guid;
    private volatile int mtu;
    private volatile int protocolVersion;

    public DefaultRakChannelConfig(Channel channel) {
        super(channel);
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return getOptions(
                super.getOptions(),
                RakChannelOption.RAK_GUID, RakChannelOption.RAK_MAX_CHANNELS, RakChannelOption.RAK_MTU,
                RakChannelOption.RAK_PROTOCOL_VERSION);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(ChannelOption<T> option) {
        if (option == RakChannelOption.RAK_GUID) {
            return (T) Long.valueOf(getGuid());
        }
        if (option == RakChannelOption.RAK_MAX_CHANNELS) {
            return (T) Integer.valueOf(getMaxChannels());
        }
        if (option == RakChannelOption.RAK_MTU) {
            return (T) Integer.valueOf(getMtu());
        }
        if (option == RakChannelOption.RAK_PROTOCOL_VERSION) {
            return (T) Integer.valueOf(getProtocolVersion());
        }
        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        validate(option, value);

        if (option == RakChannelOption.RAK_GUID) {
            setGuid((Long) value);
        } else if (option == RakChannelOption.RAK_MAX_CHANNELS) {
            setMaxChannels((Integer) value);
        } else if (option == RakChannelOption.RAK_MTU) {
            setMtu((Integer) value);
        } else if (option == RakChannelOption.RAK_PROTOCOL_VERSION) {
            setProtocolVersion((Integer) value);
        } else {
            return super.setOption(option, value);
        }

        return true;
    }

    @Override
    public int getMaxChannels() {
        return maxChannels;
    }

    @Override
    public RakChannelConfig setMaxChannels(int maxChannels) {
        this.maxChannels = maxChannels;
        return this;
    }

    @Override
    public long getGuid() {
        return guid;
    }

    @Override
    public RakChannelConfig setGuid(long guid) {
        this.guid = guid;
        return this;
    }

    @Override
    public int getMtu() {
        return mtu;
    }

    @Override
    public RakChannelConfig setMtu(int mtu) {
        this.mtu = mtu;
        return this;
    }

    @Override
    public int getProtocolVersion() {
        return protocolVersion;
    }

    @Override
    public RakChannelConfig setProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion;
        return this;
    }
}
