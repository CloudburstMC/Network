package com.nukkitx.network.raknet.handler;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.raknet.RakNetClient;
import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.enveloped.DirectAddressedRakNetPacket;
import com.nukkitx.network.raknet.packet.*;
import com.nukkitx.network.raknet.session.RakNetConnectingSession;
import com.nukkitx.network.raknet.session.RakNetPingSession;
import com.nukkitx.network.raknet.session.RakNetSession;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class RakNetPacketClientHandler<T extends NetworkSession<RakNetSession>> extends RakNetPacketHandler {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakNetPacketClientHandler.class);
    private final RakNetClient<T> rakNetClient;

    @Override
    protected void messageReceived(ChannelHandlerContext ctx, DirectAddressedRakNetPacket packet) throws Exception {
        T session = rakNetClient.getSessionManager().get(packet.recipient());
        RakNetConnectingSession<T> connectingSession = rakNetClient.getConnectingSession(packet.recipient());
        RakNetPacket rakNetPacket = packet.content();
        if (session == null && connectingSession != null) {
            if (rakNetPacket instanceof OpenConnectionReply1Packet) {
                OpenConnectionReply1Packet reply1 = (OpenConnectionReply1Packet) rakNetPacket;
                OpenConnectionRequest2Packet request2 = new OpenConnectionRequest2Packet();
                request2.setClientId(rakNetClient.getId());
                request2.setServerAddress(packet.sender());
                request2.setMtuSize(reply1.getMtuSize());

                connectingSession.setMtu(reply1.getMtuSize());
                ctx.writeAndFlush(new DirectAddressedRakNetPacket(request2, packet.sender(), packet.recipient()), ctx.voidPromise());
            } else if (rakNetPacket instanceof OpenConnectionReply2Packet) {
                OpenConnectionReply2Packet reply2 = (OpenConnectionReply2Packet) rakNetPacket;
                connectingSession.setMtu(reply2.getMtuSize());
                ConnectionRequestPacket request = new ConnectionRequestPacket();
                request.setClientId(rakNetClient.getId());
                request.setServerSecurity(false);
                request.setTimestamp(rakNetClient.getTimestamp());
                connectingSession.setRemoteId(reply2.getServerId());
                // Create session
                connectingSession.createSession(RakNetConnectingSession.ConnectionState.CONNECTED).sendPacket(request);
            } else if (rakNetPacket instanceof NoFreeIncomingConnectionsPacket) {
                connectingSession.completeExceptionally(RakNetConnectingSession.ConnectionState.NO_FREE_INCOMING_CONNECTIONS);
            } else if (rakNetPacket instanceof ConnectionBannedPacket) {
                connectingSession.completeExceptionally(RakNetConnectingSession.ConnectionState.BANNED);
            } else {
                log.debug("Unhandled packet {}", packet.content());
            }
        } else {
            RakNetPingSession pingSession = rakNetClient.getPingSession(packet.recipient());
            if (packet.content() instanceof UnconnectedPongPacket && pingSession != null) {
                pingSession.onPong((UnconnectedPongPacket) packet.content());
            } else if (packet.content() instanceof AckPacket && session != null) {
                session.getConnection().onAck(((AckPacket) packet.content()).getIds());
            } else if (packet.content() instanceof NakPacket && session != null) {
                session.getConnection().onNak(((NakPacket) packet.content()).getIds());
            } else {
                log.debug("Unhandled packet {}", packet.content());
            }
        }
    }
}
