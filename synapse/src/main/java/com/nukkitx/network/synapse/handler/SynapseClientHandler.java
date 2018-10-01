package com.nukkitx.network.synapse.handler;

import com.nukkitx.network.SessionManager;
import com.nukkitx.network.synapse.SynapseConnectException;
import com.nukkitx.network.synapse.SynapsePacket;
import com.nukkitx.network.synapse.packet.StatusPacket;
import com.nukkitx.network.synapse.session.ConnectingSynapseSession;
import com.nukkitx.network.synapse.session.SynapseSession;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;

import java.net.InetSocketAddress;
import java.util.Map;

@RequiredArgsConstructor
public class SynapseClientHandler extends SynapseHandler {
    private final SessionManager<SynapseSession> sessionManager;
    private final Map<InetSocketAddress, ConnectingSynapseSession> connectingSessions;

    @Override
    protected void onPacket(ChannelHandlerContext ctx, SynapsePacket packet) {
        InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();

        SynapseSession session = sessionManager.get(localAddress);

        if (session != null) {
            session.onPacket(packet);
            return;
        }

        ConnectingSynapseSession connectingSession = connectingSessions.get(localAddress);

        if (packet instanceof StatusPacket && connectingSession != null) {

            StatusPacket status = (StatusPacket) packet;
            switch (status.getStatus()) {
                case LOGIN_SUCCESS:
                    connectingSession.getFuture().complete(session = new SynapseSession(
                            connectingSession.getChannel(),
                            connectingSession.getRemoteAddress(),
                            connectingSession.getLoginData()
                    ));
                    sessionManager.add(localAddress, session);
                    break;
                case OUTDATED_CLIENT:
                    connectingSession.getFuture().completeExceptionally(new SynapseConnectException(SynapseConnectException.State.OUTDATED_CLIENT));
                    break;
                case OUTDATED_SERVER:
                    connectingSession.getFuture().completeExceptionally(new SynapseConnectException(SynapseConnectException.State.OUTDATED_SERVER));
                    break;
                default:
                    connectingSession.getFuture().completeExceptionally(new SynapseConnectException(SynapseConnectException.State.LOGIN_FAILED));
                    break;
            }
            connectingSessions.remove(localAddress);
        }
    }
}
