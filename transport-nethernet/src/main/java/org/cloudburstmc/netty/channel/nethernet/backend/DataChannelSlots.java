package org.cloudburstmc.netty.channel.nethernet.backend;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;

import java.util.Objects;
import java.util.function.Consumer;

final class DataChannelSlots<T> {
    private final Consumer<T> closeRejected;
    private volatile T reliable;
    private volatile T unreliable;
    private boolean closed;

    DataChannelSlots(Consumer<T> closeRejected) {
        this.closeRejected = Objects.requireNonNull(closeRejected, "closeRejected");
    }

    boolean admit(T channel, Parameters parameters) {
        Objects.requireNonNull(channel, "channel");
        synchronized (this) {
            if (channel == reliable || channel == unreliable) {
                return false;
            }
            if (!closed && !parameters.negotiated()
                    && parameters.maxPacketLifeTime() == 0 && parameters.maxRetransmits() == 0) {
                if (NetherNetConstants.RELIABLE_CHANNEL_LABEL.equals(parameters.label())
                        && parameters.ordered() && parameters.reliable() && reliable == null) {
                    reliable = channel;
                    return true;
                }
                if (NetherNetConstants.UNRELIABLE_CHANNEL_LABEL.equals(parameters.label())
                        && !parameters.ordered() && !parameters.reliable() && unreliable == null) {
                    unreliable = channel;
                    return true;
                }
            }
        }
        // Native close can invoke callbacks. Do not hold the slot lock across it.
        closeRejected.accept(channel);
        return false;
    }

    T reliable() {
        return reliable;
    }

    T unreliable() {
        return unreliable;
    }

    synchronized void stopAccepting() {
        closed = true;
    }

    // The Java WebRTC API reports both absent limits and explicit zero as 0.
    // isReliable distinguishes the reliable stream from partial reliability.
    // Subprotocol metadata is intentionally ignored for compatibility with older clients.
    record Parameters(String label, boolean ordered, boolean reliable, boolean negotiated,
                      int maxPacketLifeTime, int maxRetransmits) {
    }
}
