package com.nukkitx.network.raknet.handler;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.raknet.CustomRakNetPacket;
import com.nukkitx.network.raknet.RakNetClient;
import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.packet.*;
import com.nukkitx.network.raknet.session.RakNetConnectingSession;
import com.nukkitx.network.raknet.session.RakNetSession;
import com.nukkitx.network.util.DisconnectReason;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.util.Arrays;

import static com.nukkitx.network.raknet.handler.RakNetDatagramServerHandler.JUNK_ADDRESS;
import static com.nukkitx.network.raknet.handler.RakNetDatagramServerHandler.LOOPBACK;

public class RakNetDatagramClientHandler<T extends NetworkSession<RakNetSession>> extends RakNetDatagramHandler<T> {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakNetDatagramClientHandler.class);
    private final RakNetClient<T> rakNetClient;

    public RakNetDatagramClientHandler(RakNetClient<T> rakNet) {
        super(rakNet);
        this.rakNetClient = rakNet;
    }

    @Override
    protected void onPacket(RakNetPacket packet, T session) throws Exception {
        if (packet instanceof CustomRakNetPacket) {
            ((CustomRakNetPacket<T>) packet).handle(session);
        } else if (packet instanceof ConnectedPongPacket) {
            ConnectedPongPacket request = (ConnectedPongPacket) packet;
            // TODO: Calculate latency
        } else if (packet instanceof ConnectedPingPacket) {
            ConnectedPongPacket pong = new ConnectedPongPacket();
            pong.setPingTime(((ConnectedPingPacket) packet).getPingTime());
            pong.setPongTime(rakNetClient.getTimestamp());
            session.getConnection().sendPacket(pong);
        } else if (packet instanceof ConnectionRequestAcceptedPacket) {
            ConnectionRequestAcceptedPacket connectionRequestAccepted = (ConnectionRequestAcceptedPacket) packet;

            RakNetConnectingSession<T> connectingSession = rakNetClient.getConnectingSession(session.getConnection().getLocalAddress());
            if (connectingSession == null) {
                return;
            }

            NewIncomingConnectionPacket newIncomingConnection = new NewIncomingConnectionPacket();
            newIncomingConnection.setClientAddress(connectingSession.getLocalAddress());
            newIncomingConnection.setClientTimestamp(rakNetClient.getTimestamp());
            newIncomingConnection.setServerTimestamp(connectionRequestAccepted.getSystemTimestamp());
            InetSocketAddress[] systemAddresses = new InetSocketAddress[20];
            Arrays.fill(systemAddresses, JUNK_ADDRESS);
            systemAddresses[0] = LOOPBACK;
            newIncomingConnection.setSystemAddresses(systemAddresses);
            session.getConnection().sendPacket(newIncomingConnection);
            connectingSession.complete();
        } else if (packet instanceof DisconnectNotificationPacket) {
            session.getConnection().close(DisconnectReason.SERVER_DISCONNECT);
            getRakNet().getSessionManager().remove(session);
        } else {
            log.debug("Packet not handled for {}: {}", session, packet);
        }
    }
}
