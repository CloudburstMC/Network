package org.cloudburstmc.netty.channel.raknet;

import java.net.InetSocketAddress;

public class RakPong {

    private final long pingTime;
    private final long pongTime;
    private final long guid;
    private final byte[] pongData;
    private final InetSocketAddress sender;

    public RakPong(long pingTime, long pongTime, long guid, byte[] pongData, InetSocketAddress sender) {
        this.pingTime = pingTime;
        this.pongTime = pongTime;
        this.guid = guid;
        this.pongData = pongData;
        this.sender = sender;
    }

    public long getPingTime() {
        return this.pingTime;
    }

    public long getPongTime() {
        return this.pongTime;
    }

    public long getGuid() {
        return this.guid;
    }

    public byte[] getPongData() {
        return this.pongData;
    }

    public InetSocketAddress getSender() {
        return this.sender;
    }
}
