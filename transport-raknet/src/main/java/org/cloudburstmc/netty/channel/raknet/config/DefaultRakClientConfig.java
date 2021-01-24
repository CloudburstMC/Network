package org.cloudburstmc.netty.channel.raknet.config;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;

import java.util.Map;

import static org.cloudburstmc.netty.RakNetConstants.DEFAULT_UNCONNECTED_MAGIC;

/**
 * The extended implementation of {@link RakChannelConfig} based on {@link DefaultRakSessionConfig} used by client.
 */
public class DefaultRakClientConfig extends DefaultRakSessionConfig {

    private volatile ByteBuf unconnectedMagic = Unpooled.wrappedBuffer(DEFAULT_UNCONNECTED_MAGIC);

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
        }
        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        this.validate(option, value);

        if (option == RakChannelOption.RAK_UNCONNECTED_MAGIC) {
            this.setUnconnectedMagic((ByteBuf) value);
            return true;
        }
        return super.setOption(option, value);
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
}
