package org.cloudburstmc.netty.channel.nethernet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;

/**
 * An inbound unreliable message, including its NetherNet header. The framing
 * codec decodes it independently of any reliable message being reassembled.
 * Outbound messages still use reliable delivery and must be plain byte buffers.
 */
public final class NetherNetUnreliableFrame extends DefaultByteBufHolder {
    /**
     * @param data the raw framed message; this holder takes ownership of its reference
     */
    public NetherNetUnreliableFrame(ByteBuf data) {
        super(data);
    }

    @Override
    public NetherNetUnreliableFrame replace(ByteBuf content) {
        return new NetherNetUnreliableFrame(content);
    }
}
