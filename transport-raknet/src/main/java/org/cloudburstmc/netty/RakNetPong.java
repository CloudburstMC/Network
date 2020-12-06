package org.cloudburstmc.netty;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class RakNetPong {
    private final long pingTime;
    private final long pongTime;
    private final long guid;
    private final byte[] userData;
}
