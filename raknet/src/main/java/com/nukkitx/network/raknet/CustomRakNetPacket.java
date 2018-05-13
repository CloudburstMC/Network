package com.nukkitx.network.raknet;

import com.nukkitx.network.NetworkSession;

public interface CustomRakNetPacket<T extends NetworkSession> extends RakNetPacket {

    void handle(T session);
}
