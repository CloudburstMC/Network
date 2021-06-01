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

    public DefaultRakServerConfig(RakServerChannel channel) {
        super(channel);
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return getOptions(
                super.getOptions(),
                RakChannelOption.RAK_GUID, RakChannelOption.RAK_MAX_CHANNELS, RakChannelOption.RAK_MAX_CONNECTIONS,
                RakChannelOption.RAK_SUPPORTED_PROTOCOLS, RakChannelOption.RAK_UNCONNECTED_MAGIC);
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
        this.supportedProtocols = Arrays.copyOf(supportedProtocols, supportedProtocols.length);
        Arrays.sort(this.supportedProtocols);
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
            throw new IllegalArgumentException("Unconnect magic must at least be 16 bytes");
        }
        this.unconnectedMagic = unconnectedMagic.copy().asReadOnly();
        return null;
    }
}
