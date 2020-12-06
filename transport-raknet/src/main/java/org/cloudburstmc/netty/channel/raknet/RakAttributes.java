package org.cloudburstmc.netty.channel.raknet;

import io.netty.util.AttributeKey;
import org.cloudburstmc.netty.RakState;

public final class RakAttributes {

    public static final AttributeKey<RakState> RAK_STATE = AttributeKey.valueOf(RakState.class, "RAK_STATE");

    private RakAttributes() {
    }
}
