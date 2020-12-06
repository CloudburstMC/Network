package org.cloudburstmc.netty;

import com.nukkitx.network.util.DisconnectReason;
import io.netty.buffer.ByteBuf;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public interface RakNetSessionListener {

    void onSessionChangeState(RakState state);

    void onDisconnect(DisconnectReason reason);

    void onEncapsulated(EncapsulatedPacket packet);

    void onDirect(ByteBuf buf);
}
