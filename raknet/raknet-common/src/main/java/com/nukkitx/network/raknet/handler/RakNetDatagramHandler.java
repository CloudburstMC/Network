package com.nukkitx.network.raknet.handler;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.SessionManager;
import com.nukkitx.network.raknet.RakNet;
import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.RakNetPacketRegistry;
import com.nukkitx.network.raknet.datagram.EncapsulatedRakNetPacket;
import com.nukkitx.network.raknet.datagram.RakNetReliability;
import com.nukkitx.network.raknet.enveloped.AddressedRakNetDatagram;
import com.nukkitx.network.raknet.enveloped.DirectAddressedRakNetPacket;
import com.nukkitx.network.raknet.packet.AckPacket;
import com.nukkitx.network.raknet.session.RakNetSession;
import com.nukkitx.network.raknet.util.IntRange;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.Cleanup;

import java.util.Optional;

public abstract class RakNetDatagramHandler<T extends NetworkSession<RakNetSession>> extends ChannelInboundHandlerAdapter implements IRakNetPacketHandler<T> {
    private final RakNetPacketRegistry<T> packetRegistry;
    private final SessionManager<T> sessionManager;

    public RakNetDatagramHandler(RakNet<T> rakNet) {
        this.packetRegistry = rakNet.getPacketRegistry();
        this.sessionManager = rakNet.getSessionManager();
    }


    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof AddressedRakNetDatagram) {
            AddressedRakNetDatagram datagram = (AddressedRakNetDatagram) msg;

            T session = sessionManager.get(datagram.sender());

            if (session == null) {
                return;
            }

            // Make sure a RakNet session is not null
            if (session.getConnection() == null) {
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
                            RakNetPacket pk = packetRegistry.tryDecode(reassembled);
                            onPacket(pk, session);
                        }
                    } else {
                        // Try to decode the full packet.
                        RakNetPacket pk = packetRegistry.tryDecode(packet.getBuffer());
                        onPacket(pk, session);
                    }
                }
            }
        }
    }

    private void tryHandle(T session, EncapsulatedRakNetPacket original, RakNetPacket packet) throws Exception {
        RakNetSession rakNetSession = session.getConnection();
        if (original.getReliability().isOrdered()) {
            rakNetSession.queueReceivedPacket(original.getOrderingIndex(), packet);
        } else {
            onPacket(packet, session);
        }
    }
}
