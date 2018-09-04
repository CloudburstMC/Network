package com.nukkitx.network.raknet.datagram;

public enum RakNetReliability {
    UNRELIABLE(false),
    UNRELIABLE_SEQUENCED(false),
    RELIABLE(false),
    RELIABLE_ORDERED(true),
    RELIABLE_SEQUENCED(false),
    UNRELIABLE_WITH_ACK_RECEIPT(false),
    RELIABLE_WITH_ACK_RECEIPT(false),
    RELIABLE_ORDERED_WITH_ACK_RECEIPT(true);

    private final boolean ordered;

    RakNetReliability(boolean ordered) {
        this.ordered = ordered;
    }

    public boolean isOrdered() {
        return ordered;
    }
}