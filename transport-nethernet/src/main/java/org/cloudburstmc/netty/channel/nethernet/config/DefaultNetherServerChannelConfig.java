package org.cloudburstmc.netty.channel.nethernet.config;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import org.cloudburstmc.netty.channel.nethernet.NetherNetServerChannel;

import java.util.Map;

public class DefaultNetherServerChannelConfig extends DefaultNetherChannelConfig {
    private volatile int serverRtcHandshakeTimeoutSeconds = 30;
    private volatile boolean inferPeerCandidates = true;
    private volatile NetherServerMetrics serverMetrics;

    public DefaultNetherServerChannelConfig(Channel channel) {
        super(channel);
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return this.getOptions(
                super.getOptions(), NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS,
                NetherChannelOption.NETHER_INFER_PEER_CANDIDATES, NetherChannelOption.NETHER_SERVER_METRICS
        );
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(ChannelOption<T> option) {
        if (option == NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS) {
            return (T) Integer.valueOf(this.serverRtcHandshakeTimeoutSeconds);
        } else if (option == NetherChannelOption.NETHER_INFER_PEER_CANDIDATES) {
            return (T) Boolean.valueOf(this.inferPeerCandidates);
        } else if (option == NetherChannelOption.NETHER_SERVER_METRICS) {
            return (T) this.getServerMetrics();
        }

        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        this.validate(option, value);

        if (option == NetherChannelOption.NETHER_SERVER_METRICS) {
            this.setServerMetrics((NetherServerMetrics) value);
            return true;
        } else if (option == NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS) {
            this.setServerRtcHandshakeTimeoutSeconds((Integer) value);
            return true;
        } else if (option == NetherChannelOption.NETHER_INFER_PEER_CANDIDATES) {
            this.inferPeerCandidates = (Boolean) value;
            return true;
        } else {
            return super.setOption(option, value);
        }
    }

    void setServerRtcHandshakeTimeoutSeconds(int serverRtcHandshakeTimeoutSeconds) {
        this.serverRtcHandshakeTimeoutSeconds = serverRtcHandshakeTimeoutSeconds;
    }

    public void setServerMetrics(NetherServerMetrics serverMetrics) {
        this.serverMetrics = serverMetrics;
        // The signaling refuses joins on its own, so it has to be told too.
        if (this.channel instanceof NetherNetServerChannel server) {
            server.serverMetricsChanged(serverMetrics);
        }
    }

    public NetherServerMetrics getServerMetrics() {
        return this.serverMetrics;
    }
}
