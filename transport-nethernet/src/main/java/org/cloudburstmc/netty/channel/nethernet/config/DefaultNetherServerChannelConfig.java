package org.cloudburstmc.netty.channel.nethernet.config;

import org.cloudburstmc.netty.channel.nethernet.NetherNetAnswerDecorator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;

import java.util.Map;

public class DefaultNetherServerChannelConfig extends DefaultNetherChannelConfig  {
    private volatile int serverRtcHandshakeTimeoutSeconds = 30;
    private volatile NetherNetAnswerDecorator answerDecorator;

    public DefaultNetherServerChannelConfig(Channel channel) {
        super(channel);
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return this.getOptions(
                super.getOptions(), NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS,
                NetherChannelOption.NETHER_SERVER_ANSWER_DECORATOR
        );
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(ChannelOption<T> option) {
        if (option == NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS) {
            return (T) Integer.valueOf(this.serverRtcHandshakeTimeoutSeconds);
        }
        if (option == NetherChannelOption.NETHER_SERVER_ANSWER_DECORATOR) {
            return (T) this.answerDecorator;
        }

        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        this.validate(option, value);

        if (option == NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS) {
            this.setServerRtcHandshakeTimeoutSeconds((Integer) value);
            return true;
        } else if (option == NetherChannelOption.NETHER_SERVER_ANSWER_DECORATOR) {
            this.answerDecorator = (NetherNetAnswerDecorator) value;
            return true;
        } else {
            return super.setOption(option, value);
        }
    }

    void setServerRtcHandshakeTimeoutSeconds(int serverRtcHandshakeTimeoutSeconds) {
        this.serverRtcHandshakeTimeoutSeconds = serverRtcHandshakeTimeoutSeconds;
    }
}
