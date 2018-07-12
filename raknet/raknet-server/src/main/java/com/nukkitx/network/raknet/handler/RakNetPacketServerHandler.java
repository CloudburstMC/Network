package com.nukkitx.network.raknet.handler;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.RakNetServer;
import com.nukkitx.network.raknet.RakNetUtil;
import com.nukkitx.network.raknet.enveloped.DirectAddressedRakNetPacket;
import com.nukkitx.network.raknet.packet.*;
import com.nukkitx.network.raknet.session.RakNetSession;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class RakNetPacketServerHandler extends RakNetPacketHandler {
    private final RakNetServer server;

    @Override
    protected void messageReceived(ChannelHandlerContext ctx, DirectAddressedRakNetPacket packet) throws Exception {
        NetworkSession session = server.getSessionManager().get(packet.sender());

        // Sessionless packets
        if (session == null) {
            if (packet.content() instanceof UnconnectedPingPacket) {
                UnconnectedPingPacket request = (UnconnectedPingPacket) packet.content();
                UnconnectedPongPacket response = new UnconnectedPongPacket();
                response.setPingId(request.getPingId());
                response.setServerId(server.getId());
                response.setAdvertisement(server.getEventListener().onQuery(packet.sender()).getAdvertisment(server));
                ctx.writeAndFlush(new DirectAddressedRakNetPacket(response, packet.sender(), packet.recipient()), ctx.voidPromise());
                return;
            }
            if (packet.content() instanceof OpenConnectionRequest1Packet) {
                OpenConnectionRequest1Packet request = (OpenConnectionRequest1Packet) packet.content();
                if (RakNetUtil.RAKNET_PROTOCOL_VERSION != request.getProtocolVersion()) {
                    IncompatibleProtocolVersion badVersion = new IncompatibleProtocolVersion();
                    badVersion.setServerId(server.getId());
                    ctx.writeAndFlush(new DirectAddressedRakNetPacket(badVersion, packet.sender(), packet.recipient()), ctx.voidPromise());
                    return;
                }

                RakNetPacket toSend;

                switch (server.getEventListener().onConnectionRequest(packet.sender())) {
                    case NO_INCOMING_CONNECTIONS:
                        NoFreeIncomingConnectionsPacket serverFull = new NoFreeIncomingConnectionsPacket();
                        serverFull.setServerId(server.getId());
                        toSend = serverFull;
                        break;
                    case BANNED:
                        ConnectionBannedPacket banned = new ConnectionBannedPacket();
                        banned.setServerId(server.getId());
                        toSend = banned;
                        break;
                    default:
                        OpenConnectionReply1Packet response = new OpenConnectionReply1Packet();
                        response.setMtuSize((request.getMtu() > RakNetUtil.MAXIMUM_MTU_SIZE ? RakNetUtil.MAXIMUM_MTU_SIZE : request.getMtu()));
                        response.setServerSecurity(false);
                        response.setServerId(server.getId());
                        toSend = response;
                }
                ctx.writeAndFlush(new DirectAddressedRakNetPacket(toSend, packet.sender(), packet.recipient()), ctx.voidPromise());
            }
            if (packet.content() instanceof OpenConnectionRequest2Packet) {
                OpenConnectionRequest2Packet request = (OpenConnectionRequest2Packet) packet.content();
                OpenConnectionReply2Packet response = new OpenConnectionReply2Packet();
                response.setMtuSize(request.getMtuSize());
                response.setServerSecurity(false);
                response.setClientAddress(packet.sender());
                response.setServerId(server.getId());
                server.createSession(new RakNetSession(packet.sender(), request.getMtuSize(), ctx.channel(), server));
                ctx.writeAndFlush(new DirectAddressedRakNetPacket(response, packet.sender(), packet.recipient()), ctx.voidPromise());
            }
        } else {
            if (packet.content() instanceof AckPacket) {
                if (session.getConnection() instanceof RakNetSession) {
                    ((RakNetSession) session.getConnection()).onAck(((AckPacket) packet.content()).getIds());
                }
            }
            if (packet.content() instanceof NakPacket) {
                if (session.getConnection() instanceof RakNetSession) {
                    ((RakNetSession) session.getConnection()).onNak(((NakPacket) packet.content()).getIds());
                }
            }
        }
    }
}
