package org.cloudburstmc.netty.channel.raknet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;

import java.util.Arrays;
import java.util.Map;

import static org.cloudburstmc.netty.RakNetConstants.DEFAULT_UNCONNECTED_MAGIC;

/**
 * The default {@link RakServerChannelConfig} implementation for RakNet
 */
public class DefaultRakServerChannelConfig extends DefaultChannelConfig implements RakServerChannelConfig {

    private volatile int maxChannels;
    private volatile long guid;
    private volatile int[] supportedProtocols;
    private volatile int maxConnections;
    private volatile ByteBuf unconnectedMagic = Unpooled.wrappedBuffer(DEFAULT_UNCONNECTED_MAGIC);
    private volatile ByteBuf unconnectedAdvert = Unpooled.EMPTY_BUFFER;

    public DefaultRakServerChannelConfig(RakServerChannel channel) {
        super(channel);
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return getOptions(
                super.getOptions(),
                RakChannelOption.RAK_GUID, RakChannelOption.RAK_MAX_CHANNELS, RakChannelOption.RAK_MAX_CONNECTIONS,
                RakChannelOption.RAK_SUPPORTED_PROTOCOLS);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(ChannelOption<T> option) {
        if (option == RakChannelOption.RAK_GUID) {
            return (T) Long.valueOf(getGuid());
        }
        if (option == RakChannelOption.RAK_MAX_CHANNELS) {
            return (T) Integer.valueOf(getMaxChannels());
        }
        if (option == RakChannelOption.RAK_MAX_CONNECTIONS) {
            return (T) Integer.valueOf(getMaxConnections());
        }
        if (option == RakChannelOption.RAK_SUPPORTED_PROTOCOLS) {
            return (T) getSupportedProtocols();
        }
        if (option == RakChannelOption.RAK_UNCONNECTED_MAGIC) {
            return (T) getUnconnectedMagic();
        }
        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        validate(option, value);

        if (option == RakChannelOption.RAK_GUID) {
            setGuid((Long) value);
        } else if (option == RakChannelOption.RAK_MAX_CHANNELS) {
            setMaxChannels((Integer) value);
        } else if (option == RakChannelOption.RAK_MAX_CONNECTIONS) {
            setMaxConnections((Integer) value);
        } else if (option == RakChannelOption.RAK_SUPPORTED_PROTOCOLS) {
            setSupportedProtocols((int[]) value);
        } else if (option == RakChannelOption.RAK_UNCONNECTED_MAGIC) {
            setUnconnectedMagic((ByteBuf) value);
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
        return guid;
    }

    @Override
    public RakServerChannelConfig setGuid(long guid) {
        this.guid = guid;
        return this;
    }

    @Override
    public int[] getSupportedProtocols() {
        return supportedProtocols;
    }

    @Override
    public RakServerChannelConfig setSupportedProtocols(int[] supportedProtocols) {
        this.supportedProtocols = Arrays.copyOf(supportedProtocols, supportedProtocols.length);
        Arrays.sort(this.supportedProtocols);
        return this;
    }

    @Override
    public int getMaxConnections() {
        return maxConnections;
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

    @Override
    public ByteBuf getUnconnectedAdvert() {
        return this.unconnectedAdvert;
    }

    @Override
    public RakServerChannelConfig setUnconnectedAdvert(ByteBuf unconnectedAdvert) {
        this.unconnectedAdvert = unconnectedAdvert.copy().asReadOnly();
        return null;
    }
}
