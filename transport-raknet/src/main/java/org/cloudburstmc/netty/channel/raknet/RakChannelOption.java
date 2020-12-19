package org.cloudburstmc.netty.channel.raknet;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelOption;

public class RakChannelOption<T> extends ChannelOption<T> {

    /**
     * Maximum amount of channels each RakNet connection can have. (1-256)
     */
    public static final ChannelOption<Integer> RAK_MAX_CHANNELS =
            valueOf(RakChannelOption.class, "RAK_MAX_CHANNELS");

    /**
     * MTU that the RakNet client will use when initially connecting.
     */
    public static final ChannelOption<Integer> RAK_MTU =
            valueOf(RakChannelOption.class, "RAK_MTU");

    /**
     * Unique ID of the RakNet peer sent.
     */
    public static final ChannelOption<Long> RAK_GUID =
            valueOf(RakChannelOption.class, "RAK_GUID");

    /**
     * Maximum allowed connections to the RakNet server. Subsequent connections will be denied.
     */
    public static final ChannelOption<Integer> RAK_MAX_CONNECTIONS =
            valueOf(RakChannelOption.class, "RAK_MAX_CONNECTIONS");

    /**
     * RakNet protocol version to send to remote peer.
     */
    public static final ChannelOption<Integer> RAK_PROTOCOL_VERSION =
            valueOf(RakChannelOption.class, "RAK_PROTOCOL_VERSION");

    /**
     * Versions supported by the RakNet server.
     */
    public static final ChannelOption<int[]> RAK_SUPPORTED_PROTOCOLS =
            valueOf(RakChannelOption.class, "RAK_SUPPORTED_PROTOCOLS");

    /**
     * Magic used to make unconnected packets unique.
     */
    public static final ChannelOption<ByteBuf> RAK_UNCONNECTED_MAGIC =
            valueOf(RakChannelOption.class, "RAK_UNCONNECTED_MAGIC");

    /**
     * Payload to advertise to unconnected users.
     */
    public static final ChannelOption<ByteBuf> RAK_ADVERT =
            valueOf(RakChannelOption.class, "RAK_ADVERT");

    @SuppressWarnings("deprecation")
    protected RakChannelOption() {
        super(null);
    }
}
