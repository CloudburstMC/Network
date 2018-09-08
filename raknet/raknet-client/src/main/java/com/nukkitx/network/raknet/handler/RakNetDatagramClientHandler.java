package com.nukkitx.network.raknet.handler;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.raknet.CustomRakNetPacket;
import com.nukkitx.network.raknet.RakNet;
import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.packet.ConnectionRequestAcceptedPacket;
import com.nukkitx.network.raknet.packet.DisconnectNotificationPacket;
import com.nukkitx.network.raknet.session.RakNetSession;

public class RakNetDatagramClientHandler<T extends NetworkSession<RakNetSession>> extends RakNetDatagramHandler<T> {

    public RakNetDatagramClientHandler(RakNet<T> rakNet) {
        super(rakNet);
    }

    @Override
    public void onPacket(RakNetPacket packet, T session) throws Exception {
        if (packet instanceof CustomRakNetPacket) {
            ((CustomRakNetPacket<T>) packet).handle(session);
            return;
        }

        if (packet instanceof ConnectionRequestAcceptedPacket) {
            ConnectionRequestAcceptedPacket requestAccepted = (ConnectionRequestAcceptedPacket) packet;
            // TODO
            return;
        }

        if (packet instanceof DisconnectNotificationPacket) {
            session.disconnect();
            return;
        }

        throw new UnsupportedOperationException(packet.getClass().getSimpleName() + " was not handled");
    }
}
