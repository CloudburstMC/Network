package com.nukkitx.network.raknet.handler;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.raknet.CustomRakNetPacket;
import com.nukkitx.network.raknet.RakNet;
import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.packet.*;
import com.nukkitx.network.raknet.session.RakNetSession;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

public class RakNetDatagramServerHandler<T extends NetworkSession<RakNetSession>> extends RakNetDatagramHandler<T> {
    private static final InetSocketAddress LOOPBACK = new InetSocketAddress(InetAddress.getLoopbackAddress(), 19132);
    private static final InetSocketAddress JUNK_ADDRESS;

    static {
        try {
            JUNK_ADDRESS = new InetSocketAddress(InetAddress.getByName("255.255.255.255"), 19132);
        } catch (UnknownHostException e) {
            throw new AssertionError("Unable to create address");
        }
    }

    public RakNetDatagramServerHandler(RakNet<T> rakNet) {
        super(rakNet);
    }

    @Override
    public void onPacket(RakNetPacket packet, T session) throws Exception {
        if (packet instanceof CustomRakNetPacket) {
            ((CustomRakNetPacket<T>) packet).handle(session);
            return;
        }

        if (packet instanceof ConnectedPingPacket) {
            ConnectedPingPacket request = (ConnectedPingPacket) packet;
            ConnectedPongPacket response = new ConnectedPongPacket();
            response.setPingTime(request.getPingTime());
            response.setPongTime(System.currentTimeMillis());
            session.getConnection().sendPacket(response);
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
            session.getConnection().sendPacket(response);
            return;
        }
        if (packet instanceof DisconnectNotificationPacket) {
            session.disconnect();
            return;
        }

        throw new IllegalStateException("Packet not handled " + packet);
    }
}
