package org.cloudburstmc.netty.channel.raknet;

import java.net.InetSocketAddress;

public class RakPing {

    private final long pingTime;
    private final InetSocketAddress sender;

    public RakPing(long pingTime, InetSocketAddress sender) {
        this.pingTime = pingTime;
        this.sender = sender;
    }

    public long getPingTime() {
        return this.pingTime;
    }


    public InetSocketAddress getSender() {
        return this.sender;
    }
}
