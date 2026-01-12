package org.cloudburstmc.netty.channel.nethernet.config;

import dev.kastle.webrtc.PortAllocatorConfig;
import io.netty.channel.ChannelOption;

public class NetherChannelOption<T> extends ChannelOption<T> {

    /**
     * The PortAllocatorConfig used for WebRTC connections.
     */
    public static final ChannelOption<PortAllocatorConfig> NETHER_PORT_ALLOCATOR_CONFIG =
            valueOf(NetherChannelOption.class, "NETHER_PORT_ALLOCATOR_CONFIG");

    /**
     * The timeout in seconds for completing the WebRTC handshake on the client before retrying.
     */
    public static final ChannelOption<Integer> NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS =
            valueOf(NetherChannelOption.class, "NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS");

    /**
     * The maximum number of handshake attempts before giving up on connecting.
     */
    public static final ChannelOption<Integer> NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS =
            valueOf(NetherChannelOption.class, "NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS");

     /**
     * The timeout in seconds for completing the WebRTC handshake on the server side before automatically closing the connection.
     */
    public static final ChannelOption<Integer> NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS =
            valueOf(NetherChannelOption.class, "NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS");

    @SuppressWarnings("deprecation")
    protected NetherChannelOption(String name) {
        super(name);
    }
}
