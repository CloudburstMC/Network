package com.nukkitx.network.synapse;

import com.nukkitx.network.NetworkPacket;

public interface SynapsePacket extends NetworkPacket {

    void handle(SynapsePacketHandler handler);
}
