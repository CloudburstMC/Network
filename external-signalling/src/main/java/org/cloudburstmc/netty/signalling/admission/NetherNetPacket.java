package org.cloudburstmc.netty.signalling.admission;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;

/**
 * Explicit outbound channel selection. Plain ByteBuf writes use the reliable channel.
 */
public final class NetherNetPacket extends DefaultByteBufHolder {
    private final boolean reliable;

    public NetherNetPacket(ByteBuf content, boolean reliable) {
        super(content);
        this.reliable = reliable;
    }

    public boolean reliable() {
        return reliable;
    }

    @Override
    public NetherNetPacket replace(ByteBuf content) {
        return new NetherNetPacket(content, reliable);
    }

    /**
     * Fired immediately before the corresponding inbound ByteBuf, on the same event loop.
     */
    public record Delivery(boolean reliable) {
    }
}
