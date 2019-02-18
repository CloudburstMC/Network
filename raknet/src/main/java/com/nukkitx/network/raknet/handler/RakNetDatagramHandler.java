package com.nukkitx.network.raknet.handler;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.raknet.RakNet;
import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.datagram.EncapsulatedRakNetPacket;
import com.nukkitx.network.raknet.enveloped.AddressedRakNetDatagram;
import com.nukkitx.network.raknet.enveloped.DirectAddressedRakNetPacket;
import com.nukkitx.network.raknet.packet.AckPacket;
import com.nukkitx.network.raknet.session.RakNetSession;
import com.nukkitx.network.raknet.util.IntRange;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.AccessLevel;
import lombok.Cleanup;
import lombok.Getter;

import java.util.Optional;

@Getter(AccessLevel.PROTECTED)
public abstract class RakNetDatagramHandler<T extends NetworkSession<RakNetSession>> extends ChannelInboundHandlerAdapter {
    private final RakNet<T> rakNet;

    public RakNetDatagramHandler(RakNet<T> rakNet) {
        this.rakNet = rakNet;
    }

    @Override
    public final void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof AddressedRakNetDatagram) {
            @Cleanup("release") AddressedRakNetDatagram datagram = (AddressedRakNetDatagram) msg;
            T session = rakNet.getSessionFromPacket(datagram);

            if (session == null) {
                return;
            }

            // Make sure connection has not been closed
            if (session.getConnection().isClosed()) {
                return;
            }

            RakNetSession rakNetSession = session.getConnection();

            // Acknowledge receipt of the datagram.
            AckPacket ackPacket = new AckPacket();
            ackPacket.getIds().add(new IntRange(datagram.content().getDatagramSequenceNumber()));
            ctx.writeAndFlush(new DirectAddressedRakNetPacket(ackPacket, datagram.sender()), ctx.voidPromise());

            // Update session touch time.
            rakNetSession.touch();

            // Check the datagram contents.
            if (datagram.content().getFlags().isValid()) {
                for (EncapsulatedRakNetPacket packet : datagram.content().getPackets()) {
                    // Try to figure out what packet got sent.
                    if (packet.isSplit()) {
                        Optional<ByteBuf> possiblyReassembled = rakNetSession.addSplitPacket(packet);
                        if (possiblyReassembled.isPresent()) {
                            @Cleanup("release") ByteBuf reassembled = possiblyReassembled.get();
                            RakNetPacket rakNetPacket = rakNet.getPacketRegistry().tryDecode(reassembled);
                            tryHandle(session, packet, rakNetPacket);
                        }
                    } else {
                        // Try to decode the full packet.
                        RakNetPacket rakNetPacket = rakNet.getPacketRegistry().tryDecode(packet.getBuffer());
                        tryHandle(session, packet, rakNetPacket);
                    }
                }
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private void tryHandle(T session, EncapsulatedRakNetPacket original, RakNetPacket packet) throws Exception {
        RakNetSession rakNetSession = session.getConnection();
        if (original.getReliability().isOrdered()) {
            for (RakNetPacket pk : rakNetSession.onOrderedReceived(original.getOrderingIndex(), packet)) {
                onPacket(pk, session);
            }
        } else {
            onPacket(packet, session);
        }
    }

    protected abstract void onPacket(RakNetPacket packet, T session) throws Exception;
}
