package com.nukkitx.network.raknet.handler;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.SessionConnection;
import com.nukkitx.network.raknet.CustomRakNetPacket;
import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.RakNetServer;
import com.nukkitx.network.raknet.datagram.EncapsulatedRakNetPacket;
import com.nukkitx.network.raknet.enveloped.AddressedRakNetDatagram;
import com.nukkitx.network.raknet.enveloped.DirectAddressedRakNetPacket;
import com.nukkitx.network.raknet.packet.*;
import com.nukkitx.network.raknet.session.RakNetSession;
import com.nukkitx.network.raknet.util.IntRange;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.Cleanup;
import lombok.extern.log4j.Log4j2;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;

@Log4j2
public class RakNetDatagramHandler<T extends NetworkSession<RakNetPacket>> extends SimpleChannelInboundHandler<AddressedRakNetDatagram> {
    private static final InetSocketAddress LOOPBACK = new InetSocketAddress(InetAddress.getLoopbackAddress(), 19132);
    private static final InetSocketAddress JUNK_ADDRESS;

    static {
        try {
            JUNK_ADDRESS = new InetSocketAddress(InetAddress.getByName("255.255.255.255"), 19132);
        } catch (UnknownHostException e) {
            throw new AssertionError("Unable to create address");
        }
    }

    private final RakNetServer<T> server;

    public RakNetDatagramHandler(RakNetServer<T> server) {
        this.server = server;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, AddressedRakNetDatagram datagram) throws Exception {
        T session = server.getSessionManager().get(datagram.sender());

        if (session == null) {
            return;
        }

        // Make sure a RakNet session is backing this packet.
        if (!(session.getConnection() instanceof RakNetSession)) {
            return;
        }

        RakNetSession rakNetSession = (RakNetSession) session.getConnection();

        // Acknowledge receipt of the datagram.
        AckPacket ackPacket = new AckPacket();
        ackPacket.getIds().add(new IntRange(datagram.content().getDatagramSequenceNumber()));
        ctx.writeAndFlush(new DirectAddressedRakNetPacket(ackPacket, datagram.sender()), ctx.voidPromise());

        // Update session touch time.
        session.touch();

        // Check the datagram contents.
        if (datagram.content().getFlags().isValid()) {
            for (EncapsulatedRakNetPacket packet : datagram.content().getPackets()) {
                // Try to figure out what packet got sent.
                if (packet.isSplit()) {
                    Optional<ByteBuf> possiblyReassembled = rakNetSession.addSplitPacket(packet);
                    if (possiblyReassembled.isPresent()) {
                        @Cleanup("release") ByteBuf reassembled = possiblyReassembled.get();
                        RakNetPacket pk = server.getPacketRegistry().tryDecode(reassembled);
                        handlePackage(pk, session);
                    }
                } else {
                    // Try to decode the full packet.
                    RakNetPacket pk = server.getPacketRegistry().tryDecode(packet.getBuffer());
                    handlePackage(pk, session);
                }
            }
        }
    }

    private void handlePackage(RakNetPacket packet, T session) throws Exception {
        if (packet == null) {
            return;
        }

        if (packet instanceof CustomRakNetPacket) {
            ((CustomRakNetPacket<T>) packet).handle(session);
            return;
        }

        if (packet instanceof ConnectedPingPacket) {
            ConnectedPingPacket request = (ConnectedPingPacket) packet;
            ConnectedPongPacket response = new ConnectedPongPacket();
            response.setPingTime(request.getPingTime());
            response.setPongTime(System.currentTimeMillis());
            encodeAndSend(session.getConnection(), response);
            return;
        }
        if (packet instanceof ConnectionRequestPacket) {
            ConnectionRequestPacket request = (ConnectionRequestPacket) packet;
            ConnectionRequestAcceptedPacket response = new ConnectionRequestAcceptedPacket();
            response.setIncomingTimestamp(request.getTimestamp());
            response.setSystemTimestamp(System.currentTimeMillis());
            response.setSystemAddress(session.getRemoteAddress().orElse(LOOPBACK));
            InetSocketAddress[] addresses = new InetSocketAddress[20];
            Arrays.fill(addresses, JUNK_ADDRESS);
            addresses[0] = LOOPBACK;
            response.setSystemAddresses(addresses);
            response.setSystemIndex((short) 0);
            encodeAndSend(session.getConnection(), response);
            return;
        }
        if (packet instanceof DisconnectNotificationPacket) {
            session.disconnect();
            return;
        }

        log.debug("Packet not handled: {}", packet);
    }

    private void encodeAndSend(SessionConnection<RakNetPacket> connection, RakNetPacket packet) {
        connection.sendPacket(packet);
    }
}
