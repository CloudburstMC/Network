package com.nukkitx.network.raknet.handler;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.raknet.RakNetServer;
import com.nukkitx.network.raknet.enveloped.DirectAddressedRakNetPacket;
import com.nukkitx.network.raknet.packet.*;
import com.nukkitx.network.raknet.session.RakNetSession;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.log4j.Log4j2;

import static com.nukkitx.network.raknet.RakNetUtil.MAXIMUM_MTU_SIZE;

@Log4j2
public class RakNetPacketHandler extends SimpleChannelInboundHandler<DirectAddressedRakNetPacket> {
    private final RakNetServer server;

    public RakNetPacketHandler(RakNetServer server) {
        this.server = server;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DirectAddressedRakNetPacket packet) throws Exception {
        try {
            NetworkSession session = server.getSessionManager().get(packet.sender());

            // Sessionless packets
            if (session == null) {
                if (packet.content() instanceof UnconnectedPingPacket) {
                    UnconnectedPingPacket request = (UnconnectedPingPacket) packet.content();
                    UnconnectedPongPacket response = new UnconnectedPongPacket();
                    response.setPingId(request.getPingId());
                    response.setServerId(server.getId());
                    response.setAdvertisement(server.getRakNetEventListener().onQuery(packet.sender()).getAdvertisment(server));
                    ctx.writeAndFlush(new DirectAddressedRakNetPacket(response, packet.sender(), packet.recipient()), ctx.voidPromise());
                    return;
                }
                if (packet.content() instanceof OpenConnectionRequest1Packet) {
                    OpenConnectionRequest1Packet request = (OpenConnectionRequest1Packet) packet.content();

                    switch (server.getRakNetEventListener().onConnectionRequest(packet.sender(), request)) {
                        case INCOMPATIBLE_VERSION:
                            IncompatibleProtocolVersion badVersion = new IncompatibleProtocolVersion();
                            badVersion.setServerId(server.getId());
                            ctx.writeAndFlush(new DirectAddressedRakNetPacket(badVersion, packet.sender(), packet.recipient()), ctx.voidPromise());
                            return;
                        case NO_INCOMING_CONNECTIONS:
                            NoFreeIncomingConnectionsPacket serverFull = new NoFreeIncomingConnectionsPacket();
                            serverFull.setServerId(server.getId());
                            ctx.writeAndFlush(new DirectAddressedRakNetPacket(serverFull, packet.sender(), packet.recipient()), ctx.voidPromise());
                            return;
                        case BANNED:
                            ConnectionBannedPacket bannedPacket = new ConnectionBannedPacket();
                            bannedPacket.setServerId(server.getId());
                            ctx.writeAndFlush(new DirectAddressedRakNetPacket(bannedPacket, packet.sender(), packet.recipient()), ctx.voidPromise());
                            return;
                        default:
                            OpenConnectionReply1Packet response = new OpenConnectionReply1Packet();
                            response.setMtuSize((request.getMtu() > MAXIMUM_MTU_SIZE ? MAXIMUM_MTU_SIZE : request.getMtu()));
                            response.setServerSecurity(false);
                            response.setServerId(server.getId());
                            ctx.writeAndFlush(new DirectAddressedRakNetPacket(response, packet.sender(), packet.recipient()), ctx.voidPromise());
                            return;
                    }
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
        } finally {
            packet.release();
        }
    }
}
