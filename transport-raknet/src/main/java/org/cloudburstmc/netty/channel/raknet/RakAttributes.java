package org.cloudburstmc.netty.channel.raknet;

import io.netty.util.AttributeKey;
import org.cloudburstmc.netty.RakState;

public final class RakAttributes {

    public static final AttributeKey<RakState> RAK_STATE = AttributeKey.valueOf(RakState.class, "RAK_STATE");

    public static final AttributeKey<Integer> RAK_PROTOCOL_VERSION = AttributeKey.valueOf(Integer.class, "RAK_PROTOCOL_VERSION");

    public static final AttributeKey<Integer> RAK_MTU = AttributeKey.valueOf(Integer.class, "RAK_MTU");

    private RakAttributes() {
    }
}
