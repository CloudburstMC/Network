package org.cloudburstmc.netty.util.nethernet;

import java.security.PublicKey;

/** One login decision, owned by a transport channel. Never exposes admission secrets. */
public abstract class IdentityKeyVerifier implements AutoCloseable {
    private enum State { PENDING, ACCEPTED, REJECTED, CLOSED }
    private State state = State.PENDING;

    public final synchronized String mismatch(PublicKey key) {
        if (state != State.PENDING) return "the transport identity binding has already been consumed";
        boolean accepted = false;
        try {
            accepted = usable() && matches(IdentityPublicKey.canonical(key));
            return accepted ? null : "the login key does not match the admitted transport identity";
        } catch (Exception invalid) {
            return "the transport or login identity key is invalid";
        } finally {
            state = accepted ? State.ACCEPTED : State.REJECTED;
            release();
        }
    }

    /** Only for an application's already authenticated, explicitly trusted forwarding path. */
    public final synchronized String acceptForwardedIdentity() {
        if (state != State.PENDING) return "the transport identity binding has already been consumed";
        boolean accepted = usable();
        state = accepted ? State.ACCEPTED : State.REJECTED;
        release();
        return accepted ? null : "the transport identity binding has expired or been revoked";
    }

    public final synchronized boolean pending() { return state == State.PENDING; }

    public final synchronized boolean rejected() {
        return state == State.REJECTED || state == State.CLOSED || (state == State.PENDING && !usable());
    }

    @Override
    public final synchronized void close() {
        if (state == State.PENDING) {
            state = State.CLOSED;
            release();
        }
    }

    protected boolean usable() { return true; }
    protected abstract boolean matches(byte[] canonicalKey);
    protected abstract void release();
}
