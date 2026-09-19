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
    private volatile NetherChannelMetrics metrics;

    public DefaultNetherChannelConfig(Channel channel) {
        super(channel);
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return this.getOptions(
                super.getOptions(),
                NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG, NetherChannelOption.NETHER_METRICS
        );
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(ChannelOption<T> option) {

        if (option == NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG) {
            return (T) this.peerConnectionConfig;
        } else if (option == NetherChannelOption.NETHER_METRICS) {
            return (T) this.getMetrics();
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
        } else if (option == NetherChannelOption.NETHER_METRICS) {
            this.setMetrics((NetherChannelMetrics) value);
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

    public void setMetrics(NetherChannelMetrics metrics) {
        this.metrics = metrics;
    }

    public NetherChannelMetrics getMetrics() {
        return this.metrics;
    }
}
