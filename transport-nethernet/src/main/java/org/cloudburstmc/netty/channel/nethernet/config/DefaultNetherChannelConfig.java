package org.cloudburstmc.netty.channel.nethernet.config;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultNetherChannelConfig extends DefaultChannelConfig {
    private final Map<ChannelOption<?>, Object> options = new ConcurrentHashMap<>();

    public DefaultNetherChannelConfig(Channel channel) {
        super(channel);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(ChannelOption<T> option) {
        if (options.containsKey(option)) {
            return (T) options.get(option);
        }
        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        if (super.setOption(option, value)) {
            return true;
        }
        options.put(option, value);
        return true;
    }
}