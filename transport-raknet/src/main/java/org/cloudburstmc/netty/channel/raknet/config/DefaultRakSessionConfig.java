/*
 * Copyright 2022 CloudburstMC
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

package org.cloudburstmc.netty.channel.raknet.config;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;
import org.cloudburstmc.netty.channel.raknet.RakConstants;

import java.util.Map;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.MAXIMUM_MTU_SIZE;
import static org.cloudburstmc.netty.channel.raknet.RakConstants.SESSION_TIMEOUT_MS;

/**
 * The default {@link RakChannelConfig} implementation for RakNet server child channel or client channel.
 */
public class DefaultRakSessionConfig extends DefaultChannelConfig implements RakChannelConfig {

    private volatile long guid;
    private volatile int mtu = MAXIMUM_MTU_SIZE;
    private volatile int protocolVersion;
    private volatile int orderingChannels = 16;
    private volatile RakMetrics metrics;
    private volatile long sessionTimeout = SESSION_TIMEOUT_MS;

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
        if (option == RakChannelOption.RAK_SESSION_TIMEOUT) {
            return (T) Long.valueOf(this.getSessionTimeout());
        }
        return this.channel.parent().config().getOption(option);
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
        } else if (option == RakChannelOption.RAK_SESSION_TIMEOUT) {
            this.setSessionTimeout((Long) value);
            return true;
        } else {
            return this.channel.parent().config().setOption(option, value);
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

    @Override
    public RakChannelConfig setSessionTimeout(long timeout) {
        this.sessionTimeout = timeout;
        return this;
    }

    @Override
    public long getSessionTimeout() {
        return this.sessionTimeout;
    }
}
