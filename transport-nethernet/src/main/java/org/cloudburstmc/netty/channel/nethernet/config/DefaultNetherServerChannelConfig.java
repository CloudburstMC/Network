package org.cloudburstmc.netty.channel.nethernet.config;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;

import java.util.Map;

public class DefaultNetherServerChannelConfig extends DefaultNetherChannelConfig {
    private volatile int serverRtcHandshakeTimeoutSeconds = 30;

    public DefaultNetherServerChannelConfig(Channel channel) {
        super(channel);
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return this.getOptions(
                super.getOptions(), NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS
        );
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(ChannelOption<T> option) {
        if (option == NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS) {
            return (T) Integer.valueOf(this.serverRtcHandshakeTimeoutSeconds);
        }

        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        this.validate(option, value);

        if (option == NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS) {
            this.setServerRtcHandshakeTimeoutSeconds((Integer) value);
            return true;
        } else {
            return super.setOption(option, value);
        }
    }

    void setServerRtcHandshakeTimeoutSeconds(int serverRtcHandshakeTimeoutSeconds) {
        this.serverRtcHandshakeTimeoutSeconds = serverRtcHandshakeTimeoutSeconds;
    }
}
