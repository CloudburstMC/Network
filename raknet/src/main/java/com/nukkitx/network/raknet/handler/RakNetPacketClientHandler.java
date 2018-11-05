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
import lombok.RequiredArgsConstructor;

import java.net.ConnectException;

@RequiredArgsConstructor
public class RakNetPacketClientHandler<T extends NetworkSession<RakNetSession>> extends RakNetPacketHandler {
    private final RakNetClient<T> rakNetClient;

    @Override
    protected void messageReceived(ChannelHandlerContext ctx, DirectAddressedRakNetPacket packet) throws Exception {
        T session = rakNetClient.getSessionManager().get(packet.recipient());
        RakNetConnectingSession<T> connectingSession = rakNetClient.getConnectingSession(packet.recipient());
        RakNetPacket rakNetPacket = packet.content();
        if (session == null) {
            if (connectingSession != null) {
                if (rakNetPacket instanceof OpenConnectionReply1Packet) {
                    OpenConnectionReply1Packet reply1 = (OpenConnectionReply1Packet) rakNetPacket;
                    OpenConnectionRequest2Packet request2 = new OpenConnectionRequest2Packet();
                    request2.setClientId(0); //TODO
                    request2.setServerAddress(connectingSession.getRemoteAddress());
                    request2.setMtuSize((short) reply1.getMtuSize());
                    connectingSession.setMtu((short) reply1.getMtuSize());
                    ctx.writeAndFlush(new DirectAddressedRakNetPacket(request2, packet.sender(), packet.recipient()), ctx.voidPromise());
                    return;
                } else if (rakNetPacket instanceof OpenConnectionReply2Packet) {
                    OpenConnectionReply2Packet reply2 = (OpenConnectionReply2Packet) rakNetPacket;
                    ConnectionRequestPacket request = new ConnectionRequestPacket();
                    request.setClientId(0);// TODO
                    request.setServerSecurity(reply2.isServerSecurity());
                    request.setTimestamp(System.currentTimeMillis());
                    ctx.writeAndFlush(new DirectAddressedRakNetPacket(request, packet.sender(), packet.recipient()), ctx.voidPromise());
                    // Create session
                    connectingSession.setState(RakNetConnectingSession.ConnectionState.CONNECTED);
                } else if (rakNetPacket instanceof NoFreeIncomingConnectionsPacket) {
                    connectingSession.setConnectionState(RakNetConnectingSession.ConnectionState.NO_FREE_INCOMING_CONNECTIONS);
                    connectingSession.getConnectedFuture().completeExceptionally(new ConnectException("No available connections found. Please try again later"));
                } else if (rakNetPacket instanceof ConnectionBannedPacket) {
                    connectingSession.setConnectionState(RakNetConnectingSession.ConnectionState.BANNED);
                    connectingSession.getConnectedFuture().completeExceptionally(new ConnectException("You have been banned from the server"));
                }
                connectingSession.close();
            }
        } else {
            RakNetPingSession pingSession = rakNetClient.getPingSession(packet.recipient());
            if (packet.content() instanceof UnconnectedPongPacket && pingSession != null) {
                pingSession.onPong((UnconnectedPongPacket) packet.content());
            }
            if (packet.content() instanceof AckPacket) {
                session.getConnection().onAck(((AckPacket) packet.content()).getIds());
            }
            if (packet.content() instanceof NakPacket) {
                session.getConnection().onNak(((NakPacket) packet.content()).getIds());
            }
        }
    }
}
