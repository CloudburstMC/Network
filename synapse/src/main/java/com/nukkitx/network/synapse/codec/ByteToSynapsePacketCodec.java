package com.nukkitx.network.synapse.codec;

import com.nukkitx.network.synapse.SynapsePacket;
import com.nukkitx.network.synapse.SynapsePacketCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import lombok.Cleanup;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class ByteToSynapsePacketCodec extends ByteToMessageCodec<SynapsePacket> {
    private final SynapsePacketCodec packetCodec;

    @Override
    protected void encode(ChannelHandlerContext ctx, SynapsePacket packet, ByteBuf out) {
        @Cleanup("release") ByteBuf buf = packetCodec.tryEncode(packet);
        ctx.writeAndFlush(buf, ctx.voidPromise());
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        out.add(packetCodec.tryDecode(in));
    }
}
