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
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;

import java.util.Map;

public class DefaultNetherClientChannelConfig extends DefaultNetherChannelConfig {
    private volatile int clientHandshakeTimeoutMs = 3000;
    private volatile int maxHandshakeAttempts = 3;
    private volatile OperatorIdentity clientIdentity;
    private volatile TokenTrust serverTrust;

    public DefaultNetherClientChannelConfig(Channel channel) {
        super(channel);
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return this.getOptions(
                super.getOptions(),
                NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS,
                NetherChannelOption.NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS,
                NetherChannelOption.NETHER_CLIENT_IDENTITY,
                NetherChannelOption.NETHER_CLIENT_SERVER_TRUST
        );
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(ChannelOption<T> option) {
        if (option == NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS) {
            return (T) Integer.valueOf(this.clientHandshakeTimeoutMs);
        } else if (option == NetherChannelOption.NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS) {
            return (T) Integer.valueOf(this.maxHandshakeAttempts);
        } else if (option == NetherChannelOption.NETHER_CLIENT_IDENTITY) {
            return (T) this.clientIdentity;
        } else if (option == NetherChannelOption.NETHER_CLIENT_SERVER_TRUST) {
            return (T) this.serverTrust;
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
        } else if (option == NetherChannelOption.NETHER_CLIENT_IDENTITY) {
            this.clientIdentity = (OperatorIdentity) value;
            return true;
        } else if (option == NetherChannelOption.NETHER_CLIENT_SERVER_TRUST) {
            this.serverTrust = (TokenTrust) value;
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
