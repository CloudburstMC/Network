package com.nukkitx.network.raknet.handler;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.session.RakNetSession;

public interface IRakNetPacketHandler<T extends NetworkSession<RakNetSession>> {
    void onPacket(RakNetPacket packet, T session) throws Exception;
}
