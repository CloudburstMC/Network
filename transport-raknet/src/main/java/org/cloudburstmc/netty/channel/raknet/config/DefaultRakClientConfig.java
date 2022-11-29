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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;

import java.util.Map;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.DEFAULT_UNCONNECTED_MAGIC;
import static org.cloudburstmc.netty.channel.raknet.RakConstants.SESSION_TIMEOUT_MS;

/**
 * The extended implementation of {@link RakChannelConfig} based on {@link DefaultRakSessionConfig} used by client.
 */
public class DefaultRakClientConfig extends DefaultRakSessionConfig {

    private volatile ByteBuf unconnectedMagic = Unpooled.wrappedBuffer(DEFAULT_UNCONNECTED_MAGIC);
    private volatile long connectTimeout = SESSION_TIMEOUT_MS;
    private volatile long serverGuid;

    public DefaultRakClientConfig(Channel channel) {
        super(channel);
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return this.getOptions(super.getOptions(), RakChannelOption.RAK_UNCONNECTED_MAGIC);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(ChannelOption<T> option) {
        if (option == RakChannelOption.RAK_UNCONNECTED_MAGIC) {
            return (T) this.getUnconnectedMagic();
        } else if (option == RakChannelOption.RAK_CONNECT_TIMEOUT) {
            return (T) Long.valueOf(this.getConnectTimeout());
        } else if (option == RakChannelOption.RAK_REMOTE_GUID) {
            return (T) Long.valueOf(this.getServerGuid());
        }
        return this.channel.parent().config().getOption(option);
    }

    @Override
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        this.validate(option, value);

        if (option == RakChannelOption.RAK_UNCONNECTED_MAGIC) {
            this.setUnconnectedMagic((ByteBuf) value);
            return true;
        } else if (option == RakChannelOption.RAK_CONNECT_TIMEOUT) {
            this.setConnectTimeout((Long) value);
            return true;
        } else if (option == RakChannelOption.RAK_REMOTE_GUID) {
            this.setServerGuid((Long) value);
            return true;
        }
        return this.channel.parent().config().setOption(option, value);
    }

    public ByteBuf getUnconnectedMagic() {
        return this.unconnectedMagic;
    }

    public RakServerChannelConfig setUnconnectedMagic(ByteBuf unconnectedMagic) {
        if (unconnectedMagic.readableBytes() < 16) {
            throw new IllegalArgumentException("Unconnect magic must at least be 16 bytes");
        }
        this.unconnectedMagic = unconnectedMagic.copy().asReadOnly();
        return null;
    }

    public long getConnectTimeout() {
        return this.connectTimeout;
    }

    public DefaultRakClientConfig setConnectTimeout(long connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    public long getServerGuid() {
        return this.serverGuid;
    }

    public DefaultRakClientConfig setServerGuid(long serverGuid) {
        this.serverGuid = serverGuid;
        return this;
    }
}
