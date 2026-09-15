package org.cloudburstmc.netty.signaling.control;

/** A verified frame whose original authority must still hold when queued application work runs. */
public final class ControlFrameDelivery {
    private final ControlFrameCodec.Frame frame;
    private final Runnable current;

    ControlFrameDelivery(ControlFrameCodec.Frame frame, Runnable current) {
        this.frame = java.util.Objects.requireNonNull(frame);
        this.current = java.util.Objects.requireNonNull(current);
    }

    /** The frame is immutable; retaining its bytes does not retain permission to apply them. */
    public ControlFrameCodec.Frame frame() { requireCurrent(); return frame; }

    /** Application executors check immediately before and after asynchronous application. */
    public void requireCurrent() { current.run(); }

    @Override public String toString() { return "ControlFrameDelivery[type=" + frame.type() + ", payload=redacted]"; }
}
