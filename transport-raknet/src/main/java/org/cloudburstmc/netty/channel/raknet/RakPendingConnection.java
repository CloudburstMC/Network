package org.cloudburstmc.netty.channel.raknet;

public class RakPendingConnection {

    private final int protocolVersion;

    public RakPendingConnection(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public int getProtocolVersion() {
        return this.protocolVersion;
    }
}
