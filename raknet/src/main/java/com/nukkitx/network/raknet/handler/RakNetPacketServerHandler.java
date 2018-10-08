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
public class RakNetPacketServerHandler<T extends NetworkSession<RakNetSession>> extends RakNetPacketHandler {
    private final RakNetServer<T> server;

    @Override
    protected void messageReceived(ChannelHandlerContext ctx, DirectAddressedRakNetPacket packet) throws Exception {
        T session = server.getSessionManager().get(packet.sender());

        // Sessionless packets
        if (session == null) {
            if (packet.content() instanceof UnconnectedPingPacket) {
                UnconnectedPingPacket request = (UnconnectedPingPacket) packet.content();
                UnconnectedPongPacket response = new UnconnectedPongPacket();
                response.setTimestamp(request.getTimestamp());
                response.setServerId(server.getId());
                response.setAdvertisement(server.getEventListener().onQuery(packet.sender()).getAdvertisment(server));
                ctx.writeAndFlush(new DirectAddressedRakNetPacket(response, packet.sender(), packet.recipient()), ctx.voidPromise());
            } else if (packet.content() instanceof OpenConnectionRequest1Packet) {
                OpenConnectionRequest1Packet request = (OpenConnectionRequest1Packet) packet.content();
                RakNetPacket toSend;

                switch (server.getEventListener().onConnectionRequest(packet.sender(), request.getProtocolVersion())) {
                    case INCOMPATIBLE_PROTOCOL_VERISON:
                        IncompatibleProtocolVersionPacket incompatibleProtocolVersion = new IncompatibleProtocolVersionPacket();
                        incompatibleProtocolVersion.setServerId(server.getId());
                        incompatibleProtocolVersion.setRakNetVersion(RakNetUtil.RAKNET_PROTOCOL_VERSION);
                        toSend = incompatibleProtocolVersion;
                        break;
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
                        response.setMtuSize(RakNetUtil.clamp(request.getMtu(), RakNetUtil.MINIMUM_MTU_SIZE, RakNetUtil.MAXIMUM_MTU_SIZE));
                        response.setServerSecurity(false);
                        response.setServerId(server.getId());
                        toSend = response;
                }
                ctx.writeAndFlush(new DirectAddressedRakNetPacket(toSend, packet.sender(), packet.recipient()), ctx.voidPromise());
            } else if (packet.content() instanceof OpenConnectionRequest2Packet) {
                OpenConnectionRequest2Packet request = (OpenConnectionRequest2Packet) packet.content();
                OpenConnectionReply2Packet response = new OpenConnectionReply2Packet();
                int mtu = RakNetUtil.clamp(request.getMtuSize(), RakNetUtil.MINIMUM_MTU_SIZE, RakNetUtil.MAXIMUM_MTU_SIZE);
                response.setMtuSize(mtu);
                response.setServerSecurity(false);
                response.setClientAddress(packet.sender());
                response.setServerId(server.getId());
                ctx.writeAndFlush(new DirectAddressedRakNetPacket(response, packet.sender(), packet.recipient()), ctx.voidPromise());
                server.createSession(new RakNetSession(packet.sender(), mtu, ctx.channel(), server));
            }
        } else {
            if (packet.content() instanceof AckPacket) {
                session.getConnection().onAck(((AckPacket) packet.content()).getIds());
            } else if (packet.content() instanceof NakPacket) {
                session.getConnection().onNak(((NakPacket) packet.content()).getIds());
            }
        }
    }
}
