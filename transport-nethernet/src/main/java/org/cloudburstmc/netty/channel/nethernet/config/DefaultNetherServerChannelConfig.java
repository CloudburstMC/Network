/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.channel.nethernet.config;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import org.cloudburstmc.netty.channel.nethernet.NetherNetServerChannel;

import java.net.InetSocketAddress;
import java.util.Map;

public class DefaultNetherServerChannelConfig extends DefaultNetherChannelConfig {
    private volatile int serverRtcHandshakeTimeoutSeconds = 30;
    private volatile boolean inferPeerCandidates = true;
    private volatile NetherServerMetrics serverMetrics;
    private volatile InetSocketAddress iceAddress;

    public DefaultNetherServerChannelConfig(Channel channel) {
        super(channel);
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return this.getOptions(
                super.getOptions(), NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS,
                NetherChannelOption.NETHER_INFER_PEER_CANDIDATES, NetherChannelOption.NETHER_SERVER_METRICS,
                NetherChannelOption.NETHER_SERVER_ICE_ADDRESS
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
        } else if (option == NetherChannelOption.NETHER_SERVER_ICE_ADDRESS) {
            return (T) this.iceAddress;
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
        } else if (option == NetherChannelOption.NETHER_SERVER_ICE_ADDRESS) {
            this.iceAddress = (InetSocketAddress) value;
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
