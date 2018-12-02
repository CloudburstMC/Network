package com.nukkitx.network.raknet.codec;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.raknet.RakNet;
import com.nukkitx.network.raknet.datagram.RakNetDatagram;
import com.nukkitx.network.raknet.datagram.RakNetDatagramFlags;
import com.nukkitx.network.raknet.enveloped.AddressedRakNetDatagram;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;

import java.util.List;

public class DatagramRakNetDatagramCodec extends MessageToMessageCodec<DatagramPacket, AddressedRakNetDatagram> {
    private final RakNet rakNet;

    public DatagramRakNetDatagramCodec(RakNet rakNet) {
        this.rakNet = rakNet;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, AddressedRakNetDatagram datagram, List<Object> list) throws Exception {
        ByteBuf buf = ctx.alloc().directBuffer();
        datagram.content().encode(buf);
        list.add(new DatagramPacket(buf, datagram.recipient(), datagram.sender()));
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket packet, List<Object> list) throws Exception {
        // Requires a session
        NetworkSession session = rakNet.getSession(packet);

        if (session == null) {
            return;
        }

        packet.content().markReaderIndex();
        RakNetDatagramFlags flags = new RakNetDatagramFlags(packet.content().readByte());
        packet.content().resetReaderIndex();

        if (flags.isValid() && !flags.isAck() && !flags.isNak()) {
            RakNetDatagram datagram = new RakNetDatagram();
            datagram.decode(packet.content());
            list.add(new AddressedRakNetDatagram(datagram, packet.recipient(), packet.sender()));
        }
    }
}
