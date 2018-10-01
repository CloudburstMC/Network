package com.nukkitx.network.synapse.handler;

import com.nukkitx.network.synapse.SynapsePacket;
import com.nukkitx.network.synapse.SynapseServer;
import com.nukkitx.network.synapse.SynapseUtils;
import com.nukkitx.network.synapse.packet.ConnectPacket;
import com.nukkitx.network.synapse.packet.StatusPacket;
import com.nukkitx.network.synapse.session.SynapseSession;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import net.minidev.json.JSONObject;

import java.net.InetSocketAddress;
import java.util.Optional;

@RequiredArgsConstructor
public class SynapseServerHandler extends ChannelInboundHandlerAdapter {
    private final SynapseServer server;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof SynapsePacket) {
            SynapsePacket packet = (SynapsePacket) msg;
            InetSocketAddress address = (InetSocketAddress) ctx.channel().remoteAddress();

            SynapseSession session = server.getSessionManager().get(address);

            if (session != null) {
                session.onPacket(packet);
                return;
            }

            if (packet instanceof ConnectPacket) {
                // Potential connection.
                ConnectPacket connect = (ConnectPacket) packet;

                int serverProtocol = server.getPacketCodec().getProtocolVersion();
                int clientProtocol = connect.getProtocolVersion();

                if (clientProtocol != serverProtocol) {
                    StatusPacket status = new StatusPacket();
                    status.setStatus(clientProtocol > serverProtocol ? StatusPacket.Status.OUTDATED_SERVER : StatusPacket.Status.OUTDATED_CLIENT);
                    ctx.writeAndFlush(status);
                    return;
                }

                Optional<JSONObject> json = SynapseUtils.decryptConnect(server.getKey(), connect.getJwe());
                StatusPacket status = new StatusPacket();
                if (!json.isPresent()) {
                    status.setStatus(StatusPacket.Status.LOGIN_FAILURE);
                    ctx.writeAndFlush(status);
                    return;
                }

                server.getSessionManager().add(address, session = new SynapseSession(ctx.channel(), address, json.get()));

                status.setStatus(StatusPacket.Status.LOGIN_SUCCESS);
                session.sendPacket(status);
            }
        }
    }
}