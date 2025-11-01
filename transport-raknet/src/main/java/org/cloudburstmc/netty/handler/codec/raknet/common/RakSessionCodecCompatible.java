package org.cloudburstmc.netty.handler.codec.raknet.common;

import org.cloudburstmc.netty.channel.raknet.*;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakDatagramPacket;

public class RakSessionCodecCompatible extends RakSessionCodec {
    public static final String NAME = "rak-session-codec";

    public RakSessionCodecCompatible(RakChannel channel) {
        super(channel);
    }

    @Override
    RakDatagramPacket createDatagramPacket() {
        return RakDatagramPacket.newInstance();
    }

    @Override
    EncapsulatedPacket createEncapsulatedPacket() {
        return EncapsulatedPacket.newInstance();
    }
}
