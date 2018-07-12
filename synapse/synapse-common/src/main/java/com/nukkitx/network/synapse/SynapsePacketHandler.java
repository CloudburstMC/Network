package com.nukkitx.network.synapse;

import com.nukkitx.network.synapse.packet.*;

public interface SynapsePacketHandler {

    void handle(ConnectPacket packet);

    void handle(DisconnectPacket packet);

    void handle(HeartbeatPacket packet);

    void handle(MessagePacket packet);

    void handle(PlayerLoginPacket packet);

    void handle(PlayerLogoutPacket packet);

    void handle(RedirectPacket packet);

    void handle(StatusPacket packet);

    void handle(TransferPacket packet);
}
