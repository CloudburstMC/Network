package com.nukkitx.network.raknet;

import lombok.Value;

@Value
public class RakNetPong {
    private final String advertisement;
    private final long delay;
    private final long serverId;
}
