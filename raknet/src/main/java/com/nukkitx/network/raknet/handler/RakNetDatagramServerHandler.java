package com.nukkitx.network.raknet.handler;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.raknet.CustomRakNetPacket;
import com.nukkitx.network.raknet.RakNet;
import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.RakNetUtil;
import com.nukkitx.network.raknet.packet.*;
import com.nukkitx.network.raknet.session.RakNetSession;
import com.nukkitx.network.util.DisconnectReason;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.util.Arrays;

public class RakNetDatagramServerHandler<T extends NetworkSession<RakNetSession>> extends RakNetDatagramHandler<T> {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakNetDatagramServerHandler.class);

    public RakNetDatagramServerHandler(RakNet<T> rakNet) {
        super(rakNet);
    }

    @Override
    protected void onPacket(RakNetPacket packet, T session) throws Exception {
        if (packet instanceof CustomRakNetPacket) {
            ((CustomRakNetPacket<T>) packet).handle(session);
        } else if (packet instanceof ConnectedPingPacket) {
            ConnectedPingPacket request = (ConnectedPingPacket) packet;
            ConnectedPongPacket response = new ConnectedPongPacket();
            response.setPingTime(request.getPingTime());
            response.setPongTime(System.currentTimeMillis());
            session.getConnection().sendPacket(response);
        } else if (packet instanceof ConnectionRequestPacket) {
            ConnectionRequestPacket request = (ConnectionRequestPacket) packet;
            ConnectionRequestAcceptedPacket response = new ConnectionRequestAcceptedPacket();
            response.setIncomingTimestamp(request.getTimestamp());
            response.setSystemTimestamp(System.currentTimeMillis());
            response.setSystemAddress(session.getRemoteAddress().orElse(RakNetUtil.LOOPBACK));
            InetSocketAddress[] addresses = new InetSocketAddress[20];
            Arrays.fill(addresses, RakNetUtil.JUNK_ADDRESS);
            addresses[0] = RakNetUtil.LOOPBACK;
            response.setSystemAddresses(addresses);
            response.setSystemIndex((short) 0);
            session.getConnection().sendPacket(response);
        } else if (packet instanceof NewIncomingConnectionPacket) {
            // Ignore
        } else if (packet instanceof DisconnectNotificationPacket) {
            session.getConnection().close(DisconnectReason.CLIENT_DISCONNECT);
        } else {
            log.debug("Packet not handled {} by {}", packet, session.getRemoteAddress().orElse(null));
        }
    }
}
