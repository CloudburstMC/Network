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
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;
import org.cloudburstmc.netty.channel.raknet.RakServerChannel;

import java.util.Arrays;
import java.util.Map;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.DEFAULT_UNCONNECTED_MAGIC;

/**
 * The default {@link RakServerChannelConfig} implementation for RakNet server.
 */
public class DefaultRakServerConfig extends DefaultChannelConfig implements RakServerChannelConfig {

    private volatile int maxChannels;
    private volatile long guid;
    private volatile int[] supportedProtocols;
    private volatile int maxConnections;
    private volatile ByteBuf unconnectedMagic = Unpooled.wrappedBuffer(DEFAULT_UNCONNECTED_MAGIC);
    private volatile ByteBuf advertisement;

    public DefaultRakServerConfig(RakServerChannel channel) {
        super(channel);
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return getOptions(
                super.getOptions(),
                RakChannelOption.RAK_GUID, RakChannelOption.RAK_MAX_CHANNELS, RakChannelOption.RAK_MAX_CONNECTIONS,
                RakChannelOption.RAK_SUPPORTED_PROTOCOLS, RakChannelOption.RAK_UNCONNECTED_MAGIC, RakChannelOption.RAK_ADVERTISEMENT);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(ChannelOption<T> option) {
        if (option == RakChannelOption.RAK_GUID) {
            return (T) Long.valueOf(this.getGuid());
        }
        if (option == RakChannelOption.RAK_MAX_CHANNELS) {
            return (T) Integer.valueOf(this.getMaxChannels());
        }
        if (option == RakChannelOption.RAK_MAX_CONNECTIONS) {
            return (T) Integer.valueOf(this.getMaxConnections());
        }
        if (option == RakChannelOption.RAK_SUPPORTED_PROTOCOLS) {
            return (T) this.getSupportedProtocols();
        }
        if (option == RakChannelOption.RAK_UNCONNECTED_MAGIC) {
            return (T) this.getUnconnectedMagic();
        }
        if (option == RakChannelOption.RAK_ADVERTISEMENT) {
            return (T) this.getAdvertisement();
        }
        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        this.validate(option, value);

        if (option == RakChannelOption.RAK_GUID) {
            this.setGuid((Long) value);
        } else if (option == RakChannelOption.RAK_MAX_CHANNELS) {
            this.setMaxChannels((Integer) value);
        } else if (option == RakChannelOption.RAK_MAX_CONNECTIONS) {
            this.setMaxConnections((Integer) value);
        } else if (option == RakChannelOption.RAK_SUPPORTED_PROTOCOLS) {
            this.setSupportedProtocols((int[]) value);
        } else if (option == RakChannelOption.RAK_UNCONNECTED_MAGIC) {
            this.setUnconnectedMagic((ByteBuf) value);
        } else if (option == RakChannelOption.RAK_ADVERTISEMENT) {
            this.setAdvertisement((ByteBuf) value);
        } else {
            return super.setOption(option, value);
        }

        return true;
    }

    @Override
    public int getMaxChannels() {
        return maxChannels;
    }

    @Override
    public RakServerChannelConfig setMaxChannels(int maxChannels) {
        if (maxChannels < 1 || maxChannels > 256) {
            throw new IllegalArgumentException("maxChannels can only be a value between 1 and 256");
        }
        this.maxChannels = maxChannels;
        return this;
    }

    @Override
    public long getGuid() {
        return this.guid;
    }

    @Override
    public RakServerChannelConfig setGuid(long guid) {
        this.guid = guid;
        return this;
    }

    @Override
    public int[] getSupportedProtocols() {
        return this.supportedProtocols;
    }

    @Override
    public RakServerChannelConfig setSupportedProtocols(int[] supportedProtocols) {
        if (supportedProtocols == null) {
            this.supportedProtocols = null;
        } else {
            this.supportedProtocols = Arrays.copyOf(supportedProtocols, supportedProtocols.length);
            Arrays.sort(this.supportedProtocols);
        }
        return this;
    }

    @Override
    public int getMaxConnections() {
        return this.maxConnections;
    }

    @Override
    public RakServerChannelConfig setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
        return this;
    }

    @Override
    public ByteBuf getUnconnectedMagic() {
        return this.unconnectedMagic;
    }

    @Override
    public RakServerChannelConfig setUnconnectedMagic(ByteBuf unconnectedMagic) {
        if (unconnectedMagic.readableBytes() < 16) {
            throw new IllegalArgumentException("Unconnected magic must at least be 16 bytes");
        }
        this.unconnectedMagic = unconnectedMagic.copy().asReadOnly();
        return this;
    }

    public ByteBuf getAdvertisement() {
        return this.advertisement;
    }

    @Override
    public RakServerChannelConfig setAdvertisement(ByteBuf advertisement) {
        this.advertisement = advertisement.copy().asReadOnly();
        return this;
    }
}
