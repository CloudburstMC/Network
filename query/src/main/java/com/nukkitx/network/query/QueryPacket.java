package com.nukkitx.network.query;

import com.nukkitx.network.NetworkPacket;

public interface QueryPacket extends NetworkPacket {

    int getSessionId();

    void setSessionId(int sessionId);

    short getId();
}
