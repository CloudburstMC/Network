package org.cloudburstmc.netty.channel.nethernet.config;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;

import java.util.Map;

public class DefaultNetherClientChannelConfig extends DefaultNetherChannelConfig  {
    private volatile int clientHandshakeTimeoutMs = 3000;
    private volatile int maxHandshakeAttempts = 3;

    public DefaultNetherClientChannelConfig(Channel channel) {
        super(channel);
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return this.getOptions(
                super.getOptions(), 
                NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS, 
                NetherChannelOption.NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS
        );
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(ChannelOption<T> option) {
        if (option == NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS) {
            return (T) Integer.valueOf(this.clientHandshakeTimeoutMs);
        } else if (option == NetherChannelOption.NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS) {
            return (T) Integer.valueOf(this.maxHandshakeAttempts);
        }

        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        this.validate(option, value);

        if (option == NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS) {
            this.setClientHandshakeTimeoutMs((Integer) value);
            return true;
        } else if (option == NetherChannelOption.NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS) {
            this.setMaxHandshakeAttempts((Integer) value);
            return true;
        } else {
            return super.setOption(option, value);
        }
    }

    void setClientHandshakeTimeoutMs(int clientHandshakeTimeoutMs) {
        this.clientHandshakeTimeoutMs = clientHandshakeTimeoutMs;
    }

    void setMaxHandshakeAttempts(int maxHandshakeAttempts) {
        this.maxHandshakeAttempts = maxHandshakeAttempts;
    }
}
