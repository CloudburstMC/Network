package org.cloudburstmc.netty.signaling.control;

/** A synchronization decision; only the current coordinator exchange can mint a confirmation. */
public final class ControlSynchronizationResult {
    private static final ControlSynchronizationResult AWAITING = new ControlSynchronizationResult(null, null);
    private final Object owner;
    private final ControlStateCodec.Acknowledgement acknowledgement;

    private ControlSynchronizationResult(Object owner, ControlStateCodec.Acknowledgement acknowledgement) {
        this.owner = owner; this.acknowledgement = acknowledgement;
    }

    /** Application may finish before its acknowledged state reaches the signed source cache. */
    public static ControlSynchronizationResult awaitingSource() { return AWAITING; }
    public boolean confirmed() { return owner != null; }
    static ControlSynchronizationResult confirmed(Object owner, ControlStateCodec.Acknowledgement acknowledgement) {
        return new ControlSynchronizationResult(java.util.Objects.requireNonNull(owner), java.util.Objects.requireNonNull(acknowledgement));
    }
    boolean belongsTo(Object expectedOwner, ControlStateCodec.Summary state) {
        return owner == expectedOwner && acknowledgement != null && ControlStateCodec.matches(state, acknowledgement);
    }
}
